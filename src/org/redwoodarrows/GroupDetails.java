package org.redwoodarrows;

import android.app.AlertDialog;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.*;

/** One visual grouping. Diagnostic prose lives behind Details, never per arrow. */
final class GroupDetails {
    final MainActivity a;final Store.Group group;final GroupPresentation summary;
    GroupDetails(MainActivity activity){a=activity;group=a.group;summary=new GroupPresentation(group);}
    void show(){
        a.shell(a.shooters.label(group)+(summary.provisionalOwner()?" ?":""));
        a.heading.setTag("group-shooter");a.heading.setContentDescription("Shooter: "+a.shooters.label(group)+". Change shooter");a.heading.setOnClickListener(v->{if(!a.busy)a.shooters.chooseForGroup();});
        int shooterColor=a.shooters.color(group.shooter);LinearLayout header=a.row();header.setGravity(Gravity.CENTER_VERTICAL);a.root.removeView(a.heading);
        String back=a.returnToProgress?"Progress":"Gallery";Button backButton=a.button("‹  "+back,a::backFromGroup);backButton.setBackgroundColor(Color.TRANSPARENT);backButton.setTextColor(MainActivity.MUTED);backButton.setTextSize(15);backButton.setPadding(a.dp(2),0,a.dp(14),0);backButton.setContentDescription("Back to "+back);
        header.addView(backButton,new LinearLayout.LayoutParams(-2,a.dp(48)));header.addView(new View(a),new LinearLayout.LayoutParams(0,1,1));
        ImageButton more=HomeUi.icon(a,"ellipsis-vertical","More grouping actions",this::menu);more.setTag("group-more");header.addView(more,new LinearLayout.LayoutParams(a.dp(48),a.dp(48)));a.root.addView(header,new LinearLayout.LayoutParams(-1,-2));
        a.heading.setTextSize(28);a.heading.setTextColor(shooterColor);a.heading.setPadding(0,0,0,0);a.heading.setSingleLine(true);a.heading.setEllipsize(android.text.TextUtils.TruncateAt.END);a.root.addView(a.heading,new LinearLayout.LayoutParams(-1,-2));
        String photoLabel=summary.photos==1?PhotoReviewState.photoLabel(group,group.views.get(PhotoAssignments.photoIndices(group).get(0)).id):summary.caption();
        TextView title=a.text(photoLabel+" · "+summary.date(),13,MainActivity.MUTED);title.setMaxLines(2);title.setPadding(0,a.dp(2),0,a.dp(6));a.root.addView(title,new LinearLayout.LayoutParams(-1,-2));
        View distance=PracticeUi.inline(a,group,summary.caption(),null);distance.setTag("group-distance");LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-1,-2);dp.setMargins(0,0,0,a.dp(8));a.root.addView(distance,dp);
        ScrollView scroll=new ScrollView(a);LinearLayout body=new LinearLayout(a);body.setOrientation(1);scroll.addView(body);a.root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TargetCanvas target=new TargetCanvas(a,group);target.setTag("group-detail-plot");target.dotColor=shooterColor;
        if(summary.located==0&&summary.photos>0){int kind=PhotoDetection.status(group).isEmpty()?GroupImages.ORIGINAL:GroupImages.WARPED;GroupImages.Preview photo=a.groupImages.new Preview(group,0,kind,1024);photo.setTag("group-detail-photo");photo.setOnClickListener(v->a.showGroupImage(group,kind,0));body.addView(photo,new LinearLayout.LayoutParams(-1,a.dp(320)));}
        else body.addView(target,new LinearLayout.LayoutParams(-1,a.dp(320)));
        String detection=PhotoDetection.status(group);TextView coordinate=a.text(summary.located>0?"Tap a dot for coordinates":!detection.isEmpty()?detection:summary.photos>0?"Photo saved · arrow analysis is optional":"No positions yet",13,MainActivity.MUTED);coordinate.setGravity(Gravity.CENTER);coordinate.setTag("selected-coordinate");
        coordinate.setMinHeight(a.dp(40));target.setArrowListener(id->{for(Store.Result r:summary.results)if(r.id==id)coordinate.setText(GroupPresentation.coordinateLabel(r));});body.addView(coordinate,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout stats=a.row();stats.setBackground(a.rounded(MainActivity.PANEL));stats.setPadding(0,a.dp(12),0,a.dp(12));int total=Math.max(summary.results.size(),group.practice.fired),views=group.coordinatesOnly()?group.sourceViewCount:summary.photos;
        stat(stats,summary.located+(total>summary.located?" / "+total:""),"Measured");stat(stats,summary.located>1?String.format(Locale.US,"%.0f mm",summary.spread):"—","Spread");stat(stats,""+views,group.coordinatesOnly()?(views==1?"View":"Views"):(views==1?"Photo":"Photos"));a.add(body,stats);
        PhotoReview review=new PhotoReview(group);
        if(!review.measurementStatus().isEmpty()){TextView pending=a.text(review.measurementStatus(),13,HomeUi.AMBER);pending.setTag("group-measurement-status");a.add(body,pending);}
        if(summary.photos>0){Button choose=a.button(review.reviewed?"Review photo again":"Review photo",()->a.assignTargets(group,Math.max(0,Math.min(a.index,group.views.size()-1))));choose.setTag("group-choose-target");choose.setBackground(ReviewControls.background(a,MainActivity.ACCENT));choose.setTextColor(MainActivity.BG);choose.setTextSize(15);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,a.dp(52));cp.setMargins(0,a.dp(10),0,a.dp(2));body.addView(choose,cp);}
        // A single photo's checkbox already states its review; the text line is for multi-photo groupings.
        TextView reviewStatus=a.text(review.status()+" · "+review.counts(),13,review.reviewed?MainActivity.ACCENT:MainActivity.MUTED);reviewStatus.setTag("group-review-status");a.add(body,reviewStatus);
        CheckBox quick;
        if(summary.photos==1){String photo=group.views.get(PhotoAssignments.photoIndices(group).get(0)).id;quick=QuickReviewUi.checkbox(a,group,photo,false,false,a::results);a.add(body,quick);reviewStatus.setVisibility(View.GONE);}else quick=null;
        if(summary.photos>0){reviewStatus.setText("Checking review... · "+review.counts());a.worker.execute(()->{
            Set<String> checked=TrainingReviews.checkedPhotos(a.store,group);
            a.runOnUiThread(()->{if(!reviewStatus.isAttachedToWindow())return;String status=checked.size()==summary.photos?"Dots checked":checked.isEmpty()?"Needs review":checked.size()+" / "+summary.photos+" photos checked";
                reviewStatus.setText(status+" · "+review.counts());reviewStatus.setTextColor(checked.size()==summary.photos?MainActivity.ACCENT:MainActivity.MUTED);
                if(quick!=null)QuickReviewUi.state(quick,!checked.isEmpty(),true);
            });
        });}
        Button details=a.button(summary.status()+" · Details",this::details);details.setBackground(ReviewControls.outline(a));details.setTag("group-details");details.setTextSize(12);details.setTextColor(MainActivity.MUTED);a.add(body,details);
        if(summary.photos>0){HorizontalScrollView strip=new HorizontalScrollView(a);strip.setHorizontalScrollBarEnabled(false);strip.setTag("group-photo-strip");LinearLayout photos=a.row();
            int number=0;for(int i:PhotoAssignments.photoIndices(group)){final int at=i;PhotoThumb thumbnail=new PhotoThumb(a,new File(a.store.directory(group),group.views.get(i).file));thumbnail.setTag("group-photo:"+i);thumbnail.setContentDescription("Photo "+(++number)+" of "+summary.photos+". Open full screen");thumbnail.setOnClickListener(v->a.showGroupImage(group,GroupImages.ORIGINAL,at));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(a.dp(72),a.dp(86));p.setMargins(0,0,a.dp(8),0);photos.addView(thumbnail,p);}
            strip.addView(photos);a.add(body,strip);
        }
        if(!group.keepPhotos&&!group.coordinatesOnly()&&!group.views.isEmpty()){Button finish=a.button("Save 2D grouping",a::confirmCoordinates);finish.setTag("save-coordinates");finish.setBackground(a.rounded(MainActivity.ACCENT));finish.setTextColor(MainActivity.BG);a.add(body,finish);}
    }
    void stat(LinearLayout row,String value,String label){LinearLayout cell=new LinearLayout(a);cell.setOrientation(1);cell.setGravity(Gravity.CENTER);TextView n=a.text(value,22,MainActivity.FG);n.setTypeface(null,1);n.setPadding(0,0,0,0);n.setGravity(Gravity.CENTER);cell.addView(n);TextView l=a.text(label,12,MainActivity.MUTED);l.setPadding(0,a.dp(2),0,0);l.setGravity(Gravity.CENTER);cell.addView(l);row.addView(cell,new LinearLayout.LayoutParams(0,-2,1));}
    void menu(){
        List<String> actions=new ArrayList<>(Arrays.asList("Shot count & setup","Rename","Change shooter","Export CSV",group.coordinatesOnly()?"Backup 2D data":"Backup with photos"));
        if(!group.coordinatesOnly()&&group.keepPhotos){actions.add("Take another photo");actions.add("Add another photo");}
        if(!group.views.isEmpty()){actions.add("Multi-view analysis");actions.add("Advanced annotations");}
        new AlertDialog.Builder(a).setTitle("Grouping").setItems(actions.toArray(new String[0]),(d,which)->{
            String action=actions.get(which);
            if(action.equals("Shot count & setup"))PracticeUi.groupDialog(a,group);else if(action.equals("Rename"))a.rename();else if(action.equals("Change shooter"))a.shooters.chooseForGroup();
            else if(action.equals("Take another photo"))a.takePhoto(true);else if(action.equals("Add another photo"))a.addPhotos(true);else if(action.equals("Multi-view analysis"))a.analyzeSweep();
            else if(action.equals("Advanced annotations")){a.index=0;a.editor();}else a.export(action.equals("Export CSV")?"csv":"zip");
        }).setNegativeButton("Close",null).show();
    }
    void details(){
        ScrollView scroll=new ScrollView(a);LinearLayout body=new LinearLayout(a);body.setOrientation(1);body.setPadding(a.dp(20),a.dp(12),a.dp(20),a.dp(12));body.setBackgroundColor(MainActivity.BG);scroll.addView(body);
        TextView table=a.text(summary.coordinateTable(),13,MainActivity.FG);table.setTypeface(Typeface.MONOSPACE);table.setTextIsSelectable(true);HorizontalScrollView columns=new HorizontalScrollView(a);columns.addView(table);body.addView(columns);
        TextView notes=a.text(summary.details(),13,MainActivity.MUTED);notes.setTextIsSelectable(true);body.addView(notes);
        new AlertDialog.Builder(a).setTitle("Coordinates & notes").setView(scroll).setPositiveButton("Done",null).show();
    }
    /** Thumbnail decoding is bounded; originals remain unchanged. */
    static final class PhotoThumb extends View {
        final File file;final Paint paint=new Paint(3);Bitmap bitmap;
        PhotoThumb(MainActivity a,File file){super(a);this.file=file;setBackground(a.rounded(MainActivity.PANEL));setClipToOutline(true);setClickable(true);setFocusable(true);}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);if(bounds.outWidth>0){BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>320)options.inSampleSize*=2;bitmap=Store.orientPhoto(BitmapFactory.decodeFile(file.getAbsolutePath(),options),file);}invalidate();}
        @Override protected void onDraw(Canvas canvas){if(bitmap!=null){float scale=Math.max(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight()),w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;canvas.drawBitmap(bitmap,null,new RectF((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2),paint);}else{paint.setColor(MainActivity.MUTED);paint.setTextSize(12*getResources().getDisplayMetrics().density);paint.setTextAlign(Paint.Align.CENTER);canvas.drawText("Photo",getWidth()/2f,getHeight()/2f,paint);}}
        @Override protected void onDetachedFromWindow(){if(bitmap!=null){bitmap.recycle();bitmap=null;}super.onDetachedFromWindow();}
    }
}
