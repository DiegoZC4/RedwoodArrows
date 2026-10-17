package org.redwoodarrows;

import java.util.*;

/** Synthetic cluster-bootstrap fixtures; no photographs or personal data. */
public final class ConfidenceStatsTest {
    static int checks;
    static final ShooterRoster.Person PERSON=new ShooterRoster.Person("test","Test shooter");
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static void near(double actual,double expected,String why){check(Math.abs(actual-expected)<1e-9,why+": "+actual+" != "+expected);}
    static Store.Group round(int day,int index,double distance,double radius,int arrows){
        Store.Group g=ConsistencyStatsTest.end(PERSON,day,distance,radius,arrows);
        g.id="ci-"+day+"-"+index;g.created=1700000000000L+day*86400000L+index*1000L;
        g.practice.fired=arrows;g.practice.misses=0;return g;
    }
    static List<Store.Group> sample(int count){List<Store.Group> out=new ArrayList<>();for(int i=0;i<count;i++)out.add(round(0,i,9.144,i%2==0?2:8,6));return out;}
    static ConsistencyStats stats(List<Store.Group> groups){return new ConsistencyStats(groups,Collections.singletonList(PERSON),"",false,Double.NaN,0);}
    static ConsistencyStats.Sample first(ConsistencyStats stats){return stats.series.get(0).samples.get(0);}
    static ConsistencyStats.Sample calculated(List<Store.Group> groups){ConsistencyStats s=stats(groups);s.computeIntervals();return first(s);}
    public static void main(String[] args)throws Exception {
        ConsistencyStats.Sample single=calculated(sample(1));
        check(single.interval.state==ConsistencyStats.Interval.SINGLE_ROUND,"One cluster cannot estimate between-round uncertainty");
        check(Double.isFinite(single.spread)&&Double.isNaN(single.interval.lower),"Single-round point retained without invented interval");
        for(int n=2;n<10;n++){
            ConsistencyStats.Sample small=calculated(sample(n));
            check(small.interval.available(),"No minimum cutoff for "+n+" rounds");
            check(small.interval.lower<=small.spread&&small.interval.upper>=small.spread,"Small-sample bounds include the fixture estimate");
        }
        ConsistencyStats.Sample two=calculated(sample(2)),shortDay=calculated(sample(9));
        near(two.interval.lower,Math.sqrt(1.2*4),"Two-round lower percentile is an actual whole-round resample");
        near(two.interval.upper,Math.sqrt(1.2*64),"Two-round upper percentile is an actual whole-round resample");
        List<Store.Group> groups=sample(10);ConsistencyStats s=stats(groups);ConsistencyStats.Sample day=first(s);
        check(day.interval.state==ConsistencyStats.Interval.PENDING,"Point estimates available before bootstrap work");
        double spread=day.spread;s.computeIntervals();check(day.interval.available(),"Ten usable rounds yield an interval");near(day.spread,spread,"Bootstrap never changes point estimate");
        // With five tight and five loose equal-sized rounds, resampling whole
        // rounds gives K ~ Binomial(10, .5) loose rounds. Exact percentile K=2,8.
        // Independent arrow resampling does NOT produce these bounds.
        near(day.interval.lower,Math.sqrt(1.2*(8*4+2*64)/10.),"Whole-round lower percentile, analytic binomial reference");
        near(day.interval.upper,Math.sqrt(1.2*(2*4+8*64)/10.),"Whole-round upper percentile, analytic binomial reference");
        check(day.interval.lower>0&&day.interval.lower<day.spread&&day.interval.upper>day.spread,"Nonnegative ordered interval");
        ConsistencyStats.Sample repeat=calculated(groups);near(day.interval.lower,repeat.interval.lower,"Reproducible lower bound");near(day.interval.upper,repeat.interval.upper,"Reproducible upper bound");
        Collections.reverse(groups);repeat=calculated(groups);near(day.interval.lower,repeat.interval.lower,"Input order does not change seeded bounds");
        groups.addAll(new ArrayList<>(groups));repeat=calculated(groups);check(repeat.usableRounds==10&&repeat.located==60,"Duplicate grouping IDs cannot inflate cluster count");near(day.interval.upper,repeat.interval.upper,"Duplicates cannot tighten interval");
        List<Store.Group> copies=new ArrayList<>();for(int i=0;i<12;i++)copies.add(groups.get(0));check(calculated(copies).interval.state==ConsistencyStats.Interval.SINGLE_ROUND,"Many views of one round cannot invent between-round variation");
        List<Store.Group> more=sample(40);ConsistencyStats.Sample longer=calculated(more);near(longer.spread,day.spread,"More observations do not improve tightness by themselves");check(longer.interval.upper-longer.interval.lower<day.interval.upper-day.interval.lower,"Same distribution, more rounds narrows uncertainty");
        List<Store.Group> scaled=sample(10);for(Store.Group g:scaled)for(Store.Result r:g.savedResults){r.x=2*r.x+100;r.y=2*r.y-200;}
        repeat=calculated(scaled);near(repeat.spread,2*day.spread,"Translation removed per round and spread scales");near(repeat.interval.lower,2*day.interval.lower,"CI lower scales with metric");near(repeat.interval.upper,2*day.interval.upper,"CI upper scales with metric");
        List<Store.Group> identical=new ArrayList<>();for(int i=0;i<10;i++)identical.add(round(0,i,9.144,3,6));
        check(calculated(identical).interval.state==ConsistencyStats.Interval.DEGENERATE,"Identical round estimates do not assert zero uncertainty");
        check(calculated(new ArrayList<>(identical.subList(0,2))).interval.state==ConsistencyStats.Interval.DEGENERATE,"Two identical rounds do not assert zero uncertainty either");
        List<Store.Group> singletonStratum=sample(2);singletonStratum.add(round(0,80,13.716,3,6));
        repeat=calculated(singletonStratum);check(repeat.interval.state==ConsistencyStats.Interval.SINGLE_ROUND&&Double.isFinite(repeat.spread),"All ranges never silently treats singleton-stratum uncertainty as zero");
        ConsistencyStats repeatedDistance=new ConsistencyStats(singletonStratum,Collections.singletonList(PERSON),"",false,9.144,0);
        repeatedDistance.computeIntervals();check(first(repeatedDistance).interval.available(),"Filtering the repeated distance restores its small-sample interval");
        singletonStratum.add(round(0,81,13.716,8,6));
        check(calculated(singletonStratum).interval.available(),"Two rounds at each distance are resampled without a ten-round gate");
        List<Store.Group> stratified=sample(10);for(int i=0;i<9;i++)stratified.add(round(0,20+i,13.716,3+i,6));
        repeat=calculated(stratified);check(repeat.usableRounds==19&&repeat.interval.available(),"Nine rounds at a distance no longer suppress an interval");
        stratified.add(round(0,29,13.716,12,6));repeat=calculated(stratified);check(repeat.interval.available(),"Stratified interval available when all ranges have enough rounds");
        double equalVariance=repeat.spread*repeat.spread;List<Store.Group> highRange=new ArrayList<>(stratified.subList(10,20));
        for(int i=0;i<10;i++){Store.Group copy=round(0,40+i,13.716,3+i,6);stratified.add(copy);}
        ConsistencyStats.Sample unbalanced=calculated(stratified);near(unbalanced.spread*unbalanced.spread,equalVariance,"Extra rounds at one distance preserve equal-distance weighting");
        ConsistencyStats.Sample low=calculated(sample(10)),high=calculated(highRange);
        near(repeat.spread,Math.sqrt((low.spread*low.spread+high.spread*high.spread)/2),"Combined estimate matches independently pooled strata");
        List<Store.Group> fixedStratum=sample(10);for(int i=0;i<30;i++)fixedStratum.add(round(0,50+i,13.716,20,6));
        ConsistencyStats.Sample balancedBounds=calculated(fixedStratum);
        near(balancedBounds.interval.lower,Math.sqrt((day.interval.lower*day.interval.lower+480)/2),"Each bootstrap replicate gives distances equal weight despite 3x round count");
        near(balancedBounds.interval.upper,Math.sqrt((day.interval.upper*day.interval.upper+480)/2),"Stratified upper percentile matches analytic reference");
        List<Store.Group> unusable=sample(9);unusable.add(round(0,90,9.144,3,1));Store.Group misses=round(0,91,9.144,0,0);misses.practice.fired=6;misses.practice.misses=6;unusable.add(misses);
        repeat=calculated(unusable);check(repeat.usableRounds==9&&repeat.interval.available(),"One-arrow and all-miss rounds do not inflate usable round count");near(repeat.interval.lower,shortDay.interval.lower,"Unusable rounds do not change resampled lower bound");near(repeat.interval.upper,shortDay.interval.upper,"Unusable rounds do not change resampled upper bound");check(repeat.misses==6,"Misses retained in coverage");
        check(calculated(Collections.singletonList(round(0,92,9.144,3,1))).interval.state==ConsistencyStats.Interval.NO_SPREAD,"One located arrow cannot establish round spread");
        List<Store.Group> variable=new ArrayList<>();double squared=0,df=0;for(int i=0;i<10;i++){int n=i%2==0?3:9;double radius=2+i;variable.add(round(0,i,9.144,radius,n));squared+=n*radius*radius;df+=n-1;}
        repeat=calculated(variable);near(repeat.spread,Math.sqrt(squared/df),"Unequal round sizes use pooled degrees of freedom, not average SDs");check(repeat.interval.available(),"Unequal-size clusters resample without splitting");
        ConsistencyStats canceled=stats(sample(10));Thread.currentThread().interrupt();canceled.computeIntervals();check(Thread.interrupted(),"Cancellation honored without clearing interrupt");check(first(canceled).interval.state==ConsistencyStats.Interval.PENDING,"Canceled calculation never publishes partial bounds");canceled.computeIntervals();check(first(canceled).interval.available(),"Canceled sample can be retried");
        List<Store.Group> history=new ArrayList<>();for(int d=0;d<4;d++)for(int i=0;i<10;i++)history.add(round(d,i,9.144,2+d+i%3,6));
        ConsistencyStats timeline=stats(history);ConsistencyStats.Series line=timeline.series.get(0);check(line.samples.size()==4&&line.trend.size()==4,"Daily dots preserved alongside one day-end trend sample");
        check(!ConsistencyStats.trendReady(line.trend.get(0)),"Partial 100-arrow window is not plotted");
        for(int i=1;i<4;i++){ConsistencyStats.Sample trend=line.trend.get(i);check(ConsistencyStats.trendReady(trend)&&trend.records==102,"Trend retains whole rounds beyond 100 arrows");for(ProgressStats.End end:trend.ends)check(ConsistencyStats.day(end.group.created)<=trend.time,"Trend has no future data");}
        List<Store.Group> beforeFuture=new ArrayList<>(history);beforeFuture.add(round(9,0,9.144,100,6));ConsistencyStats extended=stats(beforeFuture);near(extended.series.get(0).trend.get(1).spread,line.trend.get(1).spread,"Future observations cannot rewrite past windows");
        check(timeline.trendAt(line,timeline.latest())==line.trend.get(3),"Trend readout aligns with selected calendar day");
        List<Store.Group> fewLongRounds=new ArrayList<>();for(int i=0;i<5;i++)fewLongRounds.add(round(0,i,9.144,2+i,20));
        check(ConsistencyStats.trendReady(stats(fewLongRounds).series.get(0).trend.get(0)),"A complete 100-arrow trend needs no minimum round count");
        check(ConsistencyStats.trendReady(stats(Collections.singletonList(round(0,0,9.144,2,100))).series.get(0).trend.get(0)),"Descriptive trend can show one 100-arrow round without claiming a CI");
        System.out.println("Whole-round confidence checks passed: "+checks);
    }
}
