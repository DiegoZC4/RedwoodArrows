package org.redwoodarrows;

import java.util.*;

public final class CaptureDefaultsTest {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static Store.Group end(ShooterRoster.Person person,long time,double distance){Store.Group g=Store.Group.start(TargetCatalog.defaultPreset(),person);g.created=time;g.practice.meters=Practice.toMeters(distance,"yd");g.practice.fired=6;g.practice.misses=0;return g;}
    static void yards(Practice p,double expected,String why){check(Math.abs(Practice.fromMeters(p.meters,"yd")-expected)<1e-8,why);}
    public static void main(String[] args)throws Exception {
        ShooterRoster r=new ShooterRoster();List<Store.Group> history=new ArrayList<>();check(!CaptureDefaults.next(r,history,null).assigned(),"No roster stays unassigned");
        ShooterRoster.Person alex=r.add("Alex"),sam=r.add("Sam");check(CaptureDefaults.next(r,history,null).id.equals(alex.id),"First roster person starts");r.select(sam.id);check(CaptureDefaults.next(r,history,null).id.equals(sam.id),"Explicit initial active person");
        Store.Group first=end(alex,100,10);history.add(first);check(CaptureDefaults.next(r,history,null).id.equals(sam.id),"Alternate after saved round");
        Store.Group cancelled=Store.Group.start(TargetCatalog.defaultPreset(),sam);cancelled.created=200;history.add(cancelled);check(CaptureDefaults.next(r,history,null).id.equals(sam.id),"Empty cancelled draft never advances");
        Store.Group second=end(sam,300,15);history.add(second);check(CaptureDefaults.next(r,history,null).id.equals(alex.id),"Alternates back");
        yards(CaptureDefaults.previous(alex,history),10,"Alex's own range");yards(CaptureDefaults.previous(sam,history),15,"Sam's own range");
        second.views.add(new Store.View());second.views.add(new Store.View());check(CaptureDefaults.next(r,history,null).id.equals(alex.id),"Another view does not consume another turn");
        Collections.reverse(history);check(CaptureDefaults.next(r,history,null).id.equals(alex.id),"Order independent");
        check(CaptureDefaults.next(r,history,sam.id).id.equals(sam.id),"Manual same-shooter override");check(!CaptureDefaults.next(r,history,"").assigned(),"Explicit unknown override");check(CaptureDefaults.next(r,history,"missing").id.equals(alex.id),"Missing override ignored");
        Store.Group recent=end(alex,400,25);recent.practice.setup="Recurve";recent.practice.checked="do not copy";recent.practice.arrowMeters.put(1,recent.practice.meters);history.add(recent);
        Practice copy=CaptureDefaults.previous(alex,history);yards(copy,25,"Latest own round");check(copy.fired==-1&&copy.misses==-1&&copy.checked.isEmpty()&&copy.arrowMeters.isEmpty(),"Do not copy counts, review or arrow overrides");check(copy.setup.equals("Recurve"),"Own setup retained");copy.meters=1;yards(recent.practice,25,"Independent copy");
        recent.practice.arrowMeters.put(1,Practice.toMeters(20,"yd"));check(Double.isNaN(CaptureDefaults.previous(alex,history).meters),"Mixed walk-up not misrepresented as one range");recent.practice.arrowMeters.clear();recent.practice.meters=Double.NaN;check(Double.isNaN(CaptureDefaults.previous(alex,history).meters),"Cleared last range does not revive old range");
        Store.Group provisional=end(sam,500,50);Store.View view=new Store.View();Store.Observation o=new Store.Observation(1,new double[]{1,2},null);o.associationWarning="Shooter provisional: uncertain handoff.";view.observations.add(o);provisional.views.add(view);history.add(provisional);
        check(CaptureDefaults.next(r,history,null).id.equals(sam.id),"Uncertain owner cannot change alternation");yards(CaptureDefaults.previous(sam,history),15,"Uncertain owner cannot change remembered distance");
        Store.Group unknown=end(ShooterRoster.UNASSIGNED,600,40);history.add(unknown);check(CaptureDefaults.next(r,history,null).id.equals(sam.id),"Unassigned rounds ignored");check(Double.isNaN(CaptureDefaults.previous(ShooterRoster.UNASSIGNED,history).meters),"Do not learn range from unassigned person");
        Store.Group retired=end(new ShooterRoster.Person("retired","Retired"),700,50);history.add(retired);check(CaptureDefaults.next(r,history,null).id.equals(sam.id),"Non-roster person not selected");
        ShooterRoster solo=new ShooterRoster();solo.remember(alex);check(CaptureDefaults.next(solo,history,null).id.equals(alex.id),"Solo roster repeats");
        ShooterRoster restored=ShooterRoster.from(r.json().toString());check(CaptureDefaults.next(restored,history,null).id.equals(sam.id),"Restart restores automatic suggestion from saved data");
        check(!CaptureDefaults.previous(new ShooterRoster.Person("new","New"),history).label().contains("15"),"New shooter never borrows range");
        System.out.println("Capture defaults: "+checks+" checks passed");
    }
}
