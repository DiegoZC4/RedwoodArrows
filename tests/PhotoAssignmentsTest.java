package org.redwoodarrows;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class PhotoAssignmentsTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static Geometry.Calibration cal(double x){Geometry.Calibration c=new Geometry.Calibration(new double[]{.01,0,-x*.01,0,-.01,2,0,0,1},0,"test · reference image-up");c.reviewed=true;return c;}
    static Set<Integer> ids(Integer... ids){return new TreeSet<>(Arrays.asList(ids));}
    static Store.Result result(Store.Group g,int id){for(Store.Result r:Store.results(g))if(r.id==id)return r;throw new AssertionError("Missing arrow "+id);}
    static Store.Group group(Store store)throws Exception {
        Store.Group g=Store.Group.start(TargetCatalog.find("mlp-nff-20"),new ShooterRoster.Person("diego","Diego"));g.name="Assignment fixture";g.practice.meters=18.288;
        Store.View v=new Store.View();v.file=v.id+".jpg";v.width=600;v.height=800;v.calibration=cal(200);
        for(int id=1;id<=3;id++)v.observations.add(new Store.Observation(id,new double[]{200+id*10,200},null));g.views.add(v);
        Files.write(new File(store.directory(g),v.file).toPath(),new byte[]{1,2,3,4});store.save(g);return g;
    }
    public static void main(String[] args)throws Exception {
        Path temporary=Files.createTempDirectory("redwood-photo-assign-test-");
        try{
            Store store=new Store(temporary.toFile());Store.Group source=group(store);String before=source.json().toString();String fileHash=SyncFiles.hash(new File(store.directory(source),source.views.get(0).file));
            PhotoAssignments edit=new PhotoAssignments(source,source.views.get(0).id);edit.draft.views.get(0).observations.get(1).source="impact-model:photo-suggestion";
            edit.assign(ids(2,3),cal(400));check(source.json().toString().equals(before),"Draft never mutates source");
            check(result(edit.draft,1).x==result(source,1).x,"Unselected position unchanged");check(Math.abs(result(edit.draft,2).x+180)<1e-8,"Subset uses chosen target, not nearest target");
            check(Store.results(edit.draft).size()==3,"No added or duplicated arrows");check(PhotoAssignments.photoIndices(edit.draft).size()==1,"Target aliases remain one logical photo");
            check(edit.family().stream().flatMap(v->v.observations.stream()).filter(o->o.id==2).allMatch(o->o.source.startsWith("target-assigned:impact-model:")),"Manual target choice preserves detector provenance and blocks automatic replacement");
            Store.Group saved=edit.commit(store);check(saved.shooter.id.equals(source.shooter.id)&&saved.practice.meters==source.practice.meters,"Shooter and distance retained");
            check(SyncFiles.hash(new File(store.directory(saved),source.views.get(0).file)).equals(fileHash),"Original JPEG unchanged");
            SyncFiles.Snapshot wire=SyncFiles.snapshot(store,saved);check(wire.photos.length()==2,"Backward-compatible unique photo names");check(wire.photos.getJSONObject(0).getString("sha256").equals(wire.photos.getJSONObject(1).getString("sha256")),"Cloud can deduplicate identical JPEG blobs");
            Store.Group roundTrip=Store.Group.from(new org.json.JSONObject(wire.body));check(Math.abs(result(roundTrip,2).x+180)<1e-8,"Coordinates survive serialization and sync");
            PhotoAssignments all=new PhotoAssignments(saved,source.views.get(0).id);all.assign(ids(1,2,3),cal(400));check(all.draft.views.size()==saved.views.size(),"Reuses existing target, no endless copies");check(Math.abs(result(all.draft,1).x+190)<1e-8,"All-arrow assignment");
            String undo=all.snapshot();int added=all.add(400,200);check(added==4&&!Double.isFinite(result(all.draft,added).x),"Manual entry stays unresolved until assigned");all.assign(ids(added),cal(400));check(Math.abs(result(all.draft,added).x)<1e-8,"Missed entry assigned without detector");all.restore(undo);check(all.ids().equals(ids(1,2,3)),"Undo restores marks and targets");
            all.remove(ids(2));check(!all.ids().contains(2)&&all.ids().contains(1),"Remove only selected marks");
            boolean invalid=false;try{all.assign(ids(99),cal(400));}catch(IllegalArgumentException e){invalid=true;}check(invalid,"Reject nonexistent selection");
            Store.View other=Store.View.from(source.views.get(0).json());other.id=UUID.randomUUID().toString();other.file=other.id+".jpg";source.views.add(other);Files.write(new File(store.directory(source),other.file).toPath(),new byte[]{5,6,7});
            PhotoAssignments multi=new PhotoAssignments(source,source.views.get(0).id);multi.assign(ids(2),cal(400));check(result(multi.draft,2).views==1,"Old target in another image cannot bias reassigned arrow");check(result(multi.draft,1).views==2,"Other arrows retain cross-view evidence");
            int visible=0;for(Store.View v:multi.draft.views)for(Store.Observation o:v.observations)if(o.id==2)visible++;check(visible==2,"Other-view pixels retained for later reassignment");
            PhotoAssignments stale=new PhotoAssignments(saved,saved.views.get(0).id);stale.assign(ids(1),cal(500));String current=Files.readString(new File(store.directory(saved),"group.json").toPath());
            Store.Group latest=store.read(new File(store.directory(saved),"group.json"));latest.name="Newer user edit";store.save(latest);boolean conflict=false;try{stale.commit(store);}catch(IOException e){conflict=true;}check(conflict,"Stale save refused");check(store.read(new File(store.directory(saved),"group.json")).name.equals("Newer user edit"),"Concurrent user change preserved");
            for(Store.View v:stale.draft.views)if(stale.copies.containsKey(v.file))check(!new File(store.directory(saved),v.file).exists(),"Failed commit cleans only its new alias");
            System.out.println("Photo assignment checks passed: "+checks);
        }finally{try(java.util.stream.Stream<Path> paths=Files.walk(temporary)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new RuntimeException(e);}});}}
    }
}
