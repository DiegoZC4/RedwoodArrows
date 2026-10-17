package org.redwoodarrows;

import java.util.*;

/** Missing context is visible evidence, never an invented angular measurement. */
final class LabelCoverage {
    static boolean shooter(Store.Group g){
        if(!g.shooter.assigned())return true;
        for(Store.Result r:Store.results(g))if(r.warning.toLowerCase(Locale.US).contains("shooter provisional"))return true;
        return false;
    }
    static boolean distance(Store.Group g){
        List<Store.Result> results=Store.results(g);
        if(results.isEmpty()||g.practice.misses>0||g.practice.fired>results.size())if(!Practice.distance(g.practice.meters))return true;
        for(Store.Result r:results)if(!Practice.distance(g.practice.distanceFor(r.id)))return true;
        return false;
    }
    static boolean needs(Store.Group g){return shooter(g)||distance(g);}
    static final class Day {
        final long time;final List<String> ids=new ArrayList<>(),pending=new ArrayList<>();int shooters,distances;
        Day(long time){this.time=time;}
        String summary(){return pending.size()+" rounds need labels · "+shooters+" shooter · "+distances+" distance";}
    }
    static SortedMap<Long,Day> days(List<Store.Group> groups,String setup,double distance){
        SortedMap<Long,Day> days=new TreeMap<>();Set<String> seen=new HashSet<>();
        for(Store.Group g:groups){
            if(!seen.add(g.id)||!g.hasData()||(setup!=null&&!setup.equals(g.practice.setup)))continue;
            boolean missingDistance=distance(g);
            if(Practice.distance(distance)&&!missingDistance&&!ProgressStats.atDistance(g,Store.results(g),distance))continue;
            long time=ConsistencyStats.day(g.created);Day day=days.get(time);if(day==null){day=new Day(time);days.put(time,day);}
            day.ids.add(g.id);boolean missingShooter=shooter(g);
            if(missingShooter)day.shooters++;if(missingDistance)day.distances++;
            if(missingShooter||missingDistance)day.pending.add(g.id);
        }
        return days;
    }
}
