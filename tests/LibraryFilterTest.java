package org.redwoodarrows;

import java.util.*;

public final class LibraryFilterTest {
    static int checks;
    static final ShooterRoster.Person IRIS=new ShooterRoster.Person("iris-id","Iris"),DIEGO=new ShooterRoster.Person("diego-id","Diego");
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static Store.Group group(String id,String date,ShooterRoster.Person person)throws Exception{
        Store.Group g=Store.Group.start(TargetCatalog.defaultPreset(),person);g.id=id;g.created=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).parse(date).getTime();
        g.keepPhotos=false;g.savedResults=new ArrayList<>();g.practice.meters=13.716;
        for(int i=0;i<3;i++){Store.Result result=new Store.Result();result.id=i+1;result.x=i*10;result.y=i*5;result.method="test";g.savedResults.add(result);}return g;
    }
    public static void main(String[] args)throws Exception{
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
        Store.Group i=group("i","2026-10-14 17:40",IRIS),d=group("d","2026-10-15 15:53",DIEGO),u=group("u","2026-10-07 16:20",ShooterRoster.UNASSIGNED),i2=group("i2","2026-10-15 10:00",IRIS);
        List<Store.Group> groups=Arrays.asList(i,d,u,i2,Store.Group.start(TargetCatalog.defaultPreset(),IRIS));
        List<ShooterRoster.Person> people=Arrays.asList(IRIS,DIEGO);List<String> before=new ArrayList<>();for(Store.Group g:groups)before.add(g.json().toString());
        check(LibraryFilter.groups(groups,ShooterRoster.ALL).size()==4,"all shooters includes unassigned but excludes empty drafts");
        check(LibraryFilter.groups(groups,IRIS.id).equals(Arrays.asList(i,i2)),"Iris selection includes only Iris and keeps input order");
        check(LibraryFilter.groups(groups,DIEGO.id).equals(Arrays.asList(d)),"Diego selection is independent");
        check(LibraryFilter.groups(groups,"").equals(Arrays.asList(u)),"unassigned rounds remain accessible");
        check(LibraryFilter.groups(groups,"missing").isEmpty(),"unknown ID cannot leak another shooter's rounds");
        check(LibraryFilter.people(people,ShooterRoster.ALL).size()==2,"all progress has both people");
        check(LibraryFilter.people(people,IRIS.id).equals(Arrays.asList(IRIS)),"single-person charts contain one series");
        check(LibraryFilter.people(people,"").isEmpty(),"unknown owner is not fabricated as a measured person");
        long first=ConsistencyStats.day(i.created),second=ConsistencyStats.day(d.created);
        check(LibraryFilter.days(groups,ShooterRoster.ALL).size()==3,"date menu has unique shooting days");
        check(LibraryFilter.days(groups,IRIS.id).equals(Arrays.asList(second,first)),"date menu follows shooter and is newest first");
        check(LibraryFilter.availableDay(first,groups,IRIS.id)==first,"changing to a shooter with this day retains selection");
        check(LibraryFilter.availableDay(first,groups,DIEGO.id)==0,"changing to a shooter without this day clears a hidden date filter");
        check(LibraryFilter.availableDay(0,groups,IRIS.id)==0,"All days remains all days");
        check(LibraryFilter.days(Collections.emptyList(),ShooterRoster.ALL).isEmpty(),"empty library is valid");
        HomeStats overview=new HomeStats(LibraryFilter.groups(groups,IRIS.id),LibraryFilter.people(people,IRIS.id),"",Double.NaN);
        check(overview.sessions.size()==2&&overview.progress.series.size()==1,"Home sessions and progress share the same shooter");
        ConsistencyStats all=new ConsistencyStats(groups,people,"",false,Double.NaN,ConsistencyStats.DAILY);
        check(overview.progress.at(overview.progress.series.get(0),first).spread==all.at(all.series.get(0),first).spread,"filter does not change a shooter's measurements");
        check(overview.sessions.stream().flatMap(s->s.groups.stream()).allMatch(g->g.shooter.id.equals(IRIS.id)),"home session drill-down cannot include another shooter");
        check(LibraryFilter.people(Arrays.asList(new ShooterRoster.Person(IRIS.id,"Renamed Iris")),IRIS.id).size()==1,"filters use stable identity, not name");
        for(int at=0;at<groups.size();at++)check(before.get(at).equals(groups.get(at).json().toString()),"filters do not mutate photos, assignments or reviews");
        System.out.println("LibraryFilterTest: "+checks+" checks passed");
    }
}
