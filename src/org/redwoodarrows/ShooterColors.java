package org.redwoodarrows;

import java.util.*;

/** Appearance belongs to a stable shooter ID, not the chart's series order. */
final class ShooterColors {
    static final int PURPLE=0xFFC49BFF,BLUE=0xFF78B7FF,UNASSIGNED=0xFFB8C4BC;
    private static final int[] OTHERS={0xFFF4C967,0xFF82D6BD,0xFFF29CB6,0xFFE7B78F};
    private final Map<String,Integer> byId=new LinkedHashMap<>();
    ShooterColors(Map<String,?> saved){for(Map.Entry<String,?> entry:saved.entrySet())if(entry.getValue() instanceof Integer)byId.put(entry.getKey(),(Integer)entry.getValue());}
    boolean remember(Collection<ShooterRoster.Person> people){
        boolean changed=false;for(ShooterRoster.Person person:people)if(person.assigned()&&!byId.containsKey(person.id)){byId.put(person.id,defaultColor(person));changed=true;}return changed;
    }
    int color(ShooterRoster.Person person){return person==null||!person.assigned()?UNASSIGNED:byId.getOrDefault(person.id,defaultColor(person));}
    Map<String,Integer> snapshot(){return new LinkedHashMap<>(byId);}
    private static int defaultColor(ShooterRoster.Person person){
        String first=person.name.trim().toLowerCase(Locale.ROOT).split("\\s+",2)[0];
        if(first.equals("iris"))return PURPLE;if(first.equals("diego"))return BLUE;
        return OTHERS[Math.floorMod(person.id.hashCode(),OTHERS.length)];
    }
}
