package org.redwoodarrows;

import java.util.*;

/** Within-round angular repeatability. Never SE, bullseye error, or a score.
 * All-range samples give each represented fixed distance equal variance weight.
 * No annotations, review flags, distances or shooter identities are written. */
final class ConsistencyStats {
    static final int DAILY=0,ROLLING_30=30,ROLLING_100=100;
    static final int BOOTSTRAP_REPLICATES=4096;
    static final class Interval {
        static final int PENDING=0,READY=1,NO_SPREAD=2,DEGENERATE=3,SINGLE_ROUND=4;
        final int state;final double lower,upper;
        Interval(int state,double lower,double upper){this.state=state;this.lower=lower;this.upper=upper;}
        static Interval unavailable(int state){return new Interval(state,Double.NaN,Double.NaN);}
        boolean available(){return state==READY;}
    }
    static final class Round {final double squared;final int df;Round(double squared,int df){this.squared=squared;this.df=df;}}
    static final class Bucket {double squared;int df;final List<Round> rounds=new ArrayList<>();void add(ProgressStats.End end){
        int n=end.points.size();if(n<2)return;double x=0,y=0;
        for(double[] p:end.points){x+=p[0];y+=p[1];}x/=n;y/=n;
        double sum=0;for(double[] p:end.points){double dx=p[0]-x,dy=p[1]-y;sum+=dx*dx+dy*dy;}
        squared+=sum;df+=n-1;rounds.add(new Round(sum,n-1));
    }}
    static final class Sample {
        final long time;final List<ProgressStats.End> ends;final SortedSet<Long> ranges=new TreeSet<>();
        final TreeMap<Long,Bucket> buckets=new TreeMap<>();
        int located,records,unlocated,misses,unchecked,usableRounds;boolean countsKnown=true;
        double spread=Double.NaN;String rangeKey="";
        volatile Interval interval=Interval.unavailable(Interval.NO_SPREAD);
        Sample(long time,List<ProgressStats.End> ends){this.time=time;this.ends=new ArrayList<>(ends);
            // Stable cluster ordering makes bounds reproducible on both phones.
            this.ends.sort((a,b)->{int order=Long.compare(a.group.created,b.group.created);return order==0?a.group.id.compareTo(b.group.id):order;});
            for(ProgressStats.End end:this.ends){
                located+=end.points.size();records+=end.records;unlocated+=end.unlocated;misses+=end.misses;
                countsKnown&=end.countsKnown;if(!end.checked)unchecked++;if(end.points.size()>1)usableRounds++;
                long range=rangeKey(uniformDistance(end.group));ranges.add(range);
                Bucket bucket=buckets.get(range);if(bucket==null){bucket=new Bucket();buckets.put(range,bucket);}bucket.add(end);
            }
            rangeKey=ranges.toString();double variances=0;boolean singletonDistance=false;
            for(Bucket bucket:buckets.values()){if(bucket.df==0)return;variances+=bucket.squared/bucket.df;singletonDistance|=bucket.rounds.size()==1;}
            if(!buckets.isEmpty())spread=Math.sqrt(variances/buckets.size());
            // No product minimum. A singleton distance has no empirical
            // between-round distribution to resample; treating it as fixed
            // would falsely omit that distance's uncertainty in All ranges.
            if(Double.isFinite(spread))interval=Interval.unavailable(singletonDistance?Interval.SINGLE_ROUND:Interval.PENDING);
        }
        void computeInterval(){
            if(interval.state!=Interval.PENDING)return;
            // Resample entire usable rounds WITHIN distance strata, never arrows
            // or photographs. Preserve the point estimate's equal-distance weights.
            Random random=new Random(0x524544574f4f44L);double[] estimates=new double[BOOTSTRAP_REPLICATES];
            for(int replicate=0;replicate<estimates.length;replicate++){
                if((replicate&63)==0&&Thread.currentThread().isInterrupted())return;
                double variance=0;
                for(Bucket bucket:buckets.values()){
                    double squared=0;int df=0,n=bucket.rounds.size();
                    for(int i=0;i<n;i++){Round round=bucket.rounds.get(random.nextInt(n));squared+=round.squared;df+=round.df;}
                    variance+=squared/df;
                }
                estimates[replicate]=Math.sqrt(variance/buckets.size());
            }
            Arrays.sort(estimates);double lower=quantile(estimates,.025),upper=quantile(estimates,.975);
            // Identical sampled round variances cannot establish zero uncertainty.
            interval=upper-lower<=Math.max(1e-12,Math.abs(spread)*1e-12)
                ?Interval.unavailable(Interval.DEGENERATE):new Interval(Interval.READY,lower,upper);
        }
    }
    static final class Series {
        final ShooterRoster.Person person;final List<Sample> samples=new ArrayList<>();
        final List<Sample> trend=new ArrayList<>();
        int noDistance,unconfirmed,unchecked,mixedDistance,unsupported,wrongDistance;
        Series(ShooterRoster.Person person){this.person=person;}
    }
    final List<Series> series=new ArrayList<>();final SortedSet<Long> times=new TreeSet<>();
    final SortedSet<Long> distanceChoices=new TreeSet<>();final SortedSet<String> setups=new TreeSet<>();
    final int mode;final double distance;int missingDistance,mixedDistance,unchecked,unconfirmed,unsupported;
    ConsistencyStats(List<Store.Group> groups,List<ShooterRoster.Person> people,String setup,boolean checkedOnly,double distance,int mode){
        if(mode!=DAILY&&mode!=ROLLING_30&&mode!=ROLLING_100)throw new IllegalArgumentException("Unknown comparison window");
        this.mode=mode;this.distance=distance;
        Set<String> owners=new HashSet<>();for(ShooterRoster.Person p:people)if(p.assigned())owners.add(p.id);
        for(Store.Group g:groups)if(g.hasData()&&owners.contains(g.shooter.id)){
            setups.add(g.practice.setup);
            if(g.practice.setup.equals(setup)&&g.preset().circular()){double d=uniformDistance(g);if(Practice.distance(d))distanceChoices.add(rangeKey(d));}
        }
        Set<String> seenPeople=new HashSet<>();
        for(ShooterRoster.Person person:people){
            if(!person.assigned()||!seenPeople.add(person.id))continue;
            Series line=new Series(person);series.add(line);
            ProgressStats source=new ProgressStats(groups,person.id,setup,true,checkedOnly,distance);
            line.noDistance=source.noDistance;line.unconfirmed=source.unconfirmedOwner;line.unchecked=source.notChecked;
            line.unsupported=source.unsupportedTarget;line.wrongDistance=source.wrongDistance;
            List<ProgressStats.End> ordered=new ArrayList<>();
            for(ProgressStats.End end:source.ends){if(Practice.distance(uniformDistance(end.group)))ordered.add(end);else line.mixedDistance++;}
            ordered.sort((a,b)->{int order=Long.compare(a.group.created,b.group.created);return order==0?a.group.id.compareTo(b.group.id):order;});
            if(mode==DAILY){
                TreeMap<Long,List<ProgressStats.End>> days=new TreeMap<>();
                for(ProgressStats.End end:ordered){long day=day(end.group.created);List<ProgressStats.End> list=days.get(day);if(list==null){list=new ArrayList<>();days.put(day,list);}list.add(end);}
                int last=-1;
                for(Map.Entry<Long,List<ProgressStats.End>> e:days.entrySet()){
                    line.samples.add(new Sample(e.getKey(),e.getValue()));last+=e.getValue().size();
                    int first=last,records=ordered.get(last).records;while(first>0&&records<ROLLING_100)records+=ordered.get(--first).records;
                    // One rolling value at each shooting day's end; never use a
                    // later round, and never split a round to reach exactly 100.
                    line.trend.add(new Sample(e.getKey(),ordered.subList(first,last+1)));
                }
            }else{
                for(int i=0;i<ordered.size();i++){
                    int first=i,records=ordered.get(i).records;while(first>0&&records<mode)records+=ordered.get(--first).records;
                    Sample sample=new Sample(ordered.get(i).group.created,ordered.subList(first,i+1));
                    // Several ends can share a timestamp. Retain the complete
                    // latest window at that time instead of a vertical zigzag.
                    if(!line.samples.isEmpty()&&line.samples.get(line.samples.size()-1).time==sample.time)line.samples.remove(line.samples.size()-1);
                    line.samples.add(sample);
                }
            }
            for(Sample sample:line.samples)times.add(sample.time);
            missingDistance+=line.noDistance;mixedDistance+=line.mixedDistance;unchecked+=line.unchecked;
            unconfirmed+=line.unconfirmed;unsupported+=line.unsupported;
        }
    }
    void computeIntervals(){for(Series line:series)for(Sample sample:line.samples){if(Thread.currentThread().isInterrupted())return;sample.computeInterval();}}
    static double quantile(double[] sorted,double probability){double at=(sorted.length-1)*probability;int lo=(int)Math.floor(at),hi=(int)Math.ceil(at);return sorted[lo]+(sorted[hi]-sorted[lo])*(at-lo);}
    static boolean trendReady(Sample sample){return sample!=null&&sample.records>=ROLLING_100&&Double.isFinite(sample.spread);}
    Sample trendAt(Series line,long time){for(Sample sample:line.trend)if(sample.time==time)return sample;return null;}
    static long rangeKey(double meters){return Math.round(meters*1000);}
    static long day(long time){Calendar calendar=Calendar.getInstance();calendar.setTimeInMillis(time);calendar.set(Calendar.HOUR_OF_DAY,0);calendar.set(Calendar.MINUTE,0);calendar.set(Calendar.SECOND,0);calendar.set(Calendar.MILLISECOND,0);return calendar.getTimeInMillis();}
    static double uniformDistance(Store.Group group){
        List<Store.Result> results=Store.results(group);double meters=results.isEmpty()?group.practice.meters:group.practice.distanceFor(results.get(0).id);
        return Practice.distance(meters)&&ProgressStats.atDistance(group,results,meters)?meters:Double.NaN;
    }
    Sample at(Series line,long time){Sample found=null;for(Sample sample:line.samples){if(sample.time>time)break;if(mode==DAILY&&sample.time!=time)continue;found=sample;}return found;}
    long latest(){return times.isEmpty()?0:times.last();}
    long nearest(long time){long nearest=0;double delta=Double.POSITIVE_INFINITY;for(long t:times){double d=Math.abs((double)t-time);if(d<delta){nearest=t;delta=d;}}return nearest;}
}
