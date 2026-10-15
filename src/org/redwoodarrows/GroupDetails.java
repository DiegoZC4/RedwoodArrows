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
        LinearLayout header=a.row();header.setGravity(Gravity.CENTER_VERTICAL);a.root.removeView(a.heading);
        header.addView(a.button(a.returnToProgress?"Progress":"Gallery",a::backFromGroup),new LinearLayout.LayoutParams(a.dp(80),a.dp(44)));
        a.heading.setTextSize(23);a.heading.setPadding(a.dp(12),0,a.dp(8),0);a.heading.setSingleLine(true);a.heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout identity=new LinearLayout(a);identity.setOrientation(1);identity.addView(a.heading,new LinearLayout.LayoutParams(-1,Math.max(a.dp(40),Math.round(23*a.getResources().getDisplayMetrics().scaledDensity)+a.dp(14))));
        View distance=PracticeUi.inline(a,group,summary.caption(),null);distance.setTag("group-distance");LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-1,-2);dp.setMargins(a.dp(8),0,a.dp(8),0);identity.addView(distance,dp);header.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
        Button more=a.button("More",this::menu);more.setTag("group-more");header.addView(more,new LinearLayout.LayoutParams(a.dp(60),a.dp(44)));a.add(a.root,header);
        ScrollView scroll=new ScrollView(a);LinearLayout body=new LinearLayout(a);body.setOrientation(1);scroll.addView(body);a.root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView title=a.text(summary.caption()+" · "+summary.date(),13,MainActivity.MUTED);title.setMaxLines(2);a.add(body,title);
        if(!group.coordinatesOnly()&&group.keepPhotos){LinearLayout intake=a.row();Button camera=a.button("Take photo",()->a.takePhoto(true));camera.setTag("group-take-photo");camera.setContentDescription("Take another photo of this same unchanged grouping");Button photos=a.button("Add photos",()->a.addPhotos(true));photos.setTag("group-add-photos");photos.setContentDescription("Add camera-roll views to this same unchanged grouping");a.cell(intake,camera);a.cell(intake,photos);a.add(body,intake);}
        Button context=a.button((group.practice.isChecked(group)?"Shots checked":"Shot count & setup")+(group.practice.arrowMeters.isEmpty()?"":" · walk-up"),()->PracticeUi.groupDialog(a,group));context.setTag("group-practice");a.add(body,context);
        TargetCanvas target=new TargetCanvas(a,group);target.setTag("group-detail-plot");
        if(summary.located==0&&summary.photos>0){GroupImages.Preview photo=a.groupImages.new Preview(group,0,GroupImages.ORIGINAL,1024);photo.setTag("group-detail-photo");photo.setOnClickListener(v->a.showGroupImage(group,GroupImages.ORIGINAL,0));body.addView(photo,new LinearLayout.LayoutParams(-1,a.dp(294)));}
        else body.addView(target,new LinearLayout.LayoutParams(-1,a.dp(294)));
        TextView coordinate=a.text(summary.located>0?"Tap a dot for coordinates":summary.photos>0?"Photo saved · arrow analysis is optional":"No positions yet",13,MainActivity.MUTED);coordinate.setGravity(Gravity.CENTER);coordinate.setTag("selected-coordinate");
        target.setArrowListener(id->{for(Store.Result r:summary.results)if(r.id==id)coordinate.setText(String.format(Locale.US,"#%d    x %+.1f    y %+.1f mm",r.id,r.x,r.y));});a.add(body,coordinate);
        LinearLayout stats=a.row();int total=Math.max(summary.results.size(),group.practice.fired);stat(stats,summary.located+(total>summary.located?" / "+total:""),"located");stat(stats,summary.located>1?String.format(Locale.US,"%.0f",summary.spread):"—","spread · mm");stat(stats,""+(group.coordinatesOnly()?group.sourceViewCount:summary.photos),"views");a.add(body,stats);
        Button details=a.button(summary.status()+" · Details",this::details);details.setTag("group-details");details.setTextSize(12);details.setTextColor(MainActivity.MUTED);a.add(body,details);
        if(summary.photos>0){HorizontalScrollView strip=new HorizontalScrollView(a);strip.setHorizontalScrollBarEnabled(false);strip.setTag("group-photo-strip");LinearLayout photos=a.row();
            int number=0;for(int i:PhotoAssignments.photoIndices(group)){final int at=i;PhotoThumb thumbnail=new PhotoThumb(a,new File(a.store.directory(group),group.views.get(i).file));thumbnail.setTag("group-photo:"+i);thumbnail.setContentDescription("Photo "+(++number)+" of "+summary.photos+". Open full screen");thumbnail.setOnClickListener(v->a.showGroupImage(group,GroupImages.ORIGINAL,at));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(a.dp(72),a.dp(86));p.setMargins(0,0,a.dp(8),0);photos.addView(thumbnail,p);}
            strip.addView(photos);a.add(body,strip);
            Button choose=a.button("Assign arrows to targets",()->a.assignTargets(group,Math.max(0,Math.min(a.index,group.views.size()-1))));choose.setTag("group-choose-target");choose.setBackground(a.rounded(MainActivity.ACCENT));choose.setTextColor(MainActivity.BG);a.add(body,choose);
            Button analyze=a.button("Optional multi-view analysis",a::analyzeSweep);analyze.setTag("group-find-arrows");a.add(body,analyze);
        }
        if(!group.keepPhotos&&!group.coordinatesOnly()&&!group.views.isEmpty()){Button finish=a.button("Save 2D grouping",a::confirmCoordinates);finish.setTag("save-coordinates");finish.setBackground(a.rounded(MainActivity.ACCENT));finish.setTextColor(MainActivity.BG);a.add(body,finish);}
    }
    void stat(LinearLayout row,String value,String label){LinearLayout cell=new LinearLayout(a);cell.setOrientation(1);cell.setGravity(Gravity.CENTER);TextView n=a.text(value,23,MainActivity.FG);n.setTypeface(null,1);cell.addView(n);cell.addView(a.text(label,11,MainActivity.MUTED));row.addView(cell,new LinearLayout.LayoutParams(0,-2,1));}
    void menu(){java.util.List<String> actions=new java.util.ArrayList<>(java.util.Arrays.asList("Rename","Change shooter","Export CSV",group.coordinatesOnly()?"Backup 2D data":"Backup with photos"));if(!group.views.isEmpty())actions.add("Edit annotations");new AlertDialog.Builder(a).setTitle("Grouping").setItems(actions.toArray(new String[0]),(d,which)->{if(which==0)a.rename();else if(which==1)a.shooters.chooseForGroup();else if(which==4){a.index=0;a.editor();}else a.export(which==2?"csv":"zip");}).setNegativeButton("Close",null).show();}
    void details(){
        ScrollView scroll=new ScrollView(a);LinearLayout body=new LinearLayout(a);body.setOrientation(1);body.setPadding(a.dp(20),a.dp(12),a.dp(20),a.dp(12));body.setBackgroundColor(MainActivity.BG);scroll.addView(body);
        TextView table=a.text(summary.coordinateTable(),14,MainActivity.FG);table.setTypeface(Typeface.MONOSPACE);table.setTextIsSelectable(true);body.addView(table);
        TextView notes=a.text(summary.details(),13,MainActivity.MUTED);notes.setTextIsSelectable(true);body.addView(notes);
        new AlertDialog.Builder(a).setTitle("Coordinates & notes").setView(scroll).setPositiveButton("Done",null).show();
    }
    /** Thumbnail decoding is bounded; originals remain unchanged. */
    static final class PhotoThumb extends View {
        final File file;final Paint paint=new Paint(3);Bitmap bitmap;
        PhotoThumb(MainActivity a,File file){super(a);this.file=file;setBackground(a.rounded(MainActivity.PANEL));setClipToOutline(true);setClickable(true);setFocusable(true);}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);if(bounds.outWidth>0){BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>320)options.inSampleSize*=2;bitmap=BitmapFactory.decodeFile(file.getAbsolutePath(),options);}invalidate();}
        @Override protected void onDraw(Canvas canvas){if(bitmap!=null){float scale=Math.max(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight()),w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;canvas.drawBitmap(bitmap,null,new RectF((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2),paint);}else{paint.setColor(MainActivity.MUTED);paint.setTextSize(12*getResources().getDisplayMetrics().density);paint.setTextAlign(Paint.Align.CENTER);canvas.drawText("Photo",getWidth()/2f,getHeight()/2f,paint);}}
        @Override protected void onDetachedFromWindow(){if(bitmap!=null){bitmap.recycle();bitmap=null;}super.onDetachedFromWindow();}
    }
}
