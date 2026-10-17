package org.redwoodarrows;

import java.util.*;

/** Derive turns from saved ends, never camera opens, cancelled drafts or view count. */
final class CaptureDefaults {
    static boolean confirmed(Store.Group group){
        if(!group.hasData()||!group.shooter.assigned())return false;
        for(Store.View v:group.views)for(Store.Observation o:v.observations)if(provisional(o.associationWarning))return false;
        if(group.savedResults!=null)for(Store.Result r:group.savedResults)if(provisional(r.warning))return false;
        return true;
    }
    private static boolean provisional(String warning){return warning!=null&&warning.toLowerCase(Locale.US).contains("shooter provisional:");}
    private static boolean later(Store.Group a,Store.Group b){return b==null||a.created>b.created||(a.created==b.created&&a.id.compareTo(b.id)>0);}
    static ShooterRoster.Person next(ShooterRoster roster,List<Store.Group> history,String override){
        if(override!=null&&roster.find(override)!=null)return roster.find(override);
        List<ShooterRoster.Person> people=roster.all();if(people.isEmpty())return ShooterRoster.UNASSIGNED;
        Store.Group last=null;for(Store.Group group:history)if(confirmed(group)&&roster.find(group.shooter.id)!=null&&later(group,last))last=group;
        if(last!=null)for(int i=0;i<people.size();i++)if(people.get(i).id.equals(last.shooter.id))return people.get((i+1)%people.size());
        return roster.active().assigned()?roster.active():people.get(0);
    }
    static Practice previous(ShooterRoster.Person person,List<Store.Group> history)throws Exception {
        Store.Group last=null;for(Store.Group group:history)if(confirmed(group)&&group.shooter.id.equals(person.id)&&later(group,last))last=group;
        if(last==null)return new Practice();
        Practice next=last.practice.next();
        // A walk-up end has no single distance to remember. Unknown stays
        // unknown, including an intentionally cleared most-recent distance.
        for(double value:last.practice.arrowMeters.values())if(!Practice.distance(next.meters)||Math.abs(value-next.meters)>1e-8)next.meters=Double.NaN;
        return next;
    }
}
