package org.redwoodarrows;

import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Two simulated phones using the real PHP HTTP API. All photos are invented. */
public final class SyncEngineTest {
    static int checks;static byte[] jpeg;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static Store.Group make(Store store,String name)throws Exception {
        Store.Group g=Store.Group.start(TargetCatalog.defaultPreset(),new ShooterRoster.Person("archer-alex","Alex"));g.name=name;
        Store.View v=new Store.View();v.width=16;v.height=16;v.file=v.id+".jpg";
        v.calibration=new Geometry.Calibration(new double[]{.1,0,-.8,0,-.1,.8,0,0,1},0,"reference image-up fixture");v.calibration.reviewed=true;
        v.observations.add(new Store.Observation(1,new double[]{8,7},null));v.observations.add(new Store.Observation(2,new double[]{9,6},null));
        g.views.add(v);Files.write(new File(store.directory(g),v.file).toPath(),jpeg);store.save(g);return g;
    }
    static Store.Group read(Store store,String id)throws Exception{return store.read(new File(new File(store.root,id),"group.json"));}
    static SyncEngine engine(Store store,SyncEngine.Transport remote)throws Exception{return new SyncEngine(store,new File(store.root.getParentFile(),"sync"),remote);}
    static abstract class Proxy implements SyncEngine.Transport {
        final SyncEngine.Transport base;Proxy(SyncEngine.Transport b){base=b;}
        public JSONObject json(String a,JSONObject i)throws Exception{return base.json(a,i);}
        public void upload(String h,File f)throws Exception{base.upload(h,f);}
        public void download(String h,File f,long n)throws Exception{base.download(h,f,n);}
    }
    public static void main(String[] args)throws Exception {
        JSONObject settings=new JSONObject(new String(SyncFiles.bytes(System.in,100000),"UTF-8"));jpeg=Base64.getDecoder().decode(settings.getString("photo"));
        Path temp=Files.createTempDirectory("redwood-sync-phones-");
        try{
            Store a=new Store(temp.resolve("phone-a").toFile()),b=new Store(temp.resolve("phone-b").toFile());
            SyncHttp ra=new SyncHttp(settings.getString("endpoint"),settings.getString("owner"),true),rb=new SyncHttp(settings.getString("endpoint"),settings.getString("member"),true);
            Store.Group first=make(a,"Offline first end");String id=first.id,photo=first.views.get(0).file;String original=Store.csv(first);
            check(a.list().size()==1&&b.list().isEmpty(),"Offline capture does not depend on another device or auth");
            SyncEngine.Transport offline=new Proxy(ra){public JSONObject json(String action,JSONObject input)throws Exception{throw new IOException("Invented lost connection");}};
            try{engine(a,offline).run();throw new AssertionError("Offline request should fail");}catch(IOException expected){}
            check(read(a,id).name.equals(first.name),"Failed connection preserves local end");
            engine(a,ra).run();engine(b,rb).run();
            check(b.list().size()==1,"Other login downloads exactly one end");
            check(Store.csv(read(b,id)).equals(original),"Exact arrow positions and shooter survive round trip");
            check(Arrays.equals(Files.readAllBytes(new File(b.directory(read(b,id)),photo).toPath()),jpeg),"Image bytes survive round trip");
            check(read(b,id).shooter.id.equals("archer-alex"),"Photographer login never changes shooter");
            Store.Group aimed=read(a,id);aimed.views.get(0).calibration.source="chosen target · reference image-up fixture";aimed.views.get(0).calibration.h[2]-=.2;a.save(aimed);engine(a,ra).run();engine(b,rb).run();
            check(read(b,id).views.get(0).calibration.source.equals(aimed.views.get(0).calibration.source)&&Arrays.equals(read(b,id).views.get(0).calibration.h,aimed.views.get(0).calibration.h),"Chosen physical target and corrected plane sync exactly");
            engine(a,ra).run();engine(b,rb).run();check(b.list().size()==1,"Repeated sync never duplicates groupings");
            Store.Group edited=read(b,id);edited.name="Sam corrected the end";edited.shooter=new ShooterRoster.Person("archer-sam","Sam");b.save(edited);engine(b,rb).run();engine(a,ra).run();
            check(read(a,id).name.equals(edited.name)&&read(a,id).shooter.id.equals("archer-sam"),"Member corrections sync back to owner");
            Store.editingGroupId=id;edited=read(b,id);edited.name="Change while another phone reviews";b.save(edited);engine(b,rb).run();engine(a,ra).run();
            check(!read(a,id).name.equals(edited.name),"Remote changes never replace the grouping currently open for editing");
            Store.editingGroupId=null;engine(a,ra).run();check(read(a,id).name.equals(edited.name),"Deferred update arrives after leaving that grouping, even past the change cursor");
            try{first.name="Stale screen";a.save(first);throw new AssertionError("Stale save should fail");}catch(IOException expected){}
            check(read(a,id).name.equals(edited.name),"Old UI snapshot cannot overwrite a remote update");

            Store.Group local=read(a,id),other=read(b,id);local.name="A offline edit";other.name="B offline edit";a.save(local);b.save(other);
            engine(b,rb).run();SyncEngine conflicted=engine(a,ra);conflicted.run();
            check(conflicted.conflicts().equals(Collections.singletonList(id)),"Concurrent edits are visible conflicts");
            check(read(a,id).name.equals("A offline edit")&&read(b,id).name.equals("B offline edit"),"Both offline versions remain intact");
            long reviewed=ra.json("group&id="+id,null).getLong("revision");Store.Group more=read(b,id);more.name="Newer edit after comparison opened";b.save(more);engine(b,rb).run();conflicted.run();
            try{conflicted.resolve(id,true,reviewed);throw new AssertionError("A stale comparison must not overwrite a newer shared edit");}catch(IOException expected){}
            check(read(b,id).name.equals(more.name)&&read(a,id).name.equals("A offline edit"),"Choosing an outdated comparison cannot overwrite an unseen newer edit");
            conflicted.resolve(id,true);engine(b,rb).run();check(read(b,id).name.equals("A offline edit"),"Explicit keep-local resolves to other phone");
            check(engine(a,ra).conflicts().isEmpty(),"Resolved conflict cleared");
            check(new File(a.root.getParentFile(),"sync/recovery").list().length>0,"Resolution leaves a local recovery manifest");

            local=read(a,id);other=read(b,id);local.name="A second edit";other.name="B second edit";a.save(local);b.save(other);engine(b,rb).run();conflicted=engine(a,ra);conflicted.run();conflicted.resolve(id,false);
            check(read(a,id).name.equals("B second edit"),"Explicit use-shared keeps shared version");
            check(new File(a.directory(read(a,id)),photo).isFile(),"Conflict choice never deletes source image");

            Store.Group lost=make(a,"Lost acknowledgement");
            SyncEngine.Transport lostAck=new Proxy(ra){boolean once=true;public JSONObject json(String action,JSONObject input)throws Exception{JSONObject reply=super.json(action,input);if(action.equals("save")&&input.getString("id").equals(lost.id)&&once){once=false;throw new IOException("Invented disconnect after commit");}return reply;}};
            try{engine(a,lostAck).run();throw new AssertionError("Should lose acknowledgement");}catch(IOException expected){}
            engine(a,ra).run();engine(b,rb).run();check(b.list().size()==2&&engine(a,ra).conflicts().isEmpty(),"Crash/retry reconciles an acknowledged server commit without duplication or false conflict");

            Store.Group concurrent=make(a,"Snapshot before transmission");
            SyncEngine.Transport whileSending=new Proxy(ra){boolean once=true;public JSONObject json(String action,JSONObject input)throws Exception{if(action.equals("save")&&input.getString("id").equals(concurrent.id)&&once){once=false;Store.Group changed=read(a,concurrent.id);changed.name="Captured another local correction";a.save(changed);}return super.json(action,input);}};
            engine(a,whileSending).run();check(read(a,concurrent.id).name.equals("Captured another local correction"),"Sending older bytes cannot erase a simultaneous local save");
            engine(a,ra).run();engine(b,rb).run();check(read(b,concurrent.id).name.equals("Captured another local correction"),"Subsequent sync delivers changes made during upload");

            Store.Group unreceived=make(a,"Integrity test");engine(a,ra).run();
            SyncEngine.Transport corrupted=new Proxy(rb){public void download(String h,File f,long size)throws Exception{Files.write(f.toPath(),new byte[]{1,2,3});}};
            try{engine(b,corrupted).run();throw new AssertionError("Corrupt photo must not commit");}catch(IOException expected){}
            check(!new File(new File(b.root,unreceived.id),"group.json").exists(),"No half-downloaded group becomes visible");
            engine(b,rb).run();check(read(b,unreceived.id).name.equals("Integrity test"),"Interrupted download retries on next run");
            check(!new File(b.root.getParentFile(),"sync/download.part").exists(),"Partial download cleaned");

            Store.Group counts=Store.Group.start(TargetCatalog.defaultPreset(),ShooterRoster.UNASSIGNED);counts.keepPhotos=false;counts.practice.fired=6;counts.practice.misses=6;a.save(counts);engine(a,ra).run();engine(b,rb).run();
            check(read(b,counts.id).practice.misses==6&&read(b,counts.id).views.isEmpty(),"Counts-only ends sync with no images");
            Store.Group compact=make(a,"2D-only end");compact.keepPhotos=false;a.save(compact);compact=a.finishCoordinates(compact);engine(a,ra).run();engine(b,rb).run();
            check(read(b,compact.id).coordinatesOnly()&&Store.results(read(b,compact.id)).size()==2,"Lightweight coordinate snapshots sync exactly");
            Store.Group inbox=make(b,"Photo-only end from member");Store.View shot=inbox.views.get(0);shot.calibration=null;shot.observations.clear();shot.sourceHash=SyncFiles.hash(jpeg);shot.capturedAt=1735816333000L;b.save(inbox);
            engine(b,rb).run();engine(a,ra).run();Store.Group received=read(a,inbox.id);
            check(received.views.size()==1&&Store.results(received).isEmpty(),"Member photo sync does not require annotations or 2D estimates");
            check(received.views.get(0).sourceHash.equals(shot.sourceHash)&&received.views.get(0).capturedAt==shot.capturedAt,"Imported photo date and identity survive shared sync");
            check(Arrays.equals(Files.readAllBytes(new File(a.directory(received),shot.file).toPath()),jpeg),"Member's unannotated image reaches the owner intact");
            Store.View another=new Store.View();another.file=another.id+".jpg";another.width=16;another.height=16;Files.write(new File(b.directory(inbox),another.file).toPath(),jpeg);inbox.views.add(another);b.save(inbox);engine(b,rb).run();engine(a,ra).run();
            check(read(a,inbox.id).views.size()==2&&read(a,inbox.id).shooter.id.equals(inbox.shooter.id),"Adding another view updates the same shared grouping and shooter");
            int photoCount=a.list().size();engine(b,rb).run();engine(a,ra).run();check(a.list().size()==photoCount&&read(a,inbox.id).views.size()==2,"Repeated photo sync creates neither extra groupings nor views");
            SyncEngine bound=engine(a,ra);try{bound.bind("unrelated-library");throw new AssertionError("Library switch must fail");}catch(IOException expected){}
            check(read(a,id).name.equals("B second edit"),"Library-binding failure never changes local data");
            JSONObject bad=read(b,id).json();bad.getJSONArray("views").getJSONObject(0).put("file","../escape.jpg");
            try{SyncFiles.snapshot(b,Store.Group.from(bad));throw new AssertionError("Unsafe path accepted");}catch(IOException expected){}
            check(SyncFiles.canonical(new JSONObject("{\"z\":1,\"a\":2}")).equals(SyncFiles.canonical(new JSONObject("{\"a\":2,\"z\":1}"))),"Stable hashes across object ordering");
        }finally{Store.editingGroupId=null;try(java.util.stream.Stream<Path> files=Files.walk(temp)){for(Path file:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.delete(file);}}
        check(!Files.exists(temp),"Both simulated phones and recovery fixtures cleaned");System.out.println("Two-phone sync checks passed: "+checks);
    }
}
