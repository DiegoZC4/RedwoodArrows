package org.redwoodarrows;

import java.util.List;

/** Review status uses the existing checked-position fingerprint, not a new flag. */
final class PhotoReview {
    final int marked,located,expected,misses,needsCalibration;
    final boolean reviewed;
    PhotoReview(Store.Group group){
        List<Store.Result> results=Store.results(group);
        marked=results.size();expected=group.practice.fired;misses=group.practice.misses;
        int valid=0,pending=0;for(Store.Result result:results){
            if(Double.isFinite(result.x)&&Double.isFinite(result.y))valid++;
            else if(needsTarget(group,result.id))pending++;
        }
        located=valid;needsCalibration=pending;reviewed=group.practice.isChecked(group);
    }
    String status(){return reviewed?"Reviewed":"Needs review";}
    String counts(){return marked+" marked"+(expected>=0?" / "+expected+" shot":"")+" | "+located+" measured";}
    String measurementStatus(){
        int pending=marked-located;if(pending==0)return "";
        if(needsCalibration==pending)return pending+" need target calibration";
        return needsCalibration>0?needsCalibration+" need target calibration; "+(pending-needsCalibration)+" other measurements pending":pending+" measurements pending";
    }
    static boolean needsTarget(Store.Group group,int arrow){
        if(group.preset().id.equals("offline-photo-unknown-target"))return true;
        for(Store.View view:group.views)if(view.calibration!=null&&view.calibration.reviewed)
            for(Store.Observation dot:view.observations)if(dot.id==arrow)return false;
        return true;
    }
    static Store.Group save(PhotoAssignments model,Store store)throws Exception {
        Practice practice=model.draft.practice;practice.validate();
        // Saving dots does not guess shot counts or certify unconfirmed geometry.
        if(!practice.isChecked(model.draft))practice.checked="";
        return model.commit(store);
    }
    static int count(String text){
        if(text.trim().isEmpty())return -1;
        try{int count=Integer.parseInt(text.trim());if(count>=0&&count<=1000)return count;}catch(NumberFormatException ignored){}
        throw new IllegalArgumentException("Use a whole number from 0 to 1,000");
    }
    static void finish(PhotoAssignments model,int fired,int misses){
        Practice practice=model.draft.practice;
        int oldFired=practice.fired,oldMisses=practice.misses;String oldChecked=practice.checked;
        try{practice.fired=fired;practice.misses=misses;practice.markChecked(model.draft);model.revision++;}
        catch(RuntimeException e){practice.fired=oldFired;practice.misses=oldMisses;practice.checked=oldChecked;throw e;}
    }
    static boolean updateCounts(PhotoAssignments model,int fired,int misses){
        Practice practice=model.draft.practice;int oldFired=practice.fired,oldMisses=practice.misses;
        if(oldFired==fired&&oldMisses==misses)return false;
        try{practice.fired=fired;practice.misses=misses;practice.validate();practice.checked="";return true;}
        catch(RuntimeException e){practice.fired=oldFired;practice.misses=oldMisses;throw e;}
    }
}
