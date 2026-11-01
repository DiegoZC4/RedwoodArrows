package org.redwoodarrows;

import java.util.*;

public final class HomeStatsTest {
    static int checks,ids;static final ShooterRoster.Person A=new ShooterRoster.Person("a","Alex"),B=new ShooterRoster.Person("b","Sam");
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static Store.Group measured(int day,ShooterRoster.Person person,double meters){
        Store.Group group=Store.Group.start(TargetCatalog.defaultPreset(),person);group.id="home-"+(++ids);group.created=1700000000000L+day*86400000L;group.keepPhotos=false;group.practice.meters=meters;group.savedResults=new ArrayList<>();
        for(int i=0;i<3;i++){Store.Result r=new Store.Result();r.id=i+1;r.x=i*meters;r.y=i*meters;r.views=1;r.method="invented";group.savedResults.add(r);}return group;
    }
    static Store.Group pending(int day){
        Store.Group group=Store.Group.start(TargetCatalog.find("mlp-nfh-35"),A);group.id="home-"+(++ids);group.created=1700000000000L+day*86400000L;group.practice.meters=13.716;
        for(int i=0;i<2;i++){Store.View view=new Store.View();view.id="view-"+i;view.file=view.id+".jpg";view.width=400;view.height=300;view.session="photo-assign:one-photo";view.observations.add(new Store.Observation(i+1,new double[]{100+i*5,120},null));group.views.add(view);}return group;
    }
    static HomeStats stats(List<Store.Group> groups,double distance){return new HomeStats(groups,Arrays.asList(A,B),"",distance);}
    public static void main(String[] args)throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
        Store.Group first=measured(0,A,13.716),second=measured(0,B,13.716),newest=pending(2);
        List<Store.Group> groups=Arrays.asList(newest,second,first,first);String before=newest.json().toString();HomeStats data=stats(groups,Double.NaN);
        check(data.sessions.size()==2,"duplicate group IDs do not create sessions");
        check(data.latest().day==ConsistencyStats.day(newest.created),"sessions newest first");
        check(data.sessions.get(1).groups.size()==2,"duplicate groups excluded from counts");
        check(data.latest().photos==1,"target-assignment image aliases count as one photo");
        check(data.latest().photoLabel().equals("1 photo"),"singular photo label");
        check(data.latest().marked==2,"saved entries, not a guessed six-arrow count");
        check(data.latest().located==0,"unconfirmed calibration remains unmeasured");
        check(data.latest().measurementLabel().equals("Measurements pending"),"missing measurements explicit");
        check(data.chartDays.size()==2,"unmeasured latest date kept on the chart");
        check(!data.measured(data.latest().day),"missing measurements not zero spread");
        check(HomeStats.latestMeasured(data.progress.series.get(0)).time==ConsistencyStats.day(first.created),"older last measured date explicit");
        check(HomeStats.latestMeasured(data.progress.series.get(1)).time==ConsistencyStats.day(second.created),"people kept separate");
        check(newest.json().toString().equals(before),"summary never edits reviews or metadata");
        newest.practice.meters=Double.NaN;data=stats(groups,13.716);check(data.chartDays.contains(ConsistencyStats.day(newest.created)),"unknown distances stay visible rather than disappearing");
        newest.practice.meters=9.144;data=stats(groups,13.716);check(!data.chartDays.contains(ConsistencyStats.day(newest.created)),"known other range excluded from chart");
        check(data.latest().day==ConsistencyStats.day(newest.created),"recent photo sessions remain accessible across filters");
        newest.practice.setup="Other bow";data=stats(groups,Double.NaN);check(!data.chartDays.contains(ConsistencyStats.day(newest.created)),"chart does not mix equipment");
        check(HomeStats.latestSetup(Arrays.asList(first,newest)).equals("Other bow"),"latest equipment chosen independent of input order");
        check(HomeStats.latestSetup(Collections.emptyList()).isEmpty(),"empty library setup");
        data=stats(Collections.emptyList(),Double.NaN);check(data.sessions.isEmpty()&&data.chartDays.isEmpty()&&data.latest()==null,"empty home supported");
        check(HomeStats.latestMeasured(data.progress.series.get(0))==null,"no fabricated empty measurements");
        List<Store.Group> many=new ArrayList<>();for(int i=0;i<8;i++)many.add(measured(i,A,13.716));data=stats(many,Double.NaN);
        check(data.chartDays.size()==5,"home chart stays compact");check(data.chartDays.get(0)==ConsistencyStats.day(many.get(3).created),"most recent five dates selected");
        check(data.sessions.size()==8,"older sessions not removed");
        List<Store.Group> dates=Arrays.asList(measured(0,A,9.144),measured(0,A,13.716),measured(0,B,9.144),measured(0,B,13.716),measured(1,A,13.716),measured(1,B,13.716),measured(7,A,13.716),measured(9,A,13.716),measured(9,B,13.716));
        data=stats(dates,Double.NaN);ConsistencyStats.Series alex=data.progress.series.get(0),sam=data.progress.series.get(1);
        check(data.rangeLabel(data.chartDays.get(0)).equals("10/15 yd"),"mixed-distance day is labeled explicitly");
        check(data.rangeLabel(data.chartDays.get(1)).equals("15 yd"),"single-distance day label");
        check(ConsistencyStats.dashedLink(alex.samples.get(0),alex.samples.get(1),data.chartDays),"distance mix change uses a dashed connector, not a disappearing line");
        check(!ConsistencyStats.dashedLink(alex.samples.get(1),alex.samples.get(2),data.chartDays),"same-range neighboring recorded dates connect normally");
        check(!ConsistencyStats.dashedLink(alex.samples.get(2),alex.samples.get(3),data.chartDays),"last neighboring sessions connect normally");
        check(ConsistencyStats.dashedLink(sam.samples.get(1),sam.samples.get(2),data.chartDays),"other shooter's missing date is bridged explicitly");
        check(data.progress.at(sam,data.chartDays.get(2))==null&&sam.samples.size()==3,"gap connector never invents a missing daily point");
        check(data.progress.at(alex,data.chartDays.get(2))!=null,"one shooter's missing day does not remove the other's measurement");
        HomeStats filtered=stats(dates,13.716);
        check(filtered.rangeLabel(filtered.chartDays.get(0)).equals("15 yd"),"distance filter also updates the range label");
        check(!ConsistencyStats.dashedLink(filtered.progress.series.get(0).samples.get(0),filtered.progress.series.get(0).samples.get(1),filtered.chartDays),"fixed-range comparison has no false distance-change dash");
        check(data.rangeLabel(0).isEmpty(),"unknown date never fabricates a range");
        check(!data.partiallyMeasured(data.chartDays.get(0)),"complete day has no partial-measurement warning");
        Store.Group unfinished=pending(0);Store.View known=unfinished.views.get(0);known.calibration=new Geometry.Calibration(new double[]{.01,0,-2,0,-.01,2,0,0,1},.001,"test");known.calibration.reviewed=true;known.observations.add(new Store.Observation(3,new double[]{140,160},null));
        HomeStats incomplete=stats(Arrays.asList(unfinished),Double.NaN);
        check(incomplete.partiallyMeasured(incomplete.chartDays.get(0)),"partly calibrated day is explicitly identified");
        check(!incomplete.partiallyMeasured(0),"absent day is not mislabeled partial");
        System.out.println("Home summary checks passed: "+checks);
    }
}
