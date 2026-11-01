package org.redwoodarrows;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import java.io.*;
import java.util.*;

/** Android-only touch/render checks on synthetic pixels, never the user's store. */
public final class PhotoCanvasTouchTest {
    static int checks,selectedId=-1,moves,canceled,taps;static long down;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void event(PhotoCanvas view,int action,float x,float y){long now=SystemClock.uptimeMillis();if(action==MotionEvent.ACTION_DOWN)down=now;MotionEvent e=MotionEvent.obtain(down,now,action,x,y,0);view.onTouchEvent(e);e.recycle();}
    static void drag(PhotoCanvas view,float x,float y){event(view,MotionEvent.ACTION_DOWN,x,y);event(view,MotionEvent.ACTION_MOVE,x+80,y+60);event(view,MotionEvent.ACTION_UP,x+80,y+60);}
    static int render(PhotoCanvas view,String name)throws Exception {Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));int amber=0;for(int y=0;y<bitmap.getHeight();y++)for(int x=0;x<bitmap.getWidth();x++)if(bitmap.getPixel(x,y)==PhotoCanvas.SELECTION)amber++;try(OutputStream output=new FileOutputStream(new File(System.getProperty("java.io.tmpdir"),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,output);}bitmap.recycle();return amber;}
    public static void run(Context application)throws Exception {
        Context context=new ContextThemeWrapper(application,android.R.style.Theme_Material);
        Set<Integer> selected=new TreeSet<>();PhotoCanvas view=new PhotoCanvas(context,new PhotoCanvas.Listener(){public void tap(double x,double y){taps++;}public void proposal(double[] line){}public void beforeMove(){}public void moved(){moves++;}public void cancelMove(){canceled++;}public void select(int id){selectedId=id;if(!selected.remove(id))selected.add(id);}});
        Bitmap image=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888);image.eraseColor(0xFFCDD2D6);Store.View frame=new Store.View();frame.width=600;frame.height=800;
        frame.observations.add(new Store.Observation(1,new double[]{160,250},null));frame.observations.add(new Store.Observation(2,new double[]{430,520},null));view.layout(0,0,600,800);view.photo(image,frame);view.selectedIds=selected;view.mode="pick";
        event(view,MotionEvent.ACTION_DOWN,160,250);event(view,MotionEvent.ACTION_UP,160,250);check(selectedId==1&&selected.contains(1),"Tap selects an arrow in pick mode");
        event(view,MotionEvent.ACTION_DOWN,430,520);event(view,MotionEvent.ACTION_UP,430,520);check(selected.equals(new TreeSet<>(Arrays.asList(1,2))),"Canvas supports multi-selection");
        double x=frame.observations.get(0).a[0],y=frame.observations.get(0).a[1];drag(view,160,250);check(frame.observations.get(0).a[0]==x&&frame.observations.get(0).a[1]==y&&moves==0,"Dragging in selection mode pans without changing dots");
        view.fitPhoto();selected.clear();selected.add(1);view.mode="move";drag(view,430,520);check(frame.observations.get(1).a[0]==430&&moves==0,"Move mode cannot drag an unselected arrow");
        view.fitPhoto();drag(view,160,250);check(frame.observations.get(0).a[0]==240&&frame.observations.get(0).a[1]==310&&moves==1,"Explicit Move changes only the selected arrow");
        event(view,MotionEvent.ACTION_DOWN,240,310);event(view,MotionEvent.ACTION_MOVE,300,370);view.cancelGesture();check(frame.observations.get(0).a[0]==240&&frame.observations.get(0).a[1]==310&&canceled==1,"Changing modes cancels an unfinished drag without saving it");
        view.fitPhoto();view.mode="add";drag(view,240,310);check(frame.observations.get(0).a[0]==240&&moves==1,"Add mode never drags an existing dot");
        view.fitPhoto();event(view,MotionEvent.ACTION_DOWN,540,120);event(view,MotionEvent.ACTION_UP,540,120);check(taps==1,"Add mode exposes an explicit empty-space tap");
        view.readOnly=true;event(view,MotionEvent.ACTION_DOWN,520,100);event(view,MotionEvent.ACTION_UP,520,100);check(taps==1,"Saving blocks additions");view.readOnly=false;
        check(!PhotoCanvas.canDragDot("move",1,new TreeSet<>(Arrays.asList(1,2))),"Bulk selection cannot enable implicit group dragging");
        check(PhotoCanvas.canDragDot("hit",1,null),"Legacy annotation drag remains supported");
        view.mode="pick";view.fitPhoto();selected.clear();int plain=render(view,"photo-canvas-unselected.png");selected.add(1);int highlight=render(view,"photo-canvas-selected.png");check(highlight>plain+100,"Selected arrow has a substantial distinct highlight, not just a tiny color change");
        view.layout(0,0,390,520);view.fitPhoto();check(render(view,"photo-canvas-small.png")>100,"Selection remains visible at a smaller portrait viewport");
        view.layout(0,0,900,320);view.fitPhoto();check(render(view,"photo-canvas-wide.png")>100,"Selection remains visible at a wide viewport");
        view.layout(0,0,600,800);view.fitPhoto();frame.observations.add(new Store.Observation(3,new double[]{245,315},null));frame.observations.add(new Store.Observation(4,new double[]{248,319},null));render(view,"photo-canvas-cluster.png");check(view.labelBoxes.size()==4,"Crowded arrows keep all four number labels");
        boolean overlap=false;for(int i=0;i<view.labelBoxes.size();i++)for(int j=i+1;j<view.labelBoxes.size();j++)overlap|=RectF.intersects(view.labelBoxes.get(i),view.labelBoxes.get(j));check(!overlap,"Crowded arrow labels do not overlap");
        view.mode="inner";render(view,"photo-canvas-calibration.png");check(view.labelBoxes.isEmpty()&&frame.observations.size()==4,"Ring alignment hides distracting dot labels without deleting dots");
        image.recycle();System.out.println("Photo canvas touch/render checks passed: "+checks);
    }
}
