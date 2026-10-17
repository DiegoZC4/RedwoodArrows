package org.redwoodarrows;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.MotionEvent;
import java.util.*;
import java.util.function.IntConsumer;
public final class TargetCanvas extends View {
    final Store.Group group;final TargetPreset preset;final List<Store.Result> results;final Paint p=new Paint(3);
    boolean small;int selected=-1;private IntConsumer onArrow;private final List<float[]> dots=new ArrayList<>();private float downX,downY;private boolean dragged;
    public TargetCanvas(Context c,Store.Group g){super(c);group=g;preset=g.preset();results=PhotoDetection.previewResults(g);describe();}
    public TargetCanvas(Context c,TargetPreset target){super(c);group=null;preset=target;results=Collections.emptyList();describe();}
    void describe(){setBackgroundColor(0xFF101C19);setContentDescription(preset.name+". "+preset.dimensions()+". "+(preset.circular()?"Ring geometry preview of one spot.":"Paper outline only; no animal artwork or scoring zones."));}
    TargetCanvas thumbnail(){small=true;setBackgroundColor(Color.TRANSPARENT);return this;}
    void setArrowListener(IntConsumer listener){onArrow=listener;setClickable(true);setFocusable(true);setContentDescription(getContentDescription()+" Tap a dot for its coordinates. Activate to move between arrows.");}
    @Override protected void onDraw(Canvas c){dots.clear();float d=getResources().getDisplayMetrics().density,x=getWidth()/2f,y=getHeight()/2f;double extent=1.12;for(Store.Result r:results)if(Double.isFinite(r.x)&&Double.isFinite(r.y))extent=Math.max(extent,Math.hypot(r.x,r.y)/preset.scaleMm()+.15);float radius=(float)(Math.min(getWidth(),getHeight()-(small?0:22*d))*.49/extent);
        p.setStyle(Paint.Style.FILL);p.setColor(0xFFE8E6DA);
        if(preset.circular()){
            c.drawRect(x-radius*1.04f,y-radius*1.04f,x+radius*1.04f,y+radius*1.04f,p);
            for(int i=0;i<preset.fillRadii.length;i++){p.setColor(preset.fillColors[i]);c.drawCircle(x,y,(float)(radius*preset.fillRadii[i]),p);}
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(1,d*.5f));p.setColor(0xFF8B9188);for(double r:preset.rings)c.drawCircle(x,y,(float)(radius*r),p);
        }else{
            float w=(float)(radius*preset.halfWidth()),h=(float)(radius*preset.halfHeight());c.drawRect(x-w,y-h,x+w,y+h,p);p.setColor(0xFF758073);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d);c.drawRect(x-w,y-h,x+w,y+h,p);c.drawLine(x-8*d,y,x+8*d,y,p);c.drawLine(x,y-8*d,x,y+8*d,p);
        }
        if(group==null&&!small){p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(11*d);p.setColor(0xFFB5C4B7);c.drawText(preset.circular()?(preset.spots>1?"One of "+preset.spots+" spots":"Ring geometry"):"Paper outline only",x,getHeight()-2*d,p);p.setTextAlign(Paint.Align.LEFT);}
        p.setTextSize(11*d);p.setTypeface(Typeface.DEFAULT_BOLD);List<float[]> labels=new ArrayList<>();for(Store.Result r:results)if(Double.isFinite(r.x)&&Double.isFinite(r.y)){float px=x+(float)(r.x/preset.scaleMm())*radius,py=y-(float)(r.y/preset.scaleMm())*radius;dots.add(new float[]{px,py,r.id});boolean uncertain=r.method.contains("neural")&&(r.views<2||!r.warning.isEmpty());p.setStyle(uncertain?Paint.Style.STROKE:Paint.Style.FILL);p.setStrokeWidth(1.5f*d);p.setColor(selected==r.id?0xFFD8EB98:0xFFFFAA65);c.drawCircle(px,py,(selected==r.id?5:small?2.8f:3.5f)*d,p);boolean crowded=false;for(float[] old:labels)if(Math.hypot(px-old[0],py-old[1])<15*d)crowded=true;if(!small&&(selected==r.id||!crowded)){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2*d);p.setColor(0xFF101C19);c.drawText(""+r.id,px+5*d,py-4*d,p);p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);c.drawText(""+r.id,px+5*d,py-4*d,p);labels.add(new float[]{px,py});}}
    }
    @Override public void draw(Canvas canvas){super.draw(canvas);if(group!=null){String status=PhotoDetection.plotStatus(group,results);if(!status.isEmpty()){float d=getResources().getDisplayMetrics().density;p.setStyle(Paint.Style.FILL);p.setColor(0xFFD8EB98);p.setTextSize(10*d);p.setTypeface(Typeface.DEFAULT);p.setTextAlign(Paint.Align.CENTER);canvas.drawText(status,getWidth()/2f,getHeight()-7*d,p);p.setTextAlign(Paint.Align.LEFT);}}}
    @Override public boolean onTouchEvent(MotionEvent e){if(onArrow==null)return super.onTouchEvent(e);switch(e.getActionMasked()){
        case MotionEvent.ACTION_DOWN:downX=e.getX();downY=e.getY();dragged=false;return true;
        case MotionEvent.ACTION_MOVE:dragged|=Math.hypot(e.getX()-downX,e.getY()-downY)>android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();return true;
        case MotionEvent.ACTION_UP:if(!dragged){float[] closest=null;double best=24*getResources().getDisplayMetrics().density;for(float[] dot:dots){double distance=Math.hypot(dot[0]-e.getX(),dot[1]-e.getY());if(distance<best){best=distance;closest=dot;}}if(closest!=null){selected=(int)closest[2];onArrow.accept(selected);invalidate();}}return true;
        case MotionEvent.ACTION_CANCEL:dragged=true;return true;
    }return true;}
    @Override public boolean performClick(){super.performClick();if(onArrow==null||dots.isEmpty())return false;int next=0;for(int i=0;i<dots.size();i++)if((int)dots.get(i)[2]==selected)next=(i+1)%dots.size();selected=(int)dots.get(next)[2];onArrow.accept(selected);invalidate();return true;}
}
