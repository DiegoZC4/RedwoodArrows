package org.redwoodarrows;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Deterministic queue/merge tests; no Android, neural network or private photos. */
public final class PhotoDetectionTest {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static Store.Group fixture(Store store,boolean queue)throws Exception {
        Store.Group g=Store.Group.start(TargetCatalog.defaultPreset(),new ShooterRoster.Person("alex","Alex"));g.practice.meters=9.144;
        Store.View v=new Store.View();v.width=v.height=400;v.file=v.id+".jpg";if(queue)PhotoDetection.prepare(store,v);g.views.add(v);Files.write(new File(store.directory(g),v.file).toPath(),new byte[]{1,2,3});store.save(g);return g;
    }
    static Store.Group read(Store s,Store.Group g)throws Exception{return s.read(new File(s.directory(g),"group.json"));}
    static PhotoDetection.Task task(Store s,Store.Group g)throws Exception {Store.Group fresh=read(s,g);return new PhotoDetection.Task(s,fresh,fresh.views.get(0));}
    static Geometry.Calibration fit(double center){return new Geometry.Calibration(new double[]{.01,0,-center*.01,0,-.01,2,0,0,1},.01,"reference image-up synthetic fit");}
    static List<double[]> points(){return Arrays.asList(new double[]{200,180,.9},new double[]{201,181,.8},new double[]{220,200,.95});}
    public static void main(String[] args)throws Exception {
        Path temp=Files.createTempDirectory("redwood-photo-queue-");try{
            Store store=new Store(temp.resolve("a").toFile());Store.Group old=fixture(store,false);String unchanged=read(store,old).json().toString();
            check(PhotoDetection.pending(store).isEmpty()&&!PhotoDetection.ownerFile(store).exists(),"Old photos are never backfilled or modified on upgrade");
            Store.Group g=fixture(store,true);PhotoDetection.Task task=task(store,g);check(PhotoDetection.pending(new Store(temp.resolve("a").toFile())).size()==1,"Pending work survives a new Store/process");
            Store.Group labels=read(store,g);labels.shooter=new ShooterRoster.Person("sam","Sam");labels.practice.meters=13.716;store.save(labels);
            Store.editingGroupId=g.id;check(!PhotoDetection.complete(store,task,points(),fit(200),false),"Open editor defers late completion");Store.editingGroupId=null;
            check(PhotoDetection.complete(store,task,points(),fit(200),false),"Completes after editing closes");Store.Group done=read(store,g);Store.View view=done.views.get(0);
            check(done.shooter.name.equals("Sam")&&done.practice.meters==13.716,"Concurrent shooter and distance corrections survive");
            check(view.observations.size()==2&&view.detectionState.equals("done"),"Dots deduplicated and committed once");check(!PhotoDetection.complete(store,task,points(),fit(200),false),"Duplicate callback cannot duplicate dots");
            check(PhotoDetection.pending(store).isEmpty(),"Successful job leaves durable queue");check(view.calibration!=null&&!view.calibration.reviewed,"Auto fit is not a manual review");
            check(PhotoDetection.previewResults(done).stream().filter(r->Double.isFinite(r.x)).count()==2,"Provisional 2D is visible without review");check(Store.results(done).stream().noneMatch(r->Double.isFinite(r.x)),"Provisional fit does not contaminate progress statistics");
            check(view.json().toString().equals(Store.View.from(view.json()).json().toString()),"Detection provenance survives serialization");
            check(Arrays.equals(Files.readAllBytes(new File(store.directory(done),view.file).toPath()),new byte[]{1,2,3}),"Detection never rewrites the photograph");
            Store.Group zero=fixture(store,true);check(PhotoDetection.complete(store,task(store,zero),Collections.emptyList(),null,false),"Zero detections is a completed job");check(PhotoDetection.status(read(store,zero)).equals("No arrows found"),"Zero result is not perpetual processing");
            Store.Group failed=fixture(store,true);check(PhotoDetection.complete(store,task(store,failed),Collections.emptyList(),null,true),"Failure saves retryable UI state");check(PhotoDetection.status(read(store,failed)).contains("photo saved")&&read(store,failed).views.size()==1,"Failure keeps original photo and metadata");
            Store.Group manual=fixture(store,true);PhotoDetection.Task late=task(store,manual);PhotoAssignments edit=new PhotoAssignments(read(store,manual),manual.views.get(0).id);int id=edit.add(40,50);edit.remove(Collections.singleton(id));edit.commit(store);
            check(!PhotoDetection.complete(store,late,points(),null,false)&&read(store,manual).views.get(0).observations.isEmpty(),"Removing every dot is not undone by a late result");
            Store.Group geometry=fixture(store,true);late=task(store,geometry);Store.Group newer=read(store,geometry);newer.views.get(0).calibration=fit(150);store.save(newer);PhotoDetection.complete(store,late,points(),null,false);
            check(read(store,geometry).views.get(0).calibration.h[2]==-1.5&&read(store,geometry).views.get(0).observations.isEmpty(),"Changed geometry wins");
            Store.Group changedFile=fixture(store,true);late=task(store,changedFile);Files.write(new File(store.directory(changedFile),changedFile.views.get(0).file).toPath(),new byte[]{9});PhotoDetection.complete(store,late,points(),null,false);check(read(store,changedFile).views.get(0).observations.isEmpty(),"A replaced photo cannot receive stale coordinates");
            Store other=new Store(temp.resolve("b").toFile());Store.Group remote=Store.Group.from(g.json());Files.write(new File(other.directory(remote),remote.views.get(0).file).toPath(),new byte[]{1,2,3});other.save(remote);PhotoDetection.owner(other,true);check(PhotoDetection.pending(other).isEmpty(),"Receiving phone does not race the capturing phone's detector");
            check(read(store,old).json().toString().equals(unchanged),"Historic labels remain byte-for-byte equivalent");
            check(PhotoDetection.target(Arrays.asList(fit(200)),points())!=null,"Clearly dominant face can produce an estimated plot");
            check(PhotoDetection.target(Arrays.asList(fit(80),fit(300)),Arrays.asList(new double[]{80,200,.9},new double[]{300,200,.9}))==null,"Two occupied faces stay unassigned rather than guessing intent");
            check(PhotoDetection.target(Arrays.asList(fit(80)),Arrays.asList(new double[]{350,350,.9}))==null,"Distant target is not silently selected");
            Store broken=new Store(temp.resolve("broken-queue").toFile());Files.write(PhotoDetection.ownerFile(broken).toPath(),new byte[]{1});Store.Group preserved=fixture(broken,true);check(read(broken,preserved).views.get(0).detectionState.equals("failed"),"Even queue setup failure cannot block photo storage");
            System.out.println("Background photo queue checks passed: "+checks);
        }finally{Store.editingGroupId=null;try(java.util.stream.Stream<Path> paths=Files.walk(temp)){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}}
    }
}
