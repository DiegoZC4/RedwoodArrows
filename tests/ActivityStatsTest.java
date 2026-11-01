package org.redwoodarrows;

import java.util.*;

public final class ActivityStatsTest {
    static int checks,ids;static final ShooterRoster.Person A=new ShooterRoster.Person("a","Alex"),B=new ShooterRoster.Person("b","Sam");
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static long at(int year,int month,int day,int hour){Calendar c=Calendar.getInstance();c.clear();c.set(year,month-1,day,hour,0);return c.getTimeInMillis();}
    static Store.Group end(long created,ShooterRoster.Person person,int dots){
        Store.Group group=Store.Group.start(TargetCatalog.defaultPreset(),person);group.id="activity-"+(++ids);group.created=created;group.keepPhotos=false;group.practice.meters=13.716;group.savedResults=new ArrayList<>();
        for(int i=0;i<dots;i++){Store.Result r=new Store.Result();r.id=i+1;r.x=i;r.y=i;r.views=1;r.method="invented";group.savedResults.add(r);}return group;
    }
    public static void main(String[] args)throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));Locale.setDefault(Locale.US);
        // US weeks start Sunday. Oct 2026: Sun 4, 11, 18, 25.
        Store.Group a1=end(at(2026,10,7,17),A,6),b1=end(at(2026,10,7,18),B,7),a2=end(at(2026,10,14,17),A,6),a3=end(at(2026,10,24,17),A,5);
        b1.practice.fired=9;String before=a1.json().toString();
        List<Store.Group> groups=Arrays.asList(a1,b1,a2,a3,a3);ActivityStats data=new ActivityStats(groups,Arrays.asList(A,B,ShooterRoster.UNASSIGNED));
        check(data.total.arrows==6+9+6+5,"entered shot count used when larger; duplicate IDs counted once");
        check(data.total.days.size()==3,"days are distinct calendar days");
        check(data.people.get("a").arrows==17&&data.people.get("a").days.size()==3,"per-person arrows and days");
        check(data.people.get("b").arrows==9&&data.people.get("b").days.size()==1,"people kept separate");
        check(data.people.size()==2,"unassigned is not a person row");
        Store.Group loose=end(at(2026,10,8,12),ShooterRoster.UNASSIGNED,4);data=new ActivityStats(Arrays.asList(a1,b1,loose),Arrays.asList(A,B));
        check(data.unassigned.arrows==4&&data.unassigned.days.size()==1&&data.total.arrows==6+9+4,"unassigned arrows tallied so people plus unassigned equals the total");
        data=new ActivityStats(groups,Arrays.asList(A,B,ShooterRoster.UNASSIGNED));
        long friday=at(2026,10,30,9);
        check(!data.shotThisWeek(friday),"no session yet this week");
        check(data.weekStreak(friday)==3,"unfinished week does not break the streak");
        check(data.weekStreak(at(2026,10,24,20))==3,"week with a session counts itself");
        check(data.weekStreak(at(2026,11,2,9))==0,"a whole missed week ends the streak");
        check(data.daysSince(friday)==6,"days since last session");
        check(data.daysThisMonth(friday)==3&&data.daysThisMonth(at(2026,11,2,9))==0,"days this month");
        Store.Group gap=end(at(2026,9,20,10),A,4);data=new ActivityStats(Arrays.asList(gap,a1),Arrays.asList(A));
        check(data.weekStreak(at(2026,10,8,9))==1,"a gap week separates streaks");
        Store.Group lastWeek=end(at(2026,10,27,17),A,4),dst=end(at(2026,11,1,10),A,4);data=new ActivityStats(Arrays.asList(lastWeek,dst),Arrays.asList(A));
        check(data.weekStreak(at(2026,11,3,9))==2,"streak spans the daylight-saving change");
        data=new ActivityStats(Collections.emptyList(),Arrays.asList(A));check(data.weekStreak(friday)==0&&data.daysSince(friday)==-1&&data.total.arrows==0,"empty library");
        Store.Group far=end(at(2026,10,9,17),A,6),unknown=end(at(2026,10,10,17),A,6);far.practice.meters=18.288;unknown.practice.meters=Double.NaN;
        data=new ActivityStats(Arrays.asList(a1,b1,a2,far,unknown),Arrays.asList(A,B));
        check(data.distanceSummary().equals("15 yd · 2 days, 20 yd · 1 day"),"distances by days, most practiced first, unknown distance left out: "+data.distanceSummary());
        check(a1.json().toString().equals(before),"activity summary never edits groupings");
        System.out.println("Activity checks passed: "+checks);
    }
}
