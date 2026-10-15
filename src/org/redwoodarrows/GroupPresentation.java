package org.redwoodarrows;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

/** Read-only presentation, separate from saved measurements and provenance. */
final class GroupPresentation {
    final Store.Group group;final List<Store.Result> results;
    final List<double[]> points=new ArrayList<>();final LinkedHashSet<String> warnings=new LinkedHashSet<>();
    final int located,unresolved,photos;final double spread;
    GroupPresentation(Store.Group group){
        this.group=group;results=Store.results(group);photos=PhotoAssignments.photoIndices(group).size();
        for(Store.Result r:results){if(Double.isFinite(r.x)&&Double.isFinite(r.y))points.add(new double[]{r.x,r.y});if(!r.warning.trim().isEmpty())for(String note:r.warning.trim().split("(?<=\\.)\\s+|\\s+·\\s+"))if(!note.trim().isEmpty())warnings.add(note.trim());}
        located=points.size();unresolved=results.size()-located;spread=Geometry.spread(points);
    }
    String shortName(){
        Matcher m=Pattern.compile("^.+ · End (\\d+) · .+$").matcher(group.name);
        return m.matches()?"End "+m.group(1).replaceFirst("^0+(?!$)",""):group.name;
    }
    String date(){return new SimpleDateFormat("MMM d · h:mm a",Locale.US).format(new Date(group.created));}
    String caption(){String name=shortName();return name.isEmpty()?"Grouping":name;}
    String count(){int total=Math.max(results.size(),group.practice.fired);return located==total?located+" arrows":located+" / "+total+" located";}
    boolean provisionalOwner(){for(String s:warnings)if(s.toLowerCase(Locale.US).contains("shooter provisional"))return true;return false;}
    String status(){return located==0?"Not measured":provisionalOwner()?"Estimate · shooter unconfirmed":unresolved>0?"Estimate · "+unresolved+" unresolved":"Estimated positions";}
    String coordinateTable(){
        StringBuilder s=new StringBuilder("Arrow      x mm      y mm\n");
        for(Store.Result r:results)s.append(Double.isFinite(r.x)&&Double.isFinite(r.y)?String.format(Locale.US,"#%-5d  %+8.1f  %+8.1f\n",r.id,r.x,r.y):String.format(Locale.US,"#%-5d  unresolved\n",r.id));
        return s.toString();
    }
    String details(){
        StringBuilder s=new StringBuilder(group.targetLabel()).append("\n").append(date()).append("\n").append(group.axes()).append("\n\nEstimates, not certified accuracy.");
        s.append("\n").append(group.practice.label()).append(" · ").append(group.practice.setupLabel());
        Set<String> methods=new LinkedHashSet<>();for(Store.Result r:results)methods.add(r.method);
        if(!methods.isEmpty())s.append("\n").append(String.join(" · ",methods));
        for(String warning:warnings)s.append("\n\n").append(warning);
        return s.toString();
    }
}
