package org.redwoodarrows;

import java.util.*;

public final class ShooterColorsTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        ShooterRoster.Person iris=new ShooterRoster.Person("iris-id","Iris"),diego=new ShooterRoster.Person("diego-id","Diego");
        ShooterColors colors=new ShooterColors(Collections.emptyMap());
        check(colors.remember(Arrays.asList(diego,iris)),"first roster stores colors");
        check(colors.color(iris)==ShooterColors.PURPLE,"Iris is purple");
        check(colors.color(diego)==ShooterColors.BLUE,"Diego is blue");
        check(!colors.remember(Arrays.asList(iris,diego)),"reordering does not write preferences");
        check(colors.color(iris)!=colors.color(diego),"people have distinct colors");
        ShooterColors restored=new ShooterColors(colors.snapshot());
        ShooterRoster.Person renamed=new ShooterRoster.Person(iris.id,"I. Liao");
        check(restored.color(renamed)==ShooterColors.PURPLE,"stable ID survives rename and restart");
        check(!restored.remember(Arrays.asList(renamed,diego)),"rename preserves assignment");
        check(restored.color(new ShooterRoster.Person("new-iris","IRIS Liao"))==ShooterColors.PURPLE,"full name and case supported");
        check(restored.color(new ShooterRoster.Person("new-diego","diego Z"))==ShooterColors.BLUE,"Diego full name supported");
        ShooterRoster.Person other=new ShooterRoster.Person("other-id","Alex");
        int otherColor=colors.color(other);colors.remember(Arrays.asList(other));
        check(new ShooterColors(colors.snapshot()).color(other)==otherColor,"fallback color deterministic");
        check(colors.color(ShooterRoster.UNASSIGNED)==ShooterColors.UNASSIGNED,"unassigned stays neutral");
        check(colors.color(null)==ShooterColors.UNASSIGNED,"missing person stays neutral");
        Map<String,Object> invalid=new HashMap<>();invalid.put(iris.id,"invalid");
        check(new ShooterColors(invalid).color(iris)==ShooterColors.PURPLE,"invalid preference uses default");
        Map<String,Integer> snapshot=colors.snapshot();snapshot.put(iris.id,0);
        check(colors.color(iris)==ShooterColors.PURPLE,"snapshot does not mutate assignments");
        check(iris.name.equals("Iris")&&diego.name.equals("Diego"),"profiles unchanged");
        System.out.println("Shooter color checks passed: "+checks);
    }
}
