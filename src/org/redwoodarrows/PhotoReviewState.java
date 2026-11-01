package org.redwoodarrows;

import java.util.*;
import java.nio.charset.StandardCharsets;

/** Transient choices stay separate from autosaved photo edits. */
final class PhotoReviewState {
    final Set<Integer> selected=new TreeSet<>();
    final List<Geometry.Calibration> targets=new ArrayList<>();
    int preview=-1;

    void sync(PhotoAssignments model){
        selected.retainAll(model.ids());
        for(Geometry.Calibration target:model.targets())addTarget(target);
        if(preview>=targets.size())preview=-1;
    }
    void toggle(int id,PhotoAssignments model){
        if(!model.ids().contains(id))return;
        if(!selected.remove(id))selected.add(id);
    }
    void selectAll(PhotoAssignments model){selected.clear();selected.addAll(model.ids());}
    void selectUnassigned(PhotoAssignments model){selected.clear();selected.addAll(unassigned(model));}
    Set<Integer> unassigned(PhotoAssignments model){
        Set<Integer> result=new TreeSet<>(model.ids());
        if(!knownSize(model))return result;
        for(Store.View view:model.family())if(view.calibration!=null&&view.calibration.reviewed)
            for(Store.Observation dot:view.observations)result.remove(dot.id);
        return result;
    }
    boolean canMove(){return selected.size()==1;}
    int addTarget(Geometry.Calibration target){
        if(target==null)return -1;
        for(double value:target.h)if(!Double.isFinite(value))return -1;
        try{Geometry.inverse(target.h);}catch(IllegalArgumentException invalid){return -1;}
        for(int i=0;i<targets.size();i++)if(PhotoAssignments.same(targets.get(i),target))return i;
        Geometry.Calibration copy=TargetSelection.copy(target);Set<String> used=new HashSet<>();for(Geometry.Calibration known:targets)used.add(known.reviewLabel);
        if(!copy.reviewLabel.matches("[A-Z]{1,3}")||used.contains(copy.reviewLabel)){int next=0;while(used.contains(name(next)))next++;copy.reviewLabel=name(next);}
        targets.add(copy);return targets.size()-1;
    }
    void choose(int index){preview=index>=0&&index<targets.size()?index:-1;}
    Geometry.Calibration pending(){return preview>=0&&preview<targets.size()?targets.get(preview):null;}
    String targetName(int index){return targets.get(index).reviewLabel;}
    boolean knownSize(PhotoAssignments model){return !model.draft.preset().id.equals("offline-photo-unknown-target");}
    boolean canAssign(PhotoAssignments model){return knownSize(model)&&pending()!=null&&(!selected.isEmpty()||model.ids().isEmpty());}
    int apply(PhotoAssignments model)throws Exception {
        sync(model);if(!canAssign(model))throw new IllegalArgumentException("Select arrows and a target first");
        int count=selected.size();
        if(model.ids().isEmpty())model.assignEmptyTarget(pending());
        else model.assign(new TreeSet<>(selected),pending());
        preview=-1;sync(model);return count;
    }
    boolean saved(PhotoAssignments model,int index){
        if(index<0||index>=targets.size())return false;
        for(Store.View view:model.family())if(view.calibration!=null&&view.calibration.reviewed&&PhotoAssignments.same(view.calibration,targets.get(index)))return true;
        return false;
    }
    Map<Integer,String> labels(PhotoAssignments model){
        Map<Integer,String> labels=new HashMap<>();
        for(Store.View view:model.family())for(Store.Observation dot:view.observations){
            String label="?";
            if(view.calibration!=null&&view.calibration.reviewed)for(int i=0;i<targets.size();i++)if(PhotoAssignments.same(view.calibration,targets.get(i))){label=targetName(i);break;}
            if(!label.equals("?")||!labels.containsKey(dot.id))labels.put(dot.id,label);
        }
        return labels;
    }
    void resetTargets(){targets.clear();preview=-1;}
    static String photoLabel(Store.Group group,String viewId){
        Store.View chosen=null;for(Store.View view:group.views)if(view.id.equals(viewId))chosen=view;
        if(chosen==null)throw new IllegalArgumentException("Photo not found");
        String key=PhotoAssignments.photoKey(chosen),canonical=chosen.id;
        for(Store.View view:group.views)if(PhotoAssignments.photoKey(view).equals(key)){canonical=view.id;break;}
        String code;
        try{code=SyncFiles.hash((group.id+":"+canonical).getBytes(StandardCharsets.UTF_8)).substring(0,8).toUpperCase(Locale.US);}
        catch(Exception unavailable){throw new IllegalStateException("Could not label this photo",unavailable);}
        return "Photo "+code.substring(0,4)+"-"+code.substring(4);
    }
    static String name(int index){
        StringBuilder value=new StringBuilder();for(int n=index+1;n>0;n=(n-1)/26)value.insert(0,(char)('A'+(n-1)%26));return value.toString();
    }
}
