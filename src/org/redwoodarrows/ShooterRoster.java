package org.redwoodarrows;

import java.text.Normalizer;
import java.util.*;
import org.json.*;

/** Local names, stable identities and the next grouping's shooter. No accounts. */
public final class ShooterRoster {
    public static final String ALL="*";
    public static final Person UNASSIGNED=new Person("","");
    public static final class Person {
        public final String id,name;
        public Person(String id,String name){
            if(id==null||(!id.isEmpty()&&!id.matches("[A-Za-z0-9_-]{1,64}")))throw new IllegalArgumentException("Invalid shooter identity");
            this.id=id;this.name=id.isEmpty()?"":cleanName(name);
        }
        public boolean assigned(){return !id.isEmpty();}
        public String label(){return assigned()?name:"Unassigned";}
        public JSONObject json()throws JSONException{return new JSONObject().put("id",id).put("name",name);}
        public static Person from(JSONObject j)throws JSONException{return j==null?UNASSIGNED:new Person(j.getString("id"),j.optString("name",""));}
    }
    private final LinkedHashMap<String,Person> people=new LinkedHashMap<>();
    private String activeId="";
    public List<Person> all(){return Collections.unmodifiableList(new ArrayList<>(people.values()));}
    public Person find(String id){return id!=null&&id.isEmpty()?UNASSIGNED:people.get(id);}
    public Person active(){return find(activeId);}
    public Person resolve(Person snapshot){Person current=find(snapshot.id);return current==null?snapshot:current;}
    public void select(String id){if(find(id)==null)throw new IllegalArgumentException("Choose a saved shooter");activeId=id;}
    public Person add(String name){
        String clean=cleanName(name);uniqueName(clean,"");Person p=new Person(UUID.randomUUID().toString(),clean);people.put(p.id,p);return p;
    }
    /** Restored group snapshots can retain their identity without a roster backup. */
    public void remember(Person p){if(p.assigned()&&!people.containsKey(p.id))people.put(p.id,p);}
    public void rename(String id,String name){
        if(id.isEmpty()||!people.containsKey(id))throw new IllegalArgumentException("Choose a saved shooter");
        String clean=cleanName(name);uniqueName(clean,id);people.put(id,new Person(id,clean));
    }
    private void uniqueName(String name,String except){for(Person p:people.values())if(!p.id.equals(except)&&p.name.equalsIgnoreCase(name))throw new IllegalArgumentException("That name is already saved. Choose that shooter or use a different name.");}
    public static String cleanName(String input){
        if(input==null)throw new IllegalArgumentException("Enter a shooter name");
        // Explicit separators behave alike on Android ICU and the host JVM;
        // Android does not support the host regex flag (?U).
        String normalized=Normalizer.normalize(input,Normalizer.Form.NFC).replaceAll("[\\p{Z}\\t\\n\\x0B\\f\\r\\u0085]+"," ");
        for(int i=0;i<normalized.length();i++)if(Character.isISOControl(normalized.charAt(i)))throw new IllegalArgumentException("Use a plain, single-line name");
        String name=normalized.trim();
        if(name.isEmpty()||name.codePointCount(0,name.length())>40)throw new IllegalArgumentException("Use a name between 1 and 40 characters");
        if(name.equalsIgnoreCase("Unassigned")||name.equalsIgnoreCase("All shooters"))throw new IllegalArgumentException("Use a person's name, not a filter label");
        return name;
    }
    public static boolean matches(String filter,Person shooter){return ALL.equals(filter)||shooter.id.equals(filter);}
    public static int toggle(int current){if(current!=0&&current!=1)throw new IllegalArgumentException("Two-choice selection required");return 1-current;}
    public JSONObject json()throws JSONException{
        JSONArray items=new JSONArray();for(Person p:people.values())items.put(p.json());
        return new JSONObject().put("schema",1).put("active_id",activeId).put("people",items);
    }
    public ShooterRoster copy()throws JSONException{return from(json().toString());}
    public static ShooterRoster from(String raw)throws JSONException{
        ShooterRoster roster=new ShooterRoster();if(raw==null||raw.trim().isEmpty())return roster;
        JSONObject j=new JSONObject(raw);if(j.getInt("schema")!=1)throw new IllegalArgumentException("Unrecognized shooter profile format");
        JSONArray items=j.getJSONArray("people");
        for(int i=0;i<items.length();i++){Person p=Person.from(items.getJSONObject(i));if(!p.assigned()||roster.people.put(p.id,p)!=null)throw new IllegalArgumentException("Duplicate or empty shooter identity");}
        roster.select(j.optString("active_id",""));return roster;
    }
}
