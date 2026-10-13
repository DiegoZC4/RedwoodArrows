package org.redwoodarrows;
import java.util.*;
import java.io.*;
import java.nio.file.*;

public final class TargetSelectionTest {
    static int checks;
    static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
    static Geometry.Calibration cal(double x,double y,double r){return new Geometry.Calibration(new double[]{1/r,0,-x/r,0,-1/r,y/r,0,0,1},0,"fixture · reference image-up");}
    public static void main(String[] args)throws Exception {
        List<Geometry.Calibration> cs=Arrays.asList(cal(200,300,100),cal(700,500,180));
        check(TargetSelection.nearest(cs,205,305)==0,"Select small bull, not largest");check(TargetSelection.nearest(cs,700,500)==1,"Select other bull");check(TargetSelection.nearest(cs,480,400)==-1,"Background is not a selection");
        Store.Group g=Store.Group.start(TargetCatalog.defaultPreset(),ShooterRoster.UNASSIGNED);g.practice.meters=18.288;g.practice.setup="original";g.practice.arrowMeters.put(1,9.144);g.practice.checked="old";
        for(int i=0;i<3;i++){Store.View v=new Store.View();v.file="photo-"+i+".jpg";v.width=1000;v.height=1000;v.calibration=cal(700,500,180);v.calibration.reviewed=true;v.observations.add(new Store.Observation(1,new double[]{220,290},null));g.views.add(v);}
        String original=g.json().toString();Store.Group next=TargetSelection.draft(g,g.views.get(1).id,cs.get(0));check(original.equals(g.json().toString()),"Draft never mutates original");check(next.views.get(0).id.equals(g.views.get(1).id),"Selected photo becomes reference");check(TargetSelection.locked(next),"Choice locked");check(next.views.get(1).calibration==null&&next.views.get(2).calibration==null,"Old planes invalidated");
        for(Store.View v:next.views){Store.View before=null;for(Store.View old:g.views)if(old.id.equals(v.id))before=old;check(before!=null&&before.file.equals(v.file),"Photo identity unchanged");check(v.observations.get(0).json().toString().equals(before.observations.get(0).json().toString()),"Arrow marks preserved");}
        Store.Result r=Store.results(next).get(0);check(Math.abs(r.x-35)<1e-8&&Math.abs(r.y-17.5)<1e-8,"Millimeter positions use selected plane");check(r.views==1,"Unaligned views excluded");check(next.practice.checked.isEmpty(),"Old position review cleared");check(next.practice.meters==g.practice.meters&&next.practice.arrowMeters.equals(g.practice.arrowMeters)&&next.practice.setup.equals(g.practice.setup),"Range metadata preserved");
        Store.Group round=Store.Group.from(next.json());check(TargetSelection.locked(round),"Round trip retains selected target in schema 4");
        Path temp=Files.createTempDirectory("redwood-target-test-");try{Store store=new Store(temp.toFile());store.save(g);Store.Group stale=TargetSelection.draft(g,g.views.get(1).id,cs.get(0));g.name="Concurrent correction";store.save(g);boolean refused=false;try{store.save(stale);}catch(IOException e){refused=true;}check(refused,"Stale target correction rejected");check(store.read(new File(store.directory(g),"group.json")).name.equals(g.name),"Concurrent saved data untouched");}finally{try(java.util.stream.Stream<Path> walk=Files.walk(temp)){for(Path p:walk.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
        System.out.println("Target selection: "+checks+" checks passed");
    }
}
