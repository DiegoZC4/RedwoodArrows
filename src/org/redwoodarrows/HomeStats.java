package org.redwoodarrows;

import java.util.*;

/** Read-only home summary; missing measurements are never plotted as zero. */
final class HomeStats {
    static final class Session {
        final long day;final List<Store.Group> groups=new ArrayList<>();
        int photos,marked,located;
        Session(long day){this.day=day;}
        void add(Store.Group group){
            groups.add(group);photos+=PhotoAssignments.photoIndices(group).size();
            for(Store.Result r:Store.results(group)){marked++;if(Double.isFinite(r.x)&&Double.isFinite(r.y))located++;}
        }
        String photoLabel(){return photos+" photo"+(photos==1?"":"s");}
        String measurementLabel(){
            if(marked==0)return "No entry points yet";
            if(located==0)return "Measurements pending";
            return located+" of "+marked+" entries measured";
        }
    }
    final List<Session> sessions=new ArrayList<>();
    final ConsistencyStats progress;
    final List<Long> chartDays=new ArrayList<>();
    final String setup;final double distance;
    HomeStats(List<Store.Group> input,List<ShooterRoster.Person> people,String setup,double distance){
        this.setup=setup;this.distance=distance;
        Map<String,Store.Group> unique=new LinkedHashMap<>();
        for(Store.Group group:input)if(group.hasData()&&!unique.containsKey(group.id))unique.put(group.id,group);
        List<Store.Group> groups=new ArrayList<>(unique.values());
        groups.sort((a,b)->{int n=Long.compare(b.created,a.created);return n==0?a.id.compareTo(b.id):n;});
        Map<Long,Session> days=new LinkedHashMap<>();
        for(Store.Group group:groups){long day=ConsistencyStats.day(group.created);Session session=days.get(day);if(session==null){session=new Session(day);days.put(day,session);}session.add(group);}
        sessions.addAll(days.values());
        progress=new ConsistencyStats(groups,people,setup,false,distance,ConsistencyStats.DAILY);
        SortedSet<Long> chart=new TreeSet<>(progress.times);
        // Keep unknown-distance or uncalibrated dates visible in their context.
        for(Store.Group group:groups)if(group.practice.setup.equals(setup)){
            double range=ConsistencyStats.uniformDistance(group);
            if(!Practice.distance(distance)||!Practice.distance(range)||Math.abs(range-distance)<.001)chart.add(ConsistencyStats.day(group.created));
        }
        chartDays.addAll(chart);while(chartDays.size()>5)chartDays.remove(0);
    }
    static String latestSetup(List<Store.Group> groups){
        Store.Group latest=null;for(Store.Group group:groups)if(group.hasData()&&(latest==null||group.created>latest.created))latest=group;
        return latest==null?"":latest.practice.setup;
    }
    static ConsistencyStats.Sample latestMeasured(ConsistencyStats.Series series){
        for(int i=series.samples.size()-1;i>=0;i--)if(Double.isFinite(series.samples.get(i).spread))return series.samples.get(i);
        return null;
    }
    boolean measured(long day){for(ConsistencyStats.Series series:progress.series){ConsistencyStats.Sample s=progress.at(series,day);if(s!=null&&Double.isFinite(s.spread))return true;}return false;}
    boolean partiallyMeasured(long day){for(ConsistencyStats.Series series:progress.series){ConsistencyStats.Sample sample=progress.at(series,day);if(sample!=null&&Double.isFinite(sample.spread)&&sample.unlocated>0)return true;}return false;}
    SortedSet<Long> ranges(long day){
        SortedSet<Long> ranges=new TreeSet<>();for(ConsistencyStats.Series series:progress.series){ConsistencyStats.Sample sample=progress.at(series,day);if(sample!=null)ranges.addAll(sample.ranges);}return ranges;
    }
    String rangeLabel(long day){
        SortedSet<Long> ranges=ranges(day);if(ranges.isEmpty())return "";if(ranges.size()>2)return "Mixed ranges";
        List<String> values=new ArrayList<>();for(long range:ranges)values.add(TargetPreset.number(Practice.fromMeters(range/1000.,"yd")));return String.join("/",values)+" yd";
    }
    Session latest(){return sessions.isEmpty()?null:sessions.get(0);}
}
