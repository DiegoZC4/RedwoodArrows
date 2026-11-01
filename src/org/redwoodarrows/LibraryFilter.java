package org.redwoodarrows;

import java.util.*;

/** Read-only scope shared by the overview, photo library and progress charts. */
final class LibraryFilter {
    static List<Store.Group> groups(List<Store.Group> source,String shooter){
        List<Store.Group> selected=new ArrayList<>();
        for(Store.Group group:source)if(group.hasData()&&ShooterRoster.matches(shooter,group.shooter))selected.add(group);
        return selected;
    }
    static List<ShooterRoster.Person> people(List<ShooterRoster.Person> source,String shooter){
        List<ShooterRoster.Person> selected=new ArrayList<>();
        for(ShooterRoster.Person person:source)if(ShooterRoster.matches(shooter,person))selected.add(person);
        return selected;
    }
    static List<Long> days(List<Store.Group> source,String shooter){
        SortedSet<Long> days=new TreeSet<>(Collections.reverseOrder());
        for(Store.Group group:groups(source,shooter))days.add(ConsistencyStats.day(group.created));
        return new ArrayList<>(days);
    }
    static long availableDay(long selected,List<Store.Group> source,String shooter){
        return selected==0||days(source,shooter).contains(selected)?selected:0;
    }
}
