package org.redwoodarrows;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;

/** Zoomable evidence view. Every displayed mark is in the saved photo's pixels. */
public final class PhotoCanvas extends View {
    public interface Listener {void tap(double x,double y);void proposal(double[] line);void beforeMove();void moved();}
    public Bitmap bitmap;public Store.View frame;public String mode="hit";public int arrow=1;public TargetPreset preset=TargetCatalog.defaultPreset();
    public boolean readOnly=false,unconfirmed=false;
    public List<double[]> outer=new ArrayList<>(),inner=new ArrayList<>(),proposals=new ArrayList<>();public double[] pending;
    private final Paint p=new Paint(3);private final Listener listener;private final ScaleGestureDetector pinch;
    private float zoom=1,panX=0,panY=0,downX,downY,lastX,lastY;private boolean moved,multi,movingMark;private double[] dragged;
    public PhotoCanvas(Context context,Listener listener){super(context);this.listener=listener;setBackgroundColor(0xFF07110F);
        pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){float old=zoom,next=Math.max(1,Math.min(10,zoom*d.getScaleFactor()));float beforeX=originX(),beforeY=originY();panX+=(d.getFocusX()-beforeX)*(1-next/old)+(bitmap.getWidth()*fit()*(next-old)/2);panY+=(d.getFocusY()-beforeY)*(1-next/old)+(bitmap.getHeight()*fit()*(next-old)/2);zoom=next;invalidate();return true;}});
        setContentDescription("Target photo. Pinch to zoom. Tap to mark; drag a marked endpoint to refine. Two fingers move the photo.");
    }
    public void photo(Bitmap bitmap,Store.View frame){this.bitmap=bitmap;this.frame=frame;zoom=1;panX=panY=0;pending=null;proposals.clear();outer.clear();inner.clear();invalidate();}
    float fit(){return bitmap==null?1:Math.min((float)getWidth()/bitmap.getWidth(),(float)getHeight()/bitmap.getHeight());}
    float scale(){return fit()*zoom;}float originX(){return bitmap==null?0:(getWidth()-bitmap.getWidth()*scale())/2+panX;}float originY(){return bitmap==null?0:(getHeight()-bitmap.getHeight()*scale())/2+panY;}
    double[] image(float x,float y){return new double[]{(x-originX())/scale(),(y-originY())/scale()};}
    float sx(double x){return originX()+(float)x*scale();}float sy(double y){return originY()+(float)y*scale();}
    @Override protected void onDraw(Canvas c){super.onDraw(c);if(bitmap==null)return;p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);p.setAlpha(255);c.drawBitmap(bitmap,null,new RectF(originX(),originY(),originX()+bitmap.getWidth()*scale(),originY()+bitmap.getHeight()*scale()),p);
        if(frame.calibration!=null){double[] inv=Geometry.inverse(frame.calibration.h);p.setStrokeWidth(2*getResources().getDisplayMetrics().density);p.setColor(frame.calibration.reviewed&&!unconfirmed?0xEEAEE5C2:0xFFFFCF68);p.setStyle(Paint.Style.STROKE);if(preset.circular()){for(double r:preset.rings){Path path=new Path();for(int i=0;i<=100;i++){double a=i*2*Math.PI/100;double[] q=Geometry.project(inv,r*Math.cos(a),r*Math.sin(a));if(i==0)path.moveTo(sx(q[0]),sy(q[1]));else path.lineTo(sx(q[0]),sy(q[1]));}c.drawPath(path,p);}}else{Path path=new Path();double[][] outline=preset.outline(1);for(int i=0;i<outline.length;i++){double[] q=Geometry.project(inv,outline[i][0],outline[i][1]);if(i==0)path.moveTo(sx(q[0]),sy(q[1]));else path.lineTo(sx(q[0]),sy(q[1]));}path.close();c.drawPath(path,p);}double[] center=Geometry.project(inv,0,0);c.drawLine(sx(center[0])-10,sy(center[1]),sx(center[0])+10,sy(center[1]),p);c.drawLine(sx(center[0]),sy(center[1])-10,sx(center[0]),sy(center[1])+10,p);}
        p.setStyle(Paint.Style.STROKE);p.setColor(0xFFFFA963);p.setStrokeWidth(3);p.setPathEffect(new DashPathEffect(new float[]{12,9},0));for(double[] l:proposals)c.drawLine(sx(l[0]),sy(l[1]),sx(l[2]),sy(l[3]),p);p.setPathEffect(null);
        for(Store.Observation o:frame.observations){int color=o.id==arrow?0xFFD8F18C:0xFF7CDED6;p.setColor(color);p.setStrokeWidth(4);if(o.line)c.drawLine(sx(o.a[0]),sy(o.a[1]),sx(o.b[0]),sy(o.b[1]),p);mark(c,o.a,color,""+o.id);if(o.line)mark(c,o.b,color,"");}
        if(mode.equals("outer")||mode.equals("inner")||mode.equals("corners")){String[] corners={"TL","TR","BR","BL"};for(int i=0;i<outer.size();i++)mark(c,outer.get(i),0xFFFFCE72,mode.equals("corners")?corners[Math.min(i,3)]:i==0?"UP":"");if(!mode.equals("corners"))for(double[] q:inner)mark(c,q,0xFF7FDFEA,"");}if(pending!=null)mark(c,pending,0xFFFFCE72,"1");
    }
    void mark(Canvas c,double[] point,int color,String label){float x=sx(point[0]),y=sy(point[1]),d=getResources().getDisplayMetrics().density;p.setColor(0xDD101C19);p.setStyle(Paint.Style.FILL);c.drawCircle(x,y,7*d,p);p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2*d);c.drawCircle(x,y,7*d,p);c.drawLine(x-11*d,y,x+11*d,y,p);c.drawLine(x,y-11*d,x,y+11*d,p);p.setStyle(Paint.Style.FILL);p.setTextSize(14*d);p.setTypeface(Typeface.DEFAULT_BOLD);c.drawText(label,x+12*d,y-9*d,p);}
    private double distance(double[] q,float x,float y){return Math.hypot(sx(q[0])-x,sy(q[1])-y);}
    @Override public boolean onTouchEvent(MotionEvent e){if(bitmap==null)return false;pinch.onTouchEvent(e);float x=e.getX(),y=e.getY();
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=lastX=x;downY=lastY=y;moved=multi=movingMark=false;dragged=null;double best=24*getResources().getDisplayMetrics().density;
            List<double[]> candidates=new ArrayList<>();if(mode.equals("outer")||mode.equals("corners"))candidates.addAll(outer);else if(mode.equals("inner"))candidates.addAll(inner);else for(Store.Observation o:frame.observations){candidates.add(o.a);if(o.line)candidates.add(o.b);}
            if(!readOnly)for(double[] q:candidates){double d=distance(q,x,y);if(d<best){best=d;dragged=q;}}return true;}
        if(e.getPointerCount()>1){multi=true;dragged=null;if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&!pinch.isInProgress()){float mx=(e.getX(0)+e.getX(1))/2,my=(e.getY(0)+e.getY(1))/2;panX+=mx-lastX;panY+=my-lastY;lastX=mx;lastY=my;invalidate();}else{lastX=(e.getX(0)+e.getX(1))/2;lastY=(e.getY(0)+e.getY(1))/2;}return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&!multi){if(Math.hypot(x-downX,y-downY)>7*getResources().getDisplayMetrics().density)moved=true;
            if(moved&&dragged!=null){if(!movingMark){listener.beforeMove();movingMark=true;for(Store.Observation o:frame.observations)if(o.a==dragged||o.b==dragged){o.source="manual correction";o.confidence=Double.NaN;o.associationWarning="";}}double[] q=image(x,y);dragged[0]=Math.max(0,Math.min(bitmap.getWidth()-1,q[0]));dragged[1]=Math.max(0,Math.min(bitmap.getHeight()-1,q[1]));}
            else if(moved){panX+=x-lastX;panY+=y-lastY;}lastX=x;lastY=y;invalidate();return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_UP){if(movingMark)listener.moved();else if(!moved&&!multi&&dragged==null){double[] q=image(x,y);if(q[0]>=0&&q[1]>=0&&q[0]<bitmap.getWidth()&&q[1]<bitmap.getHeight()){
                if(mode.equals("shaft")&&!proposals.isEmpty()){double best=18*getResources().getDisplayMetrics().density/scale();double[] selected=null;for(double[] l:proposals){double dx=l[2]-l[0],dy=l[3]-l[1],t=((q[0]-l[0])*dx+(q[1]-l[1])*dy)/(dx*dx+dy*dy);t=Math.max(0,Math.min(1,t));double d=Math.hypot(q[0]-l[0]-t*dx,q[1]-l[1]-t*dy);if(d<best){best=d;selected=l;}}if(selected!=null){listener.proposal(selected);invalidate();return true;}}
                if(!readOnly)listener.tap(q[0],q[1]);}}invalidate();performClick();return true;}
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
