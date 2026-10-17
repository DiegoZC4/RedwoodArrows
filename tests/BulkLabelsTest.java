package org.redwoodarrows;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

public final class BulkLabelsTest {
    static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    interface Job{void run()throws Exception;}static void rejects(Job j)throws Exception{try{j.run();throw new AssertionError("Expected safe rejection");}catch(IOException expected){checks++;}}
    static Store.Group reload(Store s,Store.Group g)throws Exception{return s.read(new File(s.directory(g),"group.json"));}
    static Store.Group sample(Store s,String id,int day)throws Exception{Store.Group g=ConsistencyStatsTest.end(ShooterRoster.UNASSIGNED,day,9.144,3,6);g.id=id;g.practice.meters=Double.NaN;s.save(g);return g;}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("redwood-bulk-labels-");
        try{
            Store s=new Store(dir.toFile());Store.Group one=sample(s,"one",0),two=sample(s,"two",1);String before1=one.json().toString(),before2=two.json().toString();
            BulkLabels.Change c=new BulkLabels.Change();c.shooter=true;c.person=ConsistencyStatsTest.A;
            check(BulkLabels.apply(s,Arrays.asList(one,two,one),c)==2,"Duplicate IDs counted once");
            Store.Group changed=reload(s,one);check(changed.shooter.id.equals("a")&&!Practice.distance(changed.practice.meters),"Only requested shooter changes");
            check(changed.savedResults.get(0).x==one.savedResults.get(0).x,"Positions exact");check(BulkLabels.undoCount(new Store(dir.toFile()))==2,"Undo survives restart");
            check(BulkLabels.undo(s)==2,"Undo covers batch");check(BulkLabels.same(before1,reload(s,one).json().toString())&&BulkLabels.same(before2,reload(s,two).json().toString()),"Exact context and marks restored");
            check(BulkLabels.undoCount(s)==0,"Undo one-shot");
            one=reload(s,one);two=reload(s,two);two.savedResults.get(0).warning="Shooter provisional: old guess. Geometry uncertain.";two.practice.arrowMeters.put(1,13.716);s.save(two);
            c.distance=true;c.meters=9.144;check(BulkLabels.apply(s,Arrays.asList(one,two),c)==2,"Both fields applied");
            Store.Group second=reload(s,two);check(second.practice.meters==9.144&&second.practice.arrowMeters.get(1)==13.716,"Walk-up distances retained");
            check(second.savedResults.get(0).warning.equals("Geometry uncertain."),"Only ownership warning cleared");
            check(!LabelCoverage.shooter(second)&&!LabelCoverage.distance(second),"Coverage updates without marking coordinates reviewed");
            Store.Group stale=reload(s,one),newer=reload(s,one);newer.name="New edit";s.save(newer);Store.Group other=reload(s,two);String otherBefore=other.json().toString();
            c.person=ConsistencyStatsTest.B;rejects(()->BulkLabels.apply(s,Arrays.asList(other,stale),c));check(BulkLabels.same(otherBefore,reload(s,other).json().toString()),"Stale preflight changes nothing");rejects(()->BulkLabels.undo(s));
            Store.Group third=sample(s,"three",2),fourth=sample(s,"four",3);String thirdBefore=third.json().toString();Path blocker=new File(s.directory(fourth),"group.json.bulk-new").toPath();Files.createDirectory(blocker);
            rejects(()->BulkLabels.apply(s,Arrays.asList(third,fourth),c));check(BulkLabels.same(thirdBefore,reload(s,third).json().toString()),"Partial failure rolls back earlier rows");Files.delete(blocker);
            // Simulated process death between group commits: startup restores before.
            Store.Group after=c.apply(third);JSONObject row=new JSONObject().put("id",third.id).put("before",thirdBefore).put("after",after.json().toString());
            BulkLabels.write(BulkLabels.journal(s),new JSONObject().put("state","pending").put("rows",new JSONArray().put(row)).toString());BulkLabels.write(new File(s.directory(third),"group.json"),after.json().toString());
            Store recovered=new Store(dir.toFile());check(BulkLabels.same(thirdBefore,reload(recovered,third).json().toString()),"Interrupted transaction recovered");
            List<Store.Group> groups=Arrays.asList(third,fourth,third);ConsistencyStats stats=new ConsistencyStats(groups,Arrays.asList(ConsistencyStatsTest.A,ConsistencyStatsTest.B),"",false,Double.NaN,0);
            check(stats.times.size()==2&&stats.series.get(0).samples.isEmpty(),"Entirely unlabeled days visible without samples");
            check(stats.labelDays.get(ConsistencyStats.day(third.created)).pending.size()==1,"Coverage dedupes groups");
            check(LabelCoverage.days(groups,"different",Double.NaN).isEmpty(),"Equipment filter respected");
            check(LabelCoverage.days(groups,"",9.144).size()==2,"Unknown distances visible under range filter");
            third.practice.meters=13.716;check(LabelCoverage.days(Arrays.asList(third),"",9.144).isEmpty(),"Known other distance excluded");
            c.shooter=false;c.distance=false;Store.Group fresh=reload(s,third);rejects(()->BulkLabels.apply(s,Arrays.asList(fresh),c));
            System.out.println("Bulk labels + missing-day checks passed: "+checks);
        }finally{try(java.util.stream.Stream<Path> files=Files.walk(dir)){for(Path p:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}}
    }
}
