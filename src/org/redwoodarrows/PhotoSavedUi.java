package org.redwoodarrows;

import android.view.*;
import android.widget.*;

/** A receipt, not an annotation task. The photo and labels are already durable. */
final class PhotoSavedUi {
    static void show(MainActivity a){
        a.page="photo-saved";Store.editingGroupId=null;a.shell("Photo saved");
        LinearLayout top=a.row();top.setGravity(Gravity.CENTER_VERTICAL);a.root.removeView(a.heading);top.addView(a.heading,new LinearLayout.LayoutParams(0,-2,1));top.addView(a.button("Gallery",a::home));a.add(a.root,top);
        CaptureLabels labels=new CaptureLabels(a,()->a.group,false,(person,practice)->{
            synchronized(Store.FILE_LOCK){Store.Group changed=a.store.read(new java.io.File(a.store.directory(a.group),"group.json"));if(!changed.shooter.id.equals(person.id))Store.confirmShooter(changed,person);changed.practice=Practice.from(practice.json());a.store.save(changed);a.group=changed;}SyncRuntime.saved(a);return true;
        });a.add(a.root,labels);
        final int at=Math.max(0,Math.min(a.index,a.group.views.size()-1));a.root.addView(preview(a,at),new LinearLayout.LayoutParams(-1,0,1));
        TextView status=a.text("Saved on this phone · syncs when connected",12,MainActivity.MUTED);status.setGravity(Gravity.CENTER);status.setTag("photo-save-receipt");a.add(a.root,status);
        LinearLayout optional=a.row();Button another=a.button("Another view",()->a.takePhoto(true));another.setTag("photo-another-view");another.setContentDescription("Take another view of this same grouping; keep shooter and distance");a.cell(optional,another);
        Button review=a.button("Review arrows",()->a.assignTargets(a.group,at,a.group.views.get(at).detectionState.isEmpty()&&a.group.views.get(at).observations.isEmpty()));review.setTag("photo-review-arrows");a.cell(optional,review);a.add(a.root,optional);
        Button next=a.button("Next grouping",()->a.takePhoto(false));next.setTag("photo-next-grouping");next.setTextSize(18);next.setBackground(a.rounded(MainActivity.ACCENT));next.setTextColor(MainActivity.BG);a.add(a.root,next);
        refresh(a);PhotoDetectionRuntime.saved(a);
    }
    static GroupImages.Preview preview(MainActivity a,int at){GroupImages.Preview photo=a.groupImages.new Preview(a.group,at,GroupImages.WARPED,1024);photo.setTag("saved-photo");photo.setOnClickListener(v->a.showGroupImage(a.group,GroupImages.WARPED,at));return photo;}
    static void refresh(MainActivity a){if(!a.page.equals("photo-saved")||a.group==null)return;try{
        a.group=a.store.read(new java.io.File(a.store.directory(a.group),"group.json"));TextView status=a.root.findViewWithTag("photo-save-receipt");if(status!=null){String detection=PhotoDetection.status(a.group);status.setText("Photo + labels saved"+(detection.isEmpty()?"":" · "+detection));}
        View old=a.root.findViewWithTag("saved-photo");if(old!=null){int at=a.root.indexOfChild(old);a.root.removeView(old);a.root.addView(preview(a,Math.min(a.index,a.group.views.size()-1)),at,new LinearLayout.LayoutParams(-1,0,1));}
    }catch(Exception ignored){/* The existing durable receipt remains visible. */}}
}
