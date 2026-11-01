package org.redwoodarrows;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;

/** Zoomable evidence view. Every displayed mark is in the saved photo's pixels. */
public final class PhotoCanvas extends View {
    static final int SELECTION=0xFFFFD166;
    public interface Listener {void tap(double x,double y);void proposal(double[] line);void beforeMove();void moved();default void select(int id){}default void cancelMove(){}}
    public Bitmap bitmap;public Store.View frame;public String mode="hit";public int arrow=1;public TargetPreset preset=TargetCatalog.defaultPreset();
    public boolean readOnly=false,unconfirmed=false;
    public Set<Integer> selectedIds;public Map<Integer,String> targetLabels=new HashMap<>();
    public List<Geometry.Calibration> targetOutlines=new ArrayList<>();
    public int previewTarget=-1;
    public List<double[]> outer=new ArrayList<>(),inner=new ArrayList<>(),proposals=new ArrayList<>();public double[] pending;
    private final Paint p=new Paint(3);private final Listener listener;private final ScaleGestureDetector pinch;
    final List<RectF> labelBoxes=new ArrayList<>(),markerBoxes=new ArrayList<>();
    /** While true (the hold-to-peek button is down) only the photo is drawn; marks and their data are untouched. */
    public boolean hideMarks=false;
    private float zoom=1,panX=0,panY=0,downX,downY,lastX,lastY;private boolean moved,multi,movingMark,touching;private double[] dragged,dragOrigin;private Store.Observation draggedObservation,tappedObservation;
    public PhotoCanvas(Context context,Listener listener){super(context);this.listener=listener;setBackgroundColor(0xFF07110F);
        pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){zoomAt(d.getScaleFactor(),multi?lastX:d.getFocusX(),multi?lastY:d.getFocusY());return true;}});
        // A quick second grab of the same dot is still a dot drag, not Android's
        // double-tap-and-drag zoom shortcut. Zoom has its own pinch/buttons.
        pinch.setQuickScaleEnabled(false);pinch.setStylusScaleEnabled(false);
        setContentDescription("Target photo. Pinch to zoom. Tap to mark; drag a marked endpoint to refine. Two fingers move the photo.");
    }
    public void photo(Bitmap bitmap,Store.View frame){this.bitmap=bitmap;this.frame=frame;zoom=1;panX=panY=0;pending=null;proposals.clear();outer.clear();inner.clear();invalidate();}
    float fit(){return bitmap==null?1:Math.min((float)getWidth()/bitmap.getWidth(),(float)getHeight()/bitmap.getHeight());}
    float scale(){return fit()*zoom;}float originX(){return bitmap==null?0:(getWidth()-bitmap.getWidth()*scale())/2+panX;}float originY(){return bitmap==null?0:(getHeight()-bitmap.getHeight()*scale())/2+panY;}
    double[] image(float x,float y){return new double[]{(x-originX())/scale(),(y-originY())/scale()};}
    float sx(double x){return originX()+(float)x*scale();}float sy(double y){return originY()+(float)y*scale();}
    void zoomAt(float factor,float x,float y){if(bitmap==null)return;float old=zoom,next=Math.max(1,Math.min(10,old*factor));float beforeX=originX(),beforeY=originY();panX+=(x-beforeX)*(1-next/old)+bitmap.getWidth()*fit()*(next-old)/2;panY+=(y-beforeY)*(1-next/old)+bitmap.getHeight()*fit()*(next-old)/2;zoom=next;invalidate();}
    void zoomBy(float factor){cancelMark();zoomAt(factor,getWidth()/2f,getHeight()/2f);}
    void fitPhoto(){cancelMark();zoom=1;panX=panY=0;invalidate();}
    /** View-only: centers image point (x,y) at the given zoom. Marks and image() coordinates follow the same zoom/pan, so placement is unchanged. */
    void center(double x,double y,float level){if(bitmap==null||getWidth()==0)return;cancelMark();zoom=Math.max(1,Math.min(10,level));if(zoom<=1.001){panX=panY=0;invalidate();return;}float s=scale();panX=getWidth()/2f-(float)x*s-(getWidth()-bitmap.getWidth()*s)/2;panY=getHeight()/2f-(float)y*s-(getHeight()-bitmap.getHeight()*s)/2;invalidate();}
    void zoomAround(float factor,double x,double y){center(x,y,zoom*factor);}
    /** Opens on the marked arrows with generous room around them (at most 3x); no marks keeps the whole photo. */
    void frame(List<double[]> points){if(bitmap==null||points.isEmpty()||getWidth()==0)return;double minX=Double.MAX_VALUE,minY=Double.MAX_VALUE,maxX=-Double.MAX_VALUE,maxY=-Double.MAX_VALUE;
        for(double[] q:points){minX=Math.min(minX,q[0]);maxX=Math.max(maxX,q[0]);minY=Math.min(minY,q[1]);maxY=Math.max(maxY,q[1]);}
        double w=Math.max(maxX-minX,bitmap.getWidth()*.15)*1.8,h=Math.max(maxY-minY,bitmap.getHeight()*.15)*1.8;center((minX+maxX)/2,(minY+maxY)/2,(float)Math.min(3,Math.min(getWidth()/(w*fit()),getHeight()/(h*fit()))));
        // The opening view never shows empty space past the photo's edge; later zooms may, to keep a chosen arrow centered.
        float s=scale(),pw=bitmap.getWidth()*s,ph=bitmap.getHeight()*s,ox=originX(),oy=originY();
        if(pw>=getWidth()){if(ox>0)panX-=ox;else if(ox+pw<getWidth())panX+=getWidth()-(ox+pw);}else panX=0;
        if(ph>=getHeight()){if(oy>0)panY-=oy;else if(oy+ph<getHeight())panY+=getHeight()-(oy+ph);}else panY=0;invalidate();}
    void cancelGesture(){cancelMark();touching=false;multi=true;}
    static boolean canDragDot(String mode,int id,Set<Integer> selected){
        if(mode.equals("pick")||mode.equals("target")||mode.equals("add"))return false;
        return !mode.equals("move")||selected!=null&&selected.size()==1&&selected.contains(id);
    }
    boolean editingMark(){return movingMark||touching&&dragged!=null;}
    @Override protected void onDraw(Canvas c){super.onDraw(c);labelBoxes.clear();markerBoxes.clear();if(bitmap==null)return;p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);p.setAlpha(255);c.drawBitmap(bitmap,null,new RectF(originX(),originY(),originX()+bitmap.getWidth()*scale(),originY()+bitmap.getHeight()*scale()),p);
        if(hideMarks)return;
        float density=getResources().getDisplayMetrics().density;
        // Every marker's footprint is known before any label is placed, so labels never sit on another arrow's crosshair.
        if(!mode.equals("outer")&&!mode.equals("inner")&&!mode.equals("corners"))for(Store.Observation o:frame.observations)for(double[] q:o.line?Arrays.asList(o.a,o.b):Collections.singletonList(o.a)){float x=sx(q[0]),y=sy(q[1]);markerBoxes.add(new RectF(x-13*density,y-13*density,x+13*density,y+13*density));}
        // Fitted targets show every scoring ring, so a misaligned fit is visible at a glance. Their center labels are placed after the arrow labels.
        if(mode.equals("target"))for(int n=0;n<targetOutlines.size();n++){
            Geometry.Calibration fit=targetOutlines.get(n);double[] inv=Geometry.inverse(fit.h);p.setStyle(Paint.Style.STROKE);boolean chosen=n==previewTarget;
            List<Double> radii=new ArrayList<>();radii.add(1.0);if(preset!=null&&preset.circular())for(double r:preset.rings)if(r>0&&r<.999)radii.add(r);
            for(double r:radii){p.setColor(chosen?(r==1?Color.WHITE:0xCCFFFFFF):(r==1?0xCCE3F1E8:0x88E3F1E8));p.setStrokeWidth((chosen?(r==1?3:1.5f):(r==1?1.5f:1f))*density);
                Path outline=new Path();for(int i=0;i<=100;i++){double angle=i*Math.PI/50;double[] q=Geometry.project(inv,r*Math.cos(angle),r*Math.sin(angle));if(i==0)outline.moveTo(sx(q[0]),sy(q[1]));else outline.lineTo(sx(q[0]),sy(q[1]));}c.drawPath(outline,p);}
        }
        if(frame.calibration!=null){double[] inv=Geometry.inverse(frame.calibration.h);p.setStrokeWidth(2*getResources().getDisplayMetrics().density);p.setColor(frame.calibration.reviewed&&!unconfirmed?0xEEAEE5C2:0xFFFFCF68);p.setStyle(Paint.Style.STROKE);if(preset.circular()){for(double r:preset.rings){Path path=new Path();for(int i=0;i<=100;i++){double a=i*2*Math.PI/100;double[] q=Geometry.project(inv,r*Math.cos(a),r*Math.sin(a));if(i==0)path.moveTo(sx(q[0]),sy(q[1]));else path.lineTo(sx(q[0]),sy(q[1]));}c.drawPath(path,p);}}else{Path path=new Path();double[][] outline=preset.outline(1);for(int i=0;i<outline.length;i++){double[] q=Geometry.project(inv,outline[i][0],outline[i][1]);if(i==0)path.moveTo(sx(q[0]),sy(q[1]));else path.lineTo(sx(q[0]),sy(q[1]));}path.close();c.drawPath(path,p);}double[] center=Geometry.project(inv,0,0);c.drawLine(sx(center[0])-10,sy(center[1]),sx(center[0])+10,sy(center[1]),p);c.drawLine(sx(center[0]),sy(center[1])-10,sx(center[0]),sy(center[1])+10,p);}
        p.setStyle(Paint.Style.STROKE);p.setColor(0xFFFFA963);p.setStrokeWidth(3);p.setPathEffect(new DashPathEffect(new float[]{12,9},0));for(double[] l:proposals)c.drawLine(sx(l[0]),sy(l[1]),sx(l[2]),sy(l[3]),p);p.setPathEffect(null);
        // Selected marks render last, so a nearby unselected dot cannot cover them.
        if(!mode.equals("outer")&&!mode.equals("inner")&&!mode.equals("corners"))for(int pass=0;pass<2;pass++)for(Store.Observation o:frame.observations){boolean chosen=selectedIds==null?o.id==arrow:selectedIds.contains(o.id);if(chosen!=(pass==1))continue;int color=chosen?SELECTION:0xFF72D9E7;p.setColor(color);p.setStrokeWidth(4);if(o.line)c.drawLine(sx(o.a[0]),sy(o.a[1]),sx(o.b[0]),sy(o.b[1]),p);mark(c,o.a,color,"#"+o.id+(targetLabels.containsKey(o.id)?"·"+targetLabels.get(o.id):""),chosen,"#"+o.id+"·W");if(o.line)mark(c,o.b,color,"",chosen);}
        if(mode.equals("target"))for(int n=0;n<targetOutlines.size();n++){Geometry.Calibration fit=targetOutlines.get(n);double[] center=Geometry.project(Geometry.inverse(fit.h),0,0);mark(c,center,n==previewTarget?Color.WHITE:0xFFE3F1E8,fit.reviewLabel.isEmpty()?PhotoReviewState.name(n):fit.reviewLabel,n==previewTarget);}
        if(mode.equals("outer")||mode.equals("inner")||mode.equals("corners")){String[] corners={"TL","TR","BR","BL"};for(int i=0;i<outer.size();i++)mark(c,outer.get(i),0xFFFFCE72,mode.equals("corners")?corners[Math.min(i,3)]:i==0?"UP":"");if(!mode.equals("corners"))for(double[] q:inner)mark(c,q,0xFF7FDFEA,"");}if(pending!=null)mark(c,pending,0xFFFFCE72,"1");
        if(movingMark&&dragged!=null&&!readOnly)magnifier(c);
    }
    /** Raw pixels plus a hollow reticle, away from the finger. No image copies. */
    private void magnifier(Canvas c){float d=getResources().getDisplayMetrics().density,size=Math.min(128*d,Math.min(getWidth()-24*d,getHeight()-24*d));if(size<48*d)return;
        float left=downX<getWidth()/2f?getWidth()-size-12*d:12*d,top=12*d,cx=left+size/2,cy=top+size/2;
        RectF box=new RectF(left,top,left+size,top+size);Path clip=new Path();clip.addRoundRect(box,12*d,12*d,Path.Direction.CW);c.save();c.clipPath(clip);c.drawColor(0xFF07110F);
        float s=Math.max(scale()*2.5f,2*d),x=cx-(float)dragged[0]*s,y=cy-(float)dragged[1]*s;p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);p.setAlpha(255);c.drawBitmap(bitmap,null,new RectF(x,y,x+bitmap.getWidth()*s,y+bitmap.getHeight()*s),p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3*d);p.setColor(0xCC07110F);reticle(c,cx,cy,d);p.setStrokeWidth(1.2f*d);p.setColor(0xFFD8F18C);reticle(c,cx,cy,d);c.restore();
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2*d);p.setColor(0xFFD8F18C);c.drawRoundRect(box,12*d,12*d,p);
    }
    private void reticle(Canvas c,float x,float y,float d){c.drawCircle(x,y,3*d,p);c.drawLine(x-18*d,y,x-6*d,y,p);c.drawLine(x+6*d,y,x+18*d,y,p);c.drawLine(x,y-18*d,x,y-6*d,p);c.drawLine(x,y+6*d,x,y+18*d,p);}
    void mark(Canvas c,double[] point,int color,String label){mark(c,point,color,label,false);}
    void mark(Canvas c,double[] point,int color,String label,boolean selected){mark(c,point,color,label,selected,label);}
    /** sizing is the text used to choose the label's spot, so a label lands in the same place on the Dots and Targets tabs even when its text differs. */
    void mark(Canvas c,double[] point,int color,String label,boolean selected,String sizing){
        float x=sx(point[0]),y=sy(point[1]),d=getResources().getDisplayMetrics().density;if(x<-16*d||y<-16*d||x>getWidth()+16*d||y>getHeight()+16*d)return;
        p.setStyle(Paint.Style.STROKE);p.setColor(0xEE07110F);p.setStrokeWidth(4*d);c.drawCircle(x,y,6*d,p);if(selected)c.drawCircle(x,y,12*d,p);
        p.setColor(color);p.setStrokeWidth(2*d);c.drawCircle(x,y,6*d,p);if(selected)c.drawCircle(x,y,12*d,p);
        c.drawLine(x-11*d,y,x-8*d,y,p);c.drawLine(x+8*d,y,x+11*d,y,p);c.drawLine(x,y-11*d,x,y-8*d,p);c.drawLine(x,y+8*d,x,y+11*d,p);
        if(label.isEmpty())return;p.setTextSize(12*getResources().getDisplayMetrics().scaledDensity);p.setTypeface(Typeface.DEFAULT_BOLD);
        Paint.FontMetrics fm=p.getFontMetrics();float actual=p.measureText(label)+8*d,width=Math.max(actual,p.measureText(sizing)+8*d),height=fm.descent-fm.ascent+4*d;
        RectF box=null;boolean leftSide=false;
        for(int attempt=0;attempt<80;attempt++){int row=attempt/4;boolean right=attempt%2==0,above=attempt%4<2;
            float bx=right?x+14*d:x-width-14*d,by=above?y-height-5*d-row*(height+4*d):y+5*d+row*(height+4*d);
            float left=Math.max(2*d,Math.min(getWidth()-width-2*d,bx)),top=Math.max(2*d,Math.min(getHeight()-height-2*d,by));RectF candidate=new RectF(left,top,left+width,top+height);boolean overlaps=false;
            for(RectF used:labelBoxes)if(candidate.left<used.right+3*d&&candidate.right>used.left-3*d&&candidate.top<used.bottom+3*d&&candidate.bottom>used.top-3*d){overlaps=true;break;}
            // Other arrows' crosshairs are off limits too (a marker centered on this point is this arrow's own).
            if(!overlaps)for(RectF other:markerBoxes)if(Math.abs(other.centerX()-x)>1||Math.abs(other.centerY()-y)>1)if(RectF.intersects(candidate,other)){overlaps=true;break;}
            if(!overlaps){box=candidate;leftSide=!right;break;}
        }
        if(box==null)return;labelBoxes.add(box);float left=leftSide?box.right-actual:box.left,top=box.top;width=actual;
        p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f*d);float bx=Math.max(left,Math.min(left+width,x)),by=Math.max(box.top,Math.min(box.bottom,y));float length=(float)Math.hypot(bx-x,by-y);
        // A connector whenever the label is not touching its own crosshair.
        if(length>15*d)c.drawLine(x+(bx-x)*13*d/length,y+(by-y)*13*d/length,bx,by,p);
        p.setStyle(Paint.Style.FILL);p.setColor(selected?color:0xEA07110F);c.drawRoundRect(left,top,left+width,top+height,3*d,3*d,p);
        p.setColor(selected?0xFF07110F:Color.WHITE);c.drawText(label,left+4*d,top+2*d-fm.ascent,p);
    }
    private double distance(double[] q,float x,float y){return Math.hypot(sx(q[0])-x,sy(q[1])-y);}
    @Override public boolean onTouchEvent(MotionEvent e){if(bitmap==null)return false;pinch.onTouchEvent(e);float x=e.getX(),y=e.getY();
        if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)touching=false;
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){touching=true;downX=lastX=x;downY=lastY=y;moved=multi=movingMark=false;dragged=dragOrigin=null;draggedObservation=tappedObservation=null;double best=28*getResources().getDisplayMetrics().density;
            if(!readOnly&&!mode.equals("target")){
                if(mode.equals("outer")||mode.equals("corners")||mode.equals("inner")){for(double[] q:mode.equals("inner")?inner:outer){double delta=distance(q,x,y);if(delta<best){best=delta;dragged=q;}}}
                else for(Store.Observation o:frame.observations)for(double[] q:o.line?Arrays.asList(o.a,o.b):Collections.singletonList(o.a)){double delta=distance(q,x,y);if(delta<best){best=delta;tappedObservation=o;boolean editable=canDragDot(mode,o.id,selectedIds);dragged=editable?q:null;draggedObservation=editable?o:null;}}
            }return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){cancelMark();multi=true;return true;}
        if(e.getPointerCount()>1){cancelMark();multi=true;if(e.getActionMasked()==MotionEvent.ACTION_MOVE){float mx=(e.getX(0)+e.getX(1))/2,my=(e.getY(0)+e.getY(1))/2;panX+=mx-lastX;panY+=my-lastY;lastX=mx;lastY=my;invalidate();}else{lastX=(e.getX(0)+e.getX(1))/2;lastY=(e.getY(0)+e.getY(1))/2;}return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&!multi){if(Math.hypot(x-downX,y-downY)>7*getResources().getDisplayMetrics().density)moved=true;
            if(moved&&dragged!=null){if(!movingMark){listener.beforeMove();dragOrigin=dragged.clone();movingMark=true;performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);}
                // Keep the initial finger offset: grabbing near a small dot must
                // not snap its center underneath the finger when dragging starts.
                dragged[0]=Math.max(0,Math.min(bitmap.getWidth()-1,dragOrigin[0]+(x-downX)/scale()));dragged[1]=Math.max(0,Math.min(bitmap.getHeight()-1,dragOrigin[1]+(y-downY)/scale()));}
            else if(moved){panX+=x-lastX;panY+=y-lastY;}lastX=x;lastY=y;invalidate();return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_UP){if(movingMark){if(Math.hypot(dragged[0]-dragOrigin[0],dragged[1]-dragOrigin[1])<1e-6)cancelMark();else{if(draggedObservation!=null){draggedObservation.source="manual correction";draggedObservation.confidence=Double.NaN;}movingMark=false;listener.moved();}}
            else if(!moved&&!multi&&!readOnly&&tappedObservation!=null&&(mode.equals("select")||mode.equals("pick")||mode.equals("move")||mode.equals("add")))listener.select(tappedObservation.id);
            else if(!moved&&!multi&&(dragged==null||((mode.equals("outer")||mode.equals("inner")||mode.equals("corners"))&&distance(dragged,x,y)>8*getResources().getDisplayMetrics().density))){double[] q=image(x,y);if(q[0]>=0&&q[1]>=0&&q[0]<bitmap.getWidth()&&q[1]<bitmap.getHeight()){
                if(!readOnly&&mode.equals("shaft")&&!proposals.isEmpty()){double best=18*getResources().getDisplayMetrics().density/scale();double[] selected=null;for(double[] l:proposals){double dx=l[2]-l[0],dy=l[3]-l[1],t=((q[0]-l[0])*dx+(q[1]-l[1])*dy)/(dx*dx+dy*dy);t=Math.max(0,Math.min(1,t));double d=Math.hypot(q[0]-l[0]-t*dx,q[1]-l[1]-t*dy);if(d<best){best=d;selected=l;}}if(selected!=null){listener.proposal(selected);invalidate();return true;}}
                if(!readOnly&&(mode.equals("select")||mode.equals("pick")||mode.equals("move"))){double best=28*getResources().getDisplayMetrics().density;int chosen=-1;for(Store.Observation o:frame.observations){double d=distance(o.a,x,y);if(d<best){best=d;chosen=o.id;}}if(chosen>=0)listener.select(chosen);}
                else if(!readOnly)listener.tap(q[0],q[1]);}}invalidate();performClick();return true;}
        return true;
    }
    private void cancelMark(){boolean started=movingMark;if(started&&dragged!=null&&dragOrigin!=null){dragged[0]=dragOrigin[0];dragged[1]=dragOrigin[1];}dragged=dragOrigin=null;draggedObservation=tappedObservation=null;movingMark=false;if(started)listener.cancelMove();invalidate();}
    @Override public boolean performClick(){super.performClick();return true;}
}
