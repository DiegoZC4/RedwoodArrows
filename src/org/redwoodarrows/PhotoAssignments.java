package org.redwoodarrows;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/** A photo can have several aimed-at faces. Reuse the existing, backwards-
 * compatible calibrated-view format: each face owns a disjoint set of marks.
 * JPEG aliases have identical bytes (the sync server deduplicates blobs).
 * No detector, target-nearness guess, or new schema is needed for assignment. */
final class PhotoAssignments {
    static final String PREFIX="photo-assign:";
    final Store.Group original;final String referenceId;
    Store.Group draft;final Map<String,String> copies=new LinkedHashMap<>();
    int revision;
    PhotoAssignments(Store.Group source,String viewId)throws Exception {
        if(source.coordinatesOnly())throw new IllegalArgumentException("This grouping no longer has photos");
        original=source;draft=Store.copy(source);referenceId=viewId;reference();
    }
    Store.View reference(){for(Store.View v:draft.views)if(v.id.equals(referenceId))return v;throw new IllegalArgumentException("Photo changed; reopen the grouping");}
    static String photoKey(Store.View v){return v.session.startsWith(PREFIX)?v.session:!v.sourceHash.isEmpty()?"sha:"+v.sourceHash:"view:"+v.id;}
    static List<Integer> photoIndices(Store.Group g){List<Integer> result=new ArrayList<>();Set<String> seen=new HashSet<>();for(int i=0;i<g.views.size();i++)if(seen.add(photoKey(g.views.get(i))))result.add(i);return result;}
    List<Store.View> family(){String key=photoKey(reference());List<Store.View> result=new ArrayList<>();for(Store.View v:draft.views)if(photoKey(v).equals(key))result.add(v);return result;}
    Set<Integer> ids(){Set<Integer> ids=new TreeSet<>();for(Store.View v:family())for(Store.Observation o:v.observations)ids.add(o.id);return ids;}
    String snapshot()throws Exception{return draft.json().toString();}
    void restore(String json)throws Exception {String digest=draft.diskDigest;draft=Store.Group.from(new JSONObject(json));draft.diskDigest=digest;revision++;}
    static boolean same(Geometry.Calibration a,Geometry.Calibration b){if(a==null||b==null)return a==b;for(int i=0;i<9;i++)if(Math.abs(a.h[i]/a.h[8]-b.h[i]/b.h[8])>1e-8)return false;return true;}
    Store.View bucket(List<Store.View> family,Geometry.Calibration calibration)throws Exception {
        for(Store.View v:family)if(same(v.calibration,calibration))return v;
        if(draft.views.size()>=64)throw new IllegalArgumentException("This grouping has reached its 64 target-view limit");
        Store.View base=family.get(0),v=Store.View.from(base.json());
        String familyId=base.session.startsWith(PREFIX)?base.session:PREFIX+base.id;
        for(Store.View member:family)member.session=familyId;
        v.id=UUID.randomUUID().toString();v.file=v.id+".jpg";v.session=familyId;
        v.observations.clear();v.rimMarks.clear();v.innerMarks.clear();
        v.calibration=calibration==null?null:TargetSelection.copy(calibration);
        String source=base.file;while(copies.containsKey(source))source=copies.get(source);copies.put(v.file,source);
        draft.views.add(v);family.add(v);return v;
    }
    int add(double x,double y)throws Exception {
        Store.View reference=reference();if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||y<0||x>=reference.width||y>=reference.height)throw new IllegalArgumentException("Mark an entry inside the photo");
        int id=0;for(Store.View v:draft.views)for(Store.Observation o:v.observations)id=Math.max(id,o.id);
        if(id>=999)throw new IllegalArgumentException("Arrow number limit reached");
        Store.Observation mark=new Store.Observation(++id,new double[]{x,y},null);mark.source="manual photo entry";
        mark.associationWarning="Approximate marked entry center; assign its intended target before using a 2D position.";
        bucket(family(),null).observations.add(mark);draft.practice.checked="";revision++;return id;
    }
    void remove(Set<Integer> selected){for(Store.View v:family())v.observations.removeIf(o->selected.contains(o.id));draft.practice.checked="";revision++;}
    static void move(List<Store.View> from,Store.View to,Set<Integer> selected,String warning){
        List<Store.Observation> moved=new ArrayList<>();
        for(Store.View v:from){Iterator<Store.Observation> it=v.observations.iterator();while(it.hasNext()){Store.Observation o=it.next();if(selected.contains(o.id)){it.remove();if(!o.associationWarning.contains(warning))o.associationWarning+=(o.associationWarning.isEmpty()?"":" ")+warning;moved.add(o);}}}
        // Same-image aliases must never turn one physical mark into two votes.
        Set<String> seen=new HashSet<>();for(Store.Observation o:moved)if(seen.add(o.id+":"+o.line))to.observations.add(o);
    }
    void assign(Set<Integer> selected,Geometry.Calibration target)throws Exception {
        if(selected.isEmpty()||!ids().containsAll(selected))throw new IllegalArgumentException("Select arrows in this photo first");
        Geometry.inverse(target.h);for(double value:target.h)if(!Double.isFinite(value))throw new IllegalArgumentException("Invalid target outline");
        String before=snapshot();
        try{
            List<Store.View> own=family();Store.View destination=bucket(own,target);
            destination.calibration=TargetSelection.copy(target);destination.calibration.reviewed=true;
            if(!destination.calibration.source.startsWith(TargetSelection.PREFIX))destination.calibration.source=TargetSelection.PREFIX+destination.calibration.source;
            move(own,destination,selected,"Intended bullseye assigned by user in this photo; approximate entry positions.");
            for(Store.Observation o:destination.observations)if(selected.contains(o.id)){
                // Even an NN-proposed dot now carries a manual intent choice.
                // The existing sweep-accept guard must preserve that choice.
                if(!o.source.startsWith("target-assigned:"))o.source="target-assigned:"+o.source;
                o.associationWarning=o.associationWarning.replace("assign its intended target before using a 2D position.","target now assigned.").replace("check the dot and assign its intended bullseye.","check the entry dot; target now assigned.");
            }
            // Another camera angle may still refer to the PREVIOUS target. Keep
            // those pixels but exclude only the reassigned IDs until rechecked.
            Set<String> ownIds=new HashSet<>();for(Store.View v:own)ownIds.add(v.id);
            Map<String,List<Store.View>> others=new LinkedHashMap<>();
            for(Store.View v:new ArrayList<>(draft.views))if(!ownIds.contains(v.id))others.computeIfAbsent(photoKey(v),k->new ArrayList<>()).add(v);
            for(List<Store.View> family:others.values()){
                boolean affected=false;for(Store.View v:family)for(Store.Observation o:v.observations)if(selected.contains(o.id)&&v.calibration!=null)affected=true;
                if(affected)move(family,bucket(family,null),selected,"Target reassigned in another photo; this view needs target confirmation before combining.");
            }
            draft.practice.checked="";revision++;
        }catch(Exception e){restore(before);throw e;}
    }
    Store.View overlay()throws Exception {Store.View v=Store.View.from(reference().json());v.calibration=null;v.observations.clear();v.rimMarks.clear();v.innerMarks.clear();for(Store.View member:family())v.observations.addAll(member.observations);return v;}
    List<Geometry.Calibration> targets(){List<Geometry.Calibration> result=new ArrayList<>();for(Store.View v:family())if(v.calibration!=null){boolean known=false;for(Geometry.Calibration c:result)known|=same(c,v.calibration);if(!known)result.add(v.calibration);}return result;}
    Map<Integer,String> labels(){Map<Integer,String> labels=new HashMap<>();List<Geometry.Calibration> targets=targets();for(Store.View v:family())for(Store.Observation o:v.observations){String name="?";for(int i=0;i<targets.size();i++)if(same(v.calibration,targets.get(i)))name=""+(char)('A'+i);labels.put(o.id,name);}return labels;}
    Store.Group commit(Store store)throws Exception {
        List<File> created=new ArrayList<>();boolean saved=false;
        try{
            for(Store.View v:draft.views)if(copies.containsKey(v.file)){
                File dest=new File(store.directory(draft),SyncFiles.photo(v.file)),source=new File(store.directory(original),SyncFiles.photo(copies.get(v.file)));
                if(!source.isFile())throw new IOException("Original photo is missing");
                if(dest.exists()){if(!SyncFiles.hash(dest).equals(SyncFiles.hash(source)))throw new IOException("Photo copy changed");}
                else {
                    if(!dest.createNewFile())throw new IOException("Photo copy changed; reopen the grouping");
                    created.add(dest); // Also clean up a partial copy if the disk fills.
                    Files.copy(source.toPath(),dest.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            store.save(draft);saved=true;return draft;
        }finally{if(!saved)for(File file:created)if(!file.delete())file.deleteOnExit();}
    }
}
