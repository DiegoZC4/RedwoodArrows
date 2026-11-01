package org.redwoodarrows;

import android.content.res.ColorStateList;
import android.widget.CheckBox;

/** Explicit approval of the displayed photo, without entering the dot editor. */
final class QuickReviewUi {
    static CheckBox checkbox(MainActivity a,Store.Group group,String photo,boolean checked,boolean ready,Runnable refresh){
        CheckBox check=new CheckBox(a);check.setTextSize(13);check.setMinHeight(a.dp(44));check.setMaxLines(2);
        check.setButtonTintList(ColorStateList.valueOf(MainActivity.ACCENT));check.setTag("quick-dot-review:"+photo);
        state(check,checked,ready);
        check.setOnClickListener(v->{
            boolean wanted=check.isChecked();check.setChecked(!wanted);
            if(a.busy)return;
            a.busy=true;check.setEnabled(false);check.setText("Saving...");a.rememberGroups();
            a.worker.execute(()->{
                Store.Group saved=null;String failure="";
                try{saved=TrainingReviews.confirm(a.store,group,photo,wanted);}catch(Exception e){failure=e.getMessage()==null?"Could not save this review":e.getMessage();}
                final Store.Group result=saved;final String error=failure;
                a.runOnUiThread(()->{
                    a.busy=false;
                    if(result!=null){if(a.group!=null&&a.group.id.equals(result.id)){a.group=result;a.analysis=null;}a.groupImages.thumbs.evictAll();SyncRuntime.saved(a);}
                    if(a.isDestroyed()||a.isFinishing())return;
                    if(result==null){state(check,!wanted,true);a.message(error);}else if(check.isAttachedToWindow())refresh.run();
                });
            });
        });
        return check;
    }
    static void state(CheckBox check,boolean checked,boolean ready){
        check.setChecked(checked);check.setEnabled(ready);check.setText(ready||checked?"Dots checked":"Checking review...");
        check.setTextColor(checked?MainActivity.ACCENT:MainActivity.FG);
        check.setContentDescription(checked?"All visible arrow dots checked. Uncheck photo review":"Confirm all visible arrow entry points in this photo are correct");
    }
}
