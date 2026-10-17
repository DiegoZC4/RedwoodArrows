package org.redwoodarrows;

import android.graphics.*;
import android.util.LruCache;
import android.view.View;
import java.io.File;
import java.util.concurrent.Future;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

/** Read-only evidence previews. No derived images are written into a grouping. */
final class GroupImages {
    static final int PLOT=0,WARPED=1,ORIGINAL=2;
    static final String[] LABELS={"2D","Detected","Original"};
    static final int FIT_COLOR=0xFF66E3F2;
    static {System.loadLibrary("opencv_java4");}
    static final class Picture {
        final Bitmap bitmap;final String problem;
        Picture(Bitmap b,String p){bitmap=b;problem=p;}
    }
    final MainActivity a;
    final LruCache<String,Picture> thumbs=new LruCache<String,Picture>(8*1024*1024){
        @Override protected int sizeOf(String key,Picture value){return value.bitmap==null?1:value.bitmap.getAllocationByteCount();}
    };
    GroupImages(MainActivity a){this.a=a;}
    static double extent(Store.Group g){double extent=1.12;for(Store.Result r:PhotoDetection.previewResults(g))if(Double.isFinite(r.x)&&Double.isFinite(r.y))extent=Math.max(extent,Math.hypot(r.x,r.y)/g.preset().scaleMm()+.15);return extent;}
    static int reference(Store.Group g){for(int i=0;i<g.views.size();i++){Store.View v=g.views.get(i);if(v.calibration!=null&&v.calibration.reviewed)return i;}for(int i=0;i<g.views.size();i++)if(!g.views.get(i).observations.isEmpty())return i;return 0;}
    static double[] imageToPreview(Store.View v,int decodedWidth,int decodedHeight,int size,double extent){
        return TargetPreview.imageToPreview(previewCalibration(v),v.width,v.height,decodedWidth,decodedHeight,size,extent);
    }
    static Geometry.Calibration previewCalibration(Store.View v){if(!PhotoDetection.proposal(v))return v.calibration;Geometry.Calibration copy=TargetSelection.copy(v.calibration);copy.reviewed=true;return copy;}
    static Bitmap warp(Bitmap source,Store.View v,int size,double extent){
        double[] transform=imageToPreview(v,source.getWidth(),source.getHeight(),size,extent);
        Mat input=new Mat(),output=new Mat(),h=new Mat(3,3,CvType.CV_64F);
        try{Utils.bitmapToMat(source,input);h.put(0,0,transform);Imgproc.warpPerspective(input,output,h,new Size(size,size),Imgproc.INTER_LINEAR,Core.BORDER_CONSTANT,new Scalar(16,28,25,255));Bitmap result=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);try{Utils.matToBitmap(output,result);return result;}catch(RuntimeException error){result.recycle();throw error;}}
        finally{input.release();output.release();h.release();}
    }
    static double[][][] fittedBoundaries(Store.View v,TargetPreset preset,int size,double extent){
        return TargetPreview.fittedBoundaries(previewCalibration(v),v.width,v.height,preset,size,extent);
    }
    static Bitmap warpWithFit(Bitmap source,Store.View v,TargetPreset preset,int size,double extent){
        double[][][] boundaries=fittedBoundaries(v,preset,size,extent);
        Bitmap result=warp(source,v,size,extent);
        try{
            Path path=new Path();for(double[][] boundary:boundaries){for(int i=0;i<boundary.length;i++){float x=(float)boundary[i][0],y=(float)boundary[i][1];if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}path.close();}
            float center=(size-1)/2f,arm=Math.max(3,size*.009f);
            path.moveTo(center-arm,center);path.lineTo(center+arm,center);path.moveTo(center,center-arm);path.lineTo(center,center+arm);
            Canvas canvas=new Canvas(result);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setStyle(Paint.Style.STROKE);paint.setStrokeJoin(Paint.Join.ROUND);
            float stroke=Math.max(1.6f,size*.002f);paint.setStrokeWidth(stroke+Math.max(1.6f,size*.0015f));paint.setColor(0xCC07110F);canvas.drawPath(path,paint);
            paint.setStrokeWidth(stroke);paint.setColor(FIT_COLOR);canvas.drawPath(path,paint);
            dots(canvas,v,size,size,size,extent,true);
            return result;
        }catch(RuntimeException e){result.recycle();throw e;}
    }
    static void dots(Canvas canvas,Store.View view,int width,int height,int size,double extent,boolean warped){
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setStyle(Paint.Style.STROKE);float radius=Math.max(3,Math.min(width,height)*.009f);double center=(size-1)/2.;
        for(Store.Observation o:view.observations)if(!o.line){double x,y;
            if(warped){double[] q=view.calibration.map(o.a[0],o.a[1]);double scale=(size-1)*.49/extent;x=center+q[0]*scale;y=center-q[1]*scale;}else{x=o.a[0]*width/view.width;y=o.a[1]*height/view.height;}
            p.setColor(0xFF101C19);p.setStrokeWidth(Math.max(3,radius*.6f));canvas.drawCircle((float)x,(float)y,radius,p);p.setColor(0xFFFFAA65);p.setStrokeWidth(Math.max(1.6f,radius*.3f));canvas.drawCircle((float)x,(float)y,radius,p);
        }
    }
    Picture load(Store.Group g,int view,int kind,int maxSize){
        if(g.views.isEmpty())return new Picture(null,"No photo");
        Store.View v=g.views.get(Math.min(Math.max(view,0),g.views.size()-1));
        boolean aligned=v.calibration!=null&&(v.calibration.reviewed||PhotoDetection.proposal(v));
        if(kind==WARPED&&!aligned&&v.detectionState.isEmpty()&&v.observations.isEmpty())return new Picture(null,"Not aligned");
        File file=new File(a.store.directory(g),v.file);Bitmap original=null;
        try{
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);
            if(bounds.outWidth<=0||bounds.outHeight<=0)return new Picture(null,"Photo missing");
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
            int limit=kind==WARPED?2048:maxSize;while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>limit)options.inSampleSize*=2;
            original=Store.orientPhoto(BitmapFactory.decodeFile(file.getAbsolutePath(),options),file);if(original==null)return new Picture(null,"Photo unavailable");
            if(kind==WARPED){if(aligned)return new Picture(warpWithFit(original,v,g.preset(),maxSize,extent(g)),PhotoDetection.proposal(v)?"Estimated fit":"");
                Bitmap marked=original.copy(Bitmap.Config.ARGB_8888,true);Canvas canvas=new Canvas(marked);dots(canvas,v,marked.getWidth(),marked.getHeight(),maxSize,1,false);return new Picture(marked,v.detectionState.equals("pending")?"Finding arrows…":v.detectionState.equals("failed")?"Detection unavailable":"Photo-space dots");}
            Bitmap retained=original;original=null;return new Picture(retained,"");
        }catch(RuntimeException e){return new Picture(null,"Preview unavailable");}finally{if(original!=null)original.recycle();}
    }
    String key(Store.Group g,int view,int kind){Store.View v=g.views.isEmpty()?null:g.views.get(view);TargetPreset p=g.preset();return g.id+":"+kind+":"+(v==null?"none":v.id+":"+v.width+"x"+v.height+":"+new File(a.store.directory(g),v.file).lastModified()+":"+(v.calibration==null?"none":java.util.Arrays.toString(v.calibration.h)+v.calibration.reviewed))+":"+extent(g)+(kind==WARPED?":fit:"+java.util.Arrays.toString(p.rings)+":"+p.halfWidth()+":"+p.halfHeight()+":"+g.diskDigest:"");}
    final class Preview extends View {
        final Store.Group group;final int view,kind,maxSize;final Paint paint=new Paint(3);Picture picture;Future<?> pending;
        Preview(Store.Group group,int view,int kind){this(group,view,kind,256);}
        Preview(Store.Group group,int view,int kind,int maxSize){super(a);this.group=group;this.view=view;this.kind=kind;this.maxSize=Math.max(256,Math.min(1024,maxSize));setBackgroundColor(MainActivity.BG);setClickable(true);setFocusable(true);setContentDescription(LABELS[kind]+" image of "+new GroupPresentation(group).caption()+(kind==WARPED?". Arrow dots; cyan outlines show the target fit when available. "+PhotoDetection.status(group):"")+". Open full screen");}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();final String key=key(group,view,kind)+":"+maxSize;picture=thumbs.get(key);if(picture!=null){invalidate();return;}pending=a.worker.submit(()->{Picture loaded=load(group,view,kind,maxSize);thumbs.put(key,loaded);a.runOnUiThread(()->{if(isAttachedToWindow()){picture=loaded;invalidate();}});});}
        @Override protected void onDetachedFromWindow(){if(pending!=null)pending.cancel(false);picture=null;super.onDetachedFromWindow();}
        @Override protected void onDraw(Canvas c){if(picture!=null&&picture.bitmap!=null){Bitmap b=picture.bitmap;float scale=Math.min(getWidth()/(float)b.getWidth(),getHeight()/(float)b.getHeight()),w=b.getWidth()*scale,h=b.getHeight()*scale;c.drawBitmap(b,null,new RectF((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2),paint);if(!picture.problem.isEmpty()){paint.setColor(0xDD101C19);c.drawRect(0,getHeight()-a.dp(22),getWidth(),getHeight(),paint);paint.setColor(MainActivity.ACCENT);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(a.dp(9));c.drawText(picture.problem,getWidth()/2f,getHeight()-a.dp(7),paint);}}else{paint.setColor(MainActivity.MUTED);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(a.dp(10));c.drawText(picture==null?"…":picture.problem,getWidth()/2f,getHeight()/2f,paint);}}
    }
}
