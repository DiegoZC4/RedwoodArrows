package org.redwoodarrows;

import java.util.*;

/** Bounded invented fixtures only. No personal observations in public tests. */
public final class ConsistencyStatsTest {
    static int checks;static long next=1;static final ShooterRoster.Person A=new ShooterRoster.Person("a","Alex"),B=new ShooterRoster.Person("b","Sam");
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void near(double a,double b,String why){check(Math.abs(a-b)<1e-8,why+": "+a+" != "+b);}
    static Store.Group end(ShooterRoster.Person p,int day,double meters,double radius,int n){
        Store.Group g=Store.Group.start(TargetCatalog.defaultPreset(),p);g.id="comparison-"+(next++);g.created=1700000000000L+day*86400000L+next*1000;g.keepPhotos=false;g.sourceViewCount=3;g.savedResults=new ArrayList<>();g.practice.meters=meters;
        for(int i=0;i<n;i++){Store.Result r=new Store.Result();r.id=i+1;r.x=meters*radius*Math.cos(i*2*Math.PI/n);r.y=meters*radius*Math.sin(i*2*Math.PI/n);r.views=3;r.method="invented";g.savedResults.add(r);}return g;
    }
    static ConsistencyStats stats(List<Store.Group> groups,double distance,int mode){return new ConsistencyStats(groups,Arrays.asList(A,B),"",false,distance,mode);}
    static ConsistencyStats.Sample first(ConsistencyStats s,int who){return s.series.get(who).samples.get(0);}
    public static void main(String[] args)throws Exception{
        Store.Group a=end(A,0,9.144,3,4),b=end(B,0,13.716,3,4);
        ConsistencyStats s=stats(Arrays.asList(a,b),Double.NaN,0);
        near(first(s,0).spread,Math.sqrt(12),"Sample radial SD, not standard error");near(first(s,0).spread,first(s,1).spread,"Angular distance invariance");
        check(first(s,0).located==4&&first(s,0).records==4,"Multiple views counted once");check(s.series.size()==2&&s.times.size()==1,"Shared daily axis, separate people");
        Store.Group shifted=end(A,0,9.144,3,4);for(Store.Result r:shifted.savedResults){r.x+=900;r.y-=400;}
        near(first(stats(Arrays.asList(a,shifted),Double.NaN,0),0).spread,Math.sqrt(12),"Per-round centers remove aim drift");
        Store.Group different=end(A,0,13.716,6,4);List<Store.Group> unequal=new ArrayList<>();unequal.add(a);unequal.add(different);for(int j=0;j<10;j++)unequal.add(end(A,0,13.716,6,4));
        near(first(stats(unequal,Double.NaN,0),0).spread,Math.sqrt(30),"Equal distance variance weight, not arrow volume");
        near(first(stats(unequal,9.144,0),0).spread,Math.sqrt(12),"10 yard distance filter");near(first(stats(unequal,13.716,0),0).spread,Math.sqrt(48),"15 yard distance filter");
        s=stats(Arrays.asList(a,a),Double.NaN,0);check(first(s,0).located==4,"Repeated grouping ID deduplicated");
        Store.Group missing=end(A,1,9.144,3,4);missing.practice.meters=Double.NaN;
        s=stats(Arrays.asList(a,missing),Double.NaN,0);check(s.missingDistance==1&&s.times.size()==2&&s.at(s.series.get(0),s.latest())==null,"Unknown-distance day visible, never a zero/tight measurement");
        Store.Group mixed=end(A,0,9.144,3,4);mixed.practice.arrowMeters.put(1,13.716);s=stats(Arrays.asList(a,mixed),Double.NaN,0);check(s.mixedDistance==1&&first(s,0).ends.size()==1,"Walk-ups do not pollute equal-distance comparison");
        Store.Group overrides=end(A,0,9.144,3,4);for(int i=1;i<=4;i++)overrides.practice.arrowMeters.put(i,9.144);overrides.practice.meters=Double.NaN;
        near(first(stats(Arrays.asList(overrides),9.144,0),0).spread,Math.sqrt(12),"Known uniform arrow overrides supported");
        Store.Group solo=end(A,0,9.144,3,1);check(Double.isNaN(first(stats(Arrays.asList(solo),Double.NaN,0),0).spread),"One arrow is not zero spread");
        Store.Group newRange=end(A,0,13.716,3,1);check(Double.isNaN(first(stats(Arrays.asList(a,newRange),Double.NaN,0),0).spread),"Unestimable distance not silently excluded from balanced number");
        Store.Group unconfirmed=end(B,0,9.144,3,4);unconfirmed.savedResults.get(0).warning="Shooter provisional: inferred";
        s=stats(Arrays.asList(a,unconfirmed),Double.NaN,0);check(s.unconfirmed==1&&s.series.get(1).samples.isEmpty(),"Unconfirmed owner excluded");
        Store.Group other=end(A,0,9.144,30,4);other.practice.setup="Different bow";near(first(stats(Arrays.asList(a,other),Double.NaN,0),0).spread,Math.sqrt(12),"Equipment setups not mixed");
        s=new ConsistencyStats(Arrays.asList(a,b),Arrays.asList(A,B),"",true,Double.NaN,0);check(s.unchecked==2&&s.times.isEmpty(),"Checked-only filter");
        a.practice.fired=4;a.practice.misses=0;a.practice.markChecked(a);s=new ConsistencyStats(Arrays.asList(a,b),Arrays.asList(A,B),"",true,Double.NaN,0);check(first(s,0).unchecked==0&&s.series.get(1).samples.isEmpty(),"Review eligibility follows saved marks");
        Store.Group day2=end(B,2,13.716,4,4);s=stats(Arrays.asList(a,day2),Double.NaN,0);check(s.at(s.series.get(1),s.times.first())==null&&s.at(s.series.get(0),s.times.last())==null,"Missing daily points are not carried forward");
        List<Store.Group> many=new ArrayList<>();for(int j=0;j<22;j++)many.add(end(j%2==0?A:B,j/8,9.144,3,6));
        s=stats(many,Double.NaN,30);ConsistencyStats.Series line=s.series.get(0);check(line.samples.get(line.samples.size()-1).records==30,"Rolling 30 preserves whole ends");
        check(s.at(s.series.get(1),s.times.first())==null,"Rolling never leaks future shooter result");check(s.at(line,s.latest())!=null,"Latest rolling point available");
        near(line.samples.get(0).spread,line.samples.get(line.samples.size()-1).spread,"More arrows do not artificially shrink dispersion");
        s=stats(many,Double.NaN,100);check(first(s,0).records==6&&s.series.get(0).samples.get(10).records==66,"Partial rolling windows honest");
        Store.Group allMiss=end(A,3,9.144,3,0);allMiss.practice.fired=6;allMiss.practice.misses=6;s=stats(Arrays.asList(allMiss),Double.NaN,0);check(first(s,0).misses==6&&Double.isNaN(first(s,0).spread),"All misses have no invented positions");
        Store.Group unresolved=Store.Group.start(TargetCatalog.defaultPreset(),A);unresolved.id="unresolved";unresolved.created=a.created;unresolved.practice.meters=9.144;Store.View view=new Store.View();view.id="one";view.observations.add(new Store.Observation(1,new double[]{1,1},null));unresolved.views.add(view);s=stats(Arrays.asList(a,unresolved),Double.NaN,0);check(first(s,0).unlocated==1&&first(s,0).records==5,"Unresolved positions retained in coverage");near(first(s,0).spread,Math.sqrt(12),"Unresolved coordinate not placed at zero");
        check(s.nearest(Long.MAX_VALUE)==s.latest(),"Selection snaps to real data");
        System.out.println("Angular consistency checks passed: "+checks);
    }
}
