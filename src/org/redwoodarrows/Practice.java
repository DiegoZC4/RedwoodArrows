package org.redwoodarrows;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Optional context. Unknown is never replaced with a guessed distance/count. */
final class Practice {
    double meters=Double.NaN;
    String unit="yd",setup="",checked="";
    int fired=-1,misses=-1;
    final TreeMap<Integer,Double> arrowMeters=new TreeMap<>();
    static boolean distance(double value){return Double.isFinite(value)&&value>=.1&&value<=1000;}
    double distanceFor(int id){return arrowMeters.containsKey(id)?arrowMeters.get(id):meters;}
    static double toMeters(double value,String unit){return value*(unit.equals("yd")?.9144:1);}
    static double fromMeters(double value,String unit){return value/(unit.equals("yd")?.9144:1);}
    String label(){return distance(meters)?TargetPreset.number(fromMeters(meters,unit))+" "+unit:"Distance not set";}
    String setupLabel(){return setup.isEmpty()?"Unrecorded setup":setup;}
    Practice next()throws JSONException{Practice p=from(json());p.fired=-1;p.misses=-1;p.checked="";p.arrowMeters.clear();return p;}
    JSONObject json()throws JSONException{
        validate();JSONObject arrows=new JSONObject();for(Map.Entry<Integer,Double> e:arrowMeters.entrySet())arrows.put(""+e.getKey(),e.getValue());
        return new JSONObject().put("meters",distance(meters)?meters:JSONObject.NULL).put("source",distance(meters)?"user_entered":"unknown")
            .put("unit",unit).put("setup",setup).put("arrows_shot",fired).put("unlocated_misses",misses).put("checked_positions",checked).put("arrow_meters",arrows);
    }
    static Practice from(JSONObject j)throws JSONException{
        Practice p=new Practice();if(j==null)return p;p.meters=j.isNull("meters")?Double.NaN:j.getDouble("meters");p.unit=j.optString("unit","yd");p.setup=j.optString("setup","");p.fired=j.optInt("arrows_shot",-1);p.misses=j.optInt("unlocated_misses",-1);p.checked=j.optString("checked_positions","");
        JSONObject arrows=j.optJSONObject("arrow_meters");if(arrows!=null)for(Iterator<String> it=arrows.keys();it.hasNext();){String key=it.next();try{p.arrowMeters.put(Integer.parseInt(key),arrows.getDouble(key));}catch(NumberFormatException e){throw new JSONException("Invalid arrow distance ID");}}
        p.validate();return p;
    }
    void validate(){
        if(!Double.isNaN(meters)&&!distance(meters))throw new IllegalArgumentException("Distance must be between 0.1 and 1,000 meters, or blank");
        if(!unit.equals("yd")&&!unit.equals("m"))throw new IllegalArgumentException("Invalid distance unit");
        if(setup.length()>80)throw new IllegalArgumentException("Keep the setup name under 80 characters");
        if(fired < -1||fired>1000||misses < -1||misses>1000||(fired>=0&&misses>fired))throw new IllegalArgumentException("Check arrows shot and misses");
        for(Map.Entry<Integer,Double> e:arrowMeters.entrySet())if(e.getKey()<1||!distance(e.getValue()))throw new IllegalArgumentException("Invalid walk-up distance");
    }
    void validateCounts(Store.Group g){
        validate();List<Store.Result> results=Store.results(g);if(fired>=0&&results.size()+Math.max(0,misses)>fired)throw new IllegalArgumentException("Arrows shot must cover plotted/unresolved arrows plus unlocated misses");
    }
    void markChecked(Store.Group g){
        validateCounts(g);List<Store.Result> results=Store.results(g);
        if(fired<=0||misses<0||results.size()+misses!=fired)throw new IllegalArgumentException("Enter arrows shot and misses; account for every arrow before marking checked");
        for(Store.Result r:results)if(!Double.isFinite(r.x)||!Double.isFinite(r.y))throw new IllegalArgumentException("Resolve unknown positions before marking checked");
        checked=fingerprint(g);
    }
    boolean isChecked(Store.Group g){
        try{if(checked.isEmpty()||!checked.equals(fingerprint(g))||fired<=0||misses<0)return false;List<Store.Result> rs=Store.results(g);if(rs.size()+misses!=fired)return false;for(Store.Result r:rs)if(!Double.isFinite(r.x)||!Double.isFinite(r.y))return false;return true;}catch(Exception e){return false;}
    }
    /** Renaming/reassigning never certifies changed geometry. */
    static String fingerprint(Store.Group g){
        try{StringBuilder b=new StringBuilder(g.preset().id).append('|').append(g.radiusMm());for(Store.Result r:Store.results(g))b.append('|').append(r.id).append(':').append(Double.toHexString(r.x)).append(',').append(Double.toHexString(r.y));
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder();for(byte value:digest)hex.append(String.format(Locale.US,"%02x",value&255));return hex.toString();
        }catch(Exception e){throw new IllegalArgumentException("Cannot check these coordinates",e);}
    }
}
