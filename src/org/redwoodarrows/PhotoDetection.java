package org.redwoodarrows;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Durable, per-photo work records. No model, camera, network or Activity here. */
final class PhotoDetection {
    static final String AUTO="automatic photo proposal · ";
    static File ownerFile(Store store){return new File(store.root.getParentFile(),"photo-detector-id");}
    static String owner(Store store,boolean create)throws IOException {
        synchronized(Store.FILE_LOCK){File file=ownerFile(store);
            if(!file.isFile()){if(!create)return "";SyncFiles.atomic(file,UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));}
            String id=new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
            if(!id.matches("[a-f0-9-]{36}"))throw new IOException("Invalid photo queue identity");return id;
        }
    }
    static void prepare(Store store,Store.View view){try{view.detectionOwner=owner(store,true);view.detectionState="pending";}catch(IOException e){view.detectionOwner="";view.detectionState="failed";/* Queue failure must not prevent the photo transaction. */}}
    static final class Task {
        final Store.Group group;final Store.View view;final String photoHash;
        Task(Store store,Store.Group group,Store.View view)throws Exception {this.group=group;this.view=view;File file=new File(store.directory(group),SyncFiles.photo(view.file));photoHash=file.isFile()?SyncFiles.hash(file):"";}
    }
    static List<Task> pending(Store store)throws Exception {
        List<Task> tasks=new ArrayList<>();String owner=owner(store,false);if(owner.isEmpty())return tasks;
        for(Store.Group group:store.list())for(Store.View view:group.views)if(view.detectionState.equals("pending")&&view.detectionOwner.equals(owner))tasks.add(new Task(store,group,view));
        return tasks;
    }
    /** Called under the store lock. Rebase onto current labels and other photos,
     * never onto stale geometry; manually removed marks must stay removed. */
    static boolean complete(Store store,Task task,List<double[]> points,Geometry.Calibration proposal,boolean failed)throws Exception {
        synchronized(Store.FILE_LOCK){
            if(task.group.id.equals(Store.editingGroupId))return false;
            File file=new File(new File(store.root,SyncFiles.id(task.group.id)),"group.json");if(!file.isFile())return false;
            Store.Group latest=store.read(file);Store.View view=null;for(Store.View v:latest.views)if(v.id.equals(task.view.id))view=v;
            if(view==null||!view.detectionState.equals("pending")||!view.detectionOwner.equals(task.view.detectionOwner))return false;
            if(!view.json().toString().equals(task.view.json().toString())||!view.observations.isEmpty()||view.calibration!=null||!view.rimMarks.isEmpty()||!view.innerMarks.isEmpty()){
                view.detectionState="edited";store.save(latest);return true;
            }
            // The model saw this exact photo. A replaced file cannot receive its dots.
            File photo=new File(store.directory(latest),SyncFiles.photo(view.file));
            if(!(photo.isFile()?SyncFiles.hash(photo):"").equals(task.photoHash)){view.detectionState="edited";store.save(latest);return true;}
            int id=0;for(Store.View v:latest.views)for(Store.Observation o:v.observations)id=Math.max(id,o.id);
            if(!failed)for(double[] point:points){
                if(point.length<3||!Double.isFinite(point[0])||!Double.isFinite(point[1])||!Double.isFinite(point[2])||point[0]<0||point[1]<0||point[0]>=view.width||point[1]>=view.height||id>=999)continue;
                boolean duplicate=false;for(Store.Observation o:view.observations)if(Math.hypot(o.a[0]-point[0],o.a[1]-point[1])<view.width*.012)duplicate=true;if(duplicate)continue;
                Store.Observation mark=new Store.Observation(++id,new double[]{point[0],point[1]},null);mark.source="impact-model:background-photo";mark.confidence=point[2];
                mark.associationWarning="Automatic entry estimate; target and dot are unconfirmed. Different photos have not been matched as the same arrows.";view.observations.add(mark);
            }
            if(!failed&&proposal!=null&&!view.observations.isEmpty()&&Store.presetJson(latest.preset()).toString().equals(Store.presetJson(task.group.preset()).toString())){
                view.calibration=TargetSelection.copy(proposal);view.calibration.reviewed=false;view.calibration.source=AUTO+proposal.source;
            }
            view.detectionState=failed?"failed":"done";store.save(latest);return true;
        }
    }
    /** A single clearly dominant face may be previewed, never marked reviewed.
     * Several populated faces or a tie stays photo-space instead of guessing. */
    static Geometry.Calibration target(List<Geometry.Calibration> targets,List<double[]> points){
        if(points.isEmpty())return null;int[] votes=new int[targets.size()];
        for(double[] p:points){int best=-1;double nearest=1.15;for(int i=0;i<targets.size();i++)try{double[] q=targets.get(i).map(p[0],p[1]);double r=Math.hypot(q[0],q[1]);if(r<nearest){nearest=r;best=i;}}catch(IllegalArgumentException ignored){}if(best>=0)votes[best]++;}
        int best=-1,second=0;for(int i=0;i<votes.length;i++){if(best<0||votes[i]>votes[best]){if(best>=0)second=Math.max(second,votes[best]);best=i;}else second=Math.max(second,votes[i]);}
        return best>=0&&votes[best]>=Math.ceil(points.size()*.7)&&second==0?targets.get(best):null;
    }
    static String status(Store.Group group){
        int dots=0;boolean known=false,pending=false,failed=false;for(Store.View v:group.views){known|=!v.detectionState.isEmpty();pending|=v.detectionState.equals("pending");failed|=v.detectionState.equals("failed");dots+=v.observations.size();}
        if(pending)return "Finding arrows…";if(failed)return "Detection unavailable · photo saved";
        return known?(dots==0?"No arrows found":dots+" arrow dots · estimated"):"";
    }
    static boolean proposal(Store.View v){return v.calibration!=null&&!v.calibration.reviewed&&v.calibration.source.startsWith(AUTO);}
    static String plotStatus(Store.Group group,List<Store.Result> results){boolean known=false,estimated=false;for(Store.View v:group.views){known|=!v.detectionState.isEmpty();estimated|=proposal(v);if(v.detectionState.equals("pending"))return "Finding arrows…";}if(estimated)return "Estimated";if(known&&results.stream().noneMatch(r->Double.isFinite(r.x)&&Double.isFinite(r.y)))return "No alignment";return "";}
    /** Visual proposals are not silently promoted into progress statistics. */
    static List<Store.Result> previewResults(Store.Group group){
        boolean found=false;for(Store.View v:group.views)found|=proposal(v);if(!found)return Store.results(group);
        try{Store.Group preview=Store.copy(group);for(Store.View v:preview.views)if(proposal(v))v.calibration.reviewed=true;return Store.results(preview);}catch(Exception e){return Store.results(group);}
    }
}
