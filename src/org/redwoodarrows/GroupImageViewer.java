package org.redwoodarrows;

import android.app.Dialog;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.util.concurrent.Future;

/** Immersive, zoomable inspection. Opening it cannot edit photo marks. */
final class GroupImageViewer extends Dialog {
    final MainActivity a;final Store.Group group;final FrameLayout surface;final Button[] modes=new Button[3];
    final TextView photoNumber;final Button previous,next;int kind,index,generation;Bitmap bitmap;Future<?> pending;boolean closed;
    GroupImageViewer(MainActivity a,Store.Group group,int kind,int index){
        super(a,android.R.style.Theme_Material_NoActionBar_Fullscreen);this.a=a;this.group=group;this.kind=kind;this.index=index;
        LinearLayout root=new LinearLayout(a);root.setOrientation(1);root.setBackgroundColor(MainActivity.BG);root.setPadding(a.dp(8),a.dp(4),a.dp(8),a.dp(6));
        LinearLayout header=a.row();header.setGravity(Gravity.CENTER_VERTICAL);TextView title=a.text(new GroupPresentation(group).caption()+" · "+a.shooters.label(group),15,MainActivity.FG);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);header.addView(title,new LinearLayout.LayoutParams(0,a.dp(48),1));Button close=a.button("Close",this::dismiss);close.setTag("image-close");header.addView(close,new LinearLayout.LayoutParams(a.dp(64),a.dp(44)));root.addView(header);
        LinearLayout tabs=a.row();for(int i=0;i<3;i++){final int choice=i;modes[i]=a.button(GroupImages.LABELS[i],()->{this.kind=choice;showImage();});modes[i].setTag("image-mode:"+i);a.cell(tabs,modes[i]);}root.addView(tabs);
        surface=new FrameLayout(a);surface.setTag("full-image-surface");root.addView(surface,new LinearLayout.LayoutParams(-1,0,1));
        if(!group.views.isEmpty()){Button choose=a.button("Choose target",()->{int selected=index;dismiss();a.chooseTarget(group,selected);});choose.setTag("image-choose-target");root.addView(choose);}
        LinearLayout paging=a.row();previous=a.button("Prev",()->{this.index--;showImage();});previous.setTag("image-prev");next=a.button("Next",()->{this.index++;showImage();});next.setTag("image-next");photoNumber=a.text("",12,MainActivity.MUTED);photoNumber.setGravity(Gravity.CENTER);paging.addView(previous,new LinearLayout.LayoutParams(a.dp(68),a.dp(44)));paging.addView(photoNumber,new LinearLayout.LayoutParams(0,a.dp(44),1));paging.addView(next,new LinearLayout.LayoutParams(a.dp(68),a.dp(44)));paging.setVisibility(group.views.size()>1?View.VISIBLE:View.GONE);root.addView(paging);
        setContentView(root);setOnDismissListener(d->{closed=true;generation++;if(pending!=null)pending.cancel(false);surface.removeAllViews();release();if(a.imageViewer==this)a.imageViewer=null;});
    }
    @Override public void show(){super.show();getWindow().setLayout(-1,-1);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);showImage();}
    void release(){if(bitmap!=null){bitmap.recycle();bitmap=null;}}
    void showImage(){
        final int request=++generation;final int requestedKind=kind,requestedIndex=index;if(pending!=null)pending.cancel(false);surface.removeAllViews();release();
        for(int i=0;i<3;i++){modes[i].setBackground(a.rounded(i==kind?MainActivity.ACCENT:MainActivity.PANEL));modes[i].setTextColor(i==kind?MainActivity.BG:MainActivity.FG);modes[i].setSelected(i==kind);}
        photoNumber.setText((index+1)+" / "+group.views.size());previous.setEnabled(index>0);next.setEnabled(index<group.views.size()-1);
        if(kind==GroupImages.PLOT){TargetCanvas plot=new TargetCanvas(a,group).thumbnail();int size=1536;Bitmap rendered=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);plot.layout(0,0,size,size);plot.draw(new Canvas(rendered));display(new GroupImages.Picture(rendered,""));return;}
        TextView loading=a.text("Loading…",14,MainActivity.MUTED);loading.setGravity(Gravity.CENTER);surface.addView(loading,new FrameLayout.LayoutParams(-1,-1));
        pending=a.worker.submit(()->{GroupImages.Picture picture=a.groupImages.load(group,requestedIndex,requestedKind,requestedKind==GroupImages.WARPED?1536:2048);a.runOnUiThread(()->{if(closed||request!=generation){if(picture.bitmap!=null)picture.bitmap.recycle();return;}display(picture);});});
    }
    void display(GroupImages.Picture picture){
        surface.removeAllViews();bitmap=picture.bitmap;
        if(bitmap==null){TextView empty=a.text(picture.problem.equals("Not aligned")?"This view has no confirmed target alignment.":picture.problem,16,MainActivity.MUTED);empty.setGravity(Gravity.CENTER);surface.addView(empty,new FrameLayout.LayoutParams(-1,-1));return;}
        PhotoCanvas image=new PhotoCanvas(a,null);image.readOnly=true;image.photo(bitmap,new Store.View());image.setTag("full-image");image.setContentDescription(GroupImages.LABELS[kind]+(kind==GroupImages.WARPED?". Cyan outlines show the saved target fit":"")+". Read-only image. Pinch to zoom; drag to pan.");surface.addView(image,new FrameLayout.LayoutParams(-1,-1));
    }
}
