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
        a.heading.setTextSize(23);a.heading.setPadding(a.dp(12),0,a.dp(8),0);a.heading.setSingleLine(true);a.heading.setEllipsize(android.text.TextUtils.TruncateAt.END);header.addView(a.heading,new LinearLayout.LayoutParams(0,a.dp(48),1));
        Button more=a.button("More",this::menu);more.setTag("group-more");header.addView(more,new LinearLayout.LayoutParams(a.dp(60),a.dp(44)));a.add(a.root,header);
        ScrollView scroll=new ScrollView(a);LinearLayout body=new LinearLayout(a);body.setOrientation(1);scroll.addView(body);a.root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView title=a.text(summary.caption()+" · "+summary.date(),13,MainActivity.MUTED);title.setMaxLines(2);a.add(body,title);
        Button context=a.button((group.practice.arrowMeters.isEmpty()?group.practice.label():"Walk-up distances")+" · "+(group.practice.isChecked(group)?"checked":"Check counts"),()->PracticeUi.groupDialog(a,group));context.setTag("group-practice");a.add(body,context);
        TargetCanvas target=new TargetCanvas(a,group);target.setTag("group-detail-plot");body.addView(target,new LinearLayout.LayoutParams(-1,a.dp(294)));
        TextView coordinate=a.text(summary.located>0?"Tap a dot for coordinates":"No positions yet",13,MainActivity.MUTED);coordinate.setGravity(Gravity.CENTER);coordinate.setTag("selected-coordinate");
        target.setArrowListener(id->{for(Store.Result r:summary.results)if(r.id==id)coordinate.setText(String.format(Locale.US,"#%d    x %+.1f    y %+.1f mm",r.id,r.x,r.y));});a.add(body,coordinate);
        LinearLayout stats=a.row();int total=Math.max(summary.results.size(),group.practice.fired);stat(stats,summary.located+(total>summary.located?" / "+total:""),"located");stat(stats,summary.located>1?String.format(Locale.US,"%.0f",summary.spread):"—","spread · mm");stat(stats,""+(group.coordinatesOnly()?group.sourceViewCount:summary.photos),"views");a.add(body,stats);
        Button details=a.button(summary.status()+" · Details",this::details);details.setTag("group-details");details.setTextSize(12);details.setTextColor(MainActivity.MUTED);a.add(body,details);
        if(summary.photos>0){HorizontalScrollView strip=new HorizontalScrollView(a);strip.setHorizontalScrollBarEnabled(false);strip.setTag("group-photo-strip");LinearLayout photos=a.row();
            for(int i=0;i<group.views.size();i++){final int at=i;PhotoThumb thumbnail=new PhotoThumb(a,new File(a.store.directory(group),group.views.get(i).file));thumbnail.setTag("group-photo:"+i);thumbnail.setContentDescription("Photo "+(i+1)+" of "+group.views.size()+". Open full screen");thumbnail.setOnClickListener(v->a.showGroupImage(group,GroupImages.ORIGINAL,at));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(a.dp(72),a.dp(86));p.setMargins(0,0,a.dp(8),0);photos.addView(thumbnail,p);}
            strip.addView(photos);a.add(body,strip);
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
