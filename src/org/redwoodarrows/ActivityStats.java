package org.redwoodarrows;

import java.util.*;

/** Read-only practice activity: days at the range, arrows recorded and a weekly streak. Never edits groupings. */
final class ActivityStats {
    static final class Tally {int arrows;final SortedSet<Long> days=new TreeSet<>();}
    final Tally total=new Tally(),unassigned=new Tally();final Map<String,Tally> people=new LinkedHashMap<>();
    /** Whole yards → the calendar days shot at that distance (groupings without a known distance are left out). */
    final TreeMap<Long,SortedSet<Long>> distances=new TreeMap<>();
    ActivityStats(List<Store.Group> groups,List<ShooterRoster.Person> roster){
        for(ShooterRoster.Person person:roster)if(person.assigned())people.put(person.id,new Tally());
        Set<String> seen=new HashSet<>();
        for(Store.Group group:groups){if(!group.hasData()||!seen.add(group.id))continue;int arrows=arrows(group);long day=ConsistencyStats.day(group.created);
            total.arrows+=arrows;total.days.add(day);Tally person=group.shooter.assigned()?people.get(group.shooter.id):unassigned;if(person!=null){person.arrows+=arrows;person.days.add(day);}
            if(Practice.distance(group.practice.meters))distances.computeIfAbsent(Math.round(Practice.fromMeters(group.practice.meters,"yd")),k->new TreeSet<>()).add(day);}
    }
    /** Saved arrow entries, or the entered shot count when larger (it includes misses). Separate photos of one physical end cannot be told apart, so this is arrows recorded, not proven distinct shots. */
    static int arrows(Store.Group group){return Math.max(Store.results(group).size(),group.practice.fired);}
    /** Local midnight starting the calendar week (the locale's first day) that contains this time. */
    static long week(long time){Calendar c=Calendar.getInstance();c.setTimeInMillis(ConsistencyStats.day(time));c.add(Calendar.DAY_OF_MONTH,-((c.get(Calendar.DAY_OF_WEEK)-c.getFirstDayOfWeek()+7)%7));return c.getTimeInMillis();}
    boolean shotThisWeek(long now){long current=week(now);for(long day:total.days)if(week(day)==current)return true;return false;}
    /** Consecutive calendar weeks with a session, counting back from this week. An unfinished week without a session yet does not break the streak. */
    int weekStreak(long now){
        Set<Long> weeks=new HashSet<>();for(long day:total.days)weeks.add(week(day));
        Calendar c=Calendar.getInstance();c.setTimeInMillis(week(now));if(!weeks.contains(c.getTimeInMillis()))c.add(Calendar.WEEK_OF_YEAR,-1);
        int streak=0;while(weeks.contains(c.getTimeInMillis())){streak++;c.add(Calendar.WEEK_OF_YEAR,-1);c.setTimeInMillis(week(c.getTimeInMillis()));}return streak;
    }
    /** Whole calendar days since the most recent session; -1 with no sessions. */
    int daysSince(long now){return total.days.isEmpty()?-1:(int)Math.round((ConsistencyStats.day(now)-total.days.last())/86400000.0);}
    /** "15 yd · 8 days, 20 yd · 3 days", most-practiced distance first. */
    String distanceSummary(){List<Map.Entry<Long,SortedSet<Long>>> rows=new ArrayList<>(distances.entrySet());rows.sort((m,n)->n.getValue().size()!=m.getValue().size()?n.getValue().size()-m.getValue().size():Long.compare(m.getKey(),n.getKey()));
        List<String> parts=new ArrayList<>();for(Map.Entry<Long,SortedSet<Long>> row:rows)parts.add(row.getKey()+" yd · "+row.getValue().size()+(row.getValue().size()==1?" day":" days"));return String.join(", ",parts);}
    int daysThisMonth(long now){Calendar at=Calendar.getInstance();at.setTimeInMillis(now);int n=0;Calendar c=Calendar.getInstance();for(long day:total.days){c.setTimeInMillis(day);if(c.get(Calendar.YEAR)==at.get(Calendar.YEAR)&&c.get(Calendar.MONTH)==at.get(Calendar.MONTH))n++;}return n;}
}
