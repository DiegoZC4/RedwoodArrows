package org.redwoodarrows;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.MotionEvent;
import java.util.*;
import java.util.function.IntConsumer;
public final class TargetCanvas extends View {
    final Store.Group group;final TargetPreset preset;final List<Store.Result> results;final Paint p=new Paint(3);
    boolean small,mappedOnly;int selected=-1,dotColor=0xFFFFAA65;private IntConsumer onArrow;private final List<float[]> dots=new ArrayList<>();private float downX,downY;private boolean dragged;
    public TargetCanvas(Context c,Store.Group g){this(c,g,PhotoDetection.previewResults(g));}
    private TargetCanvas(Context c,Store.Group g,List<Store.Result> mapped){super(c);group=g;preset=g.preset();results=mapped;describe();}
    static TargetCanvas mappedThumbnail(Context c,Store.Group g,int color){TargetCanvas map=new TargetCanvas(c,g,Store.results(g));map.mappedOnly=true;map.dotColor=color;return map.thumbnail();}
    public TargetCanvas(Context c,TargetPreset target){super(c);group=null;preset=target;results=Collections.emptyList();describe();}
    void describe(){setBackgroundColor(0xFF101C19);setContentDescription(preset.name+". "+preset.dimensions()+". "+(preset.circular()?"Ring geometry preview of one spot.":"Paper outline only; no animal artwork or scoring zones."));}
    TargetCanvas thumbnail(){small=true;setBackgroundColor(Color.TRANSPARENT);return this;}
    void setArrowListener(IntConsumer listener){onArrow=listener;setClickable(true);setFocusable(true);setContentDescription(getContentDescription()+" Tap a dot for its coordinates. Activate to move between arrows.");}
    @Override protected void onDraw(Canvas c){dots.clear();float d=getResources().getDisplayMetrics().density,x=getWidth()/2f,y=getHeight()/2f;
        if(mappedOnly&&results.stream().noneMatch(r->Double.isFinite(r.x)&&Double.isFinite(r.y))){p.setStyle(Paint.Style.FILL);p.setColor(MainActivity.MUTED);p.setTextSize(13*getResources().getDisplayMetrics().scaledDensity);p.setTextAlign(Paint.Align.CENTER);c.drawText("Not mapped",x,y,p);p.setTextAlign(Paint.Align.LEFT);return;}
        double extent=1.12;for(Store.Result r:results)if(Double.isFinite(r.x)&&Double.isFinite(r.y))extent=Math.max(extent,Math.hypot(r.x,r.y)/preset.scaleMm()+.15);float radius=(float)(Math.min(getWidth(),getHeight()-(small?0:22*d))*.49/extent);
        p.setStyle(Paint.Style.FILL);p.setColor(0xFFE8E6DA);
        if(preset.circular()){
            // Gallery maps skip the bright paper square: on the dark list it out-shone the shooter's dots. A faint edge keeps the face's outline.
            if(!mappedOnly)c.drawRect(x-radius*1.04f,y-radius*1.04f,x+radius*1.04f,y+radius*1.04f,p);
            for(int i=0;i<preset.fillRadii.length;i++){p.setColor(preset.fillColors[i]);c.drawCircle(x,y,(float)(radius*preset.fillRadii[i]),p);}
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(1,d*.5f));p.setColor(0xFF8B9188);for(double r:preset.rings)c.drawCircle(x,y,(float)(radius*r),p);
            if(mappedOnly){p.setColor(0x66E3F1E8);p.setStrokeWidth(Math.max(1,d));c.drawCircle(x,y,radius,p);}
        }else{
            float w=(float)(radius*preset.halfWidth()),h=(float)(radius*preset.halfHeight());c.drawRect(x-w,y-h,x+w,y+h,p);p.setColor(0xFF758073);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d);c.drawRect(x-w,y-h,x+w,y+h,p);c.drawLine(x-8*d,y,x+8*d,y,p);c.drawLine(x,y-8*d,x,y+8*d,p);
        }
        if(group==null&&!small){p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(11*d);p.setColor(0xFFB5C4B7);c.drawText(preset.circular()?(preset.spots>1?"One of "+preset.spots+" spots":"Ring geometry"):"Paper outline only",x,getHeight()-2*d,p);p.setTextAlign(Paint.Align.LEFT);}
        List<Store.Result> shown=new ArrayList<>();for(Store.Result r:results)if(Double.isFinite(r.x)&&Double.isFinite(r.y)){dots.add(new float[]{x+(float)(r.x/preset.scaleMm())*radius,y-(float)(r.y/preset.scaleMm())*radius,r.id});shown.add(r);}
        for(int i=0;i<dots.size();i++){Store.Result r=shown.get(i);float px=dots.get(i)[0],py=dots.get(i)[1];boolean chosen=selected==r.id,uncertain=r.method.contains("neural")&&(r.views<2||!r.warning.isEmpty());
            float dotRadius=(chosen?(small?5:7):small?3.4f:5)*d;
            if(mappedOnly||!small){p.setStyle(Paint.Style.FILL);p.setColor(MainActivity.BG);c.drawCircle(px,py,dotRadius+(small?1:1.5f)*d,p);}
            p.setStyle(uncertain?Paint.Style.STROKE:Paint.Style.FILL);p.setStrokeWidth(1.5f*d);p.setColor(chosen&&small?MainActivity.ACCENT:dotColor);c.drawCircle(px,py,dotRadius,p);
            if(chosen&&!small){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2*d);p.setColor(Color.WHITE);c.drawCircle(px,py,dotRadius+3*d,p);}
        }
        if(!small)labels(c,shown,x,y,d);
    }
    /** Numbered pills placed outward from the bull, around every dot and earlier label; display only. */
    private void labels(Canvas c,List<Store.Result> shown,float cx,float cy,float d){
        p.setTextSize(11*d);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextAlign(Paint.Align.CENTER);List<RectF> placed=new ArrayList<>();
        List<Integer> order=new ArrayList<>();for(int i=0;i<shown.size();i++)if(shown.get(i).id==selected)order.add(0,i);else order.add(i);
        for(int i:order){Store.Result r=shown.get(i);float px=dots.get(i)[0],py=dots.get(i)[1];boolean chosen=r.id==selected;String label=""+r.id;
            float w=Math.max(p.measureText(label)+10*d,18*d),h=16*d,clear=(chosen?10:6.5f)*d;double outward=Math.hypot(px-cx,py-cy)<6*d?-Math.PI/4:Math.atan2(py-cy,px-cx);RectF spot=null,first=null;
            for(int ring=0;ring<2&&spot==null;ring++)for(int k=0;k<8&&spot==null;k++){double angle=outward+(k%2==0?-1:1)*((k+1)/2)*Math.PI/4;float reach=clear+(2+ring*9)*d+Math.max(w,h)/2,lx=px+(float)Math.cos(angle)*reach,ly=py+(float)Math.sin(angle)*reach;
                RectF rect=new RectF(lx-w/2,ly-h/2,lx+w/2,ly+h/2);if(first==null)first=rect;if(fits(rect,placed,d))spot=rect;}
            if(spot==null&&chosen)spot=first;if(spot==null)continue;placed.add(spot);
            p.setStyle(Paint.Style.FILL);p.setColor(0xE6101C19);c.drawRoundRect(spot,h/2,h/2,p);p.setColor(chosen?Color.WHITE:MainActivity.FG);c.drawText(label,spot.centerX(),spot.centerY()-(p.descent()+p.ascent())/2,p);
        }
        p.setTextAlign(Paint.Align.LEFT);
    }
    private boolean fits(RectF rect,List<RectF> placed,float d){
        if(rect.left<0||rect.top<0||rect.right>getWidth()||rect.bottom>getHeight())return false;
        for(RectF other:placed)if(RectF.intersects(rect,other))return false;
        for(float[] dot:dots){float nx=Math.max(rect.left,Math.min(dot[0],rect.right)),ny=Math.max(rect.top,Math.min(dot[1],rect.bottom));if(Math.hypot(dot[0]-nx,dot[1]-ny)<7*d)return false;}
        return true;
    }
    @Override public void draw(Canvas canvas){super.draw(canvas);if(group!=null&&!mappedOnly){String status=PhotoDetection.plotStatus(group,results);if(!status.isEmpty()){float d=getResources().getDisplayMetrics().density;p.setStyle(Paint.Style.FILL);p.setColor(MainActivity.ACCENT);p.setTextSize(10*d);p.setTypeface(Typeface.DEFAULT);p.setTextAlign(Paint.Align.CENTER);canvas.drawText(status,getWidth()/2f,getHeight()-7*d,p);p.setTextAlign(Paint.Align.LEFT);}}}
    @Override public boolean onTouchEvent(MotionEvent e){if(onArrow==null)return super.onTouchEvent(e);switch(e.getActionMasked()){
        case MotionEvent.ACTION_DOWN:downX=e.getX();downY=e.getY();dragged=false;return true;
        case MotionEvent.ACTION_MOVE:dragged|=Math.hypot(e.getX()-downX,e.getY()-downY)>android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();return true;
        case MotionEvent.ACTION_UP:if(!dragged){float[] closest=null;double best=24*getResources().getDisplayMetrics().density;for(float[] dot:dots){double distance=Math.hypot(dot[0]-e.getX(),dot[1]-e.getY());if(distance<best){best=distance;closest=dot;}}if(closest!=null){selected=(int)closest[2];onArrow.accept(selected);invalidate();}}return true;
        case MotionEvent.ACTION_CANCEL:dragged=true;return true;
    }return true;}
    @Override public boolean performClick(){super.performClick();if(onArrow==null||dots.isEmpty())return false;int next=0;for(int i=0;i<dots.size();i++)if((int)dots.get(i)[2]==selected)next=(i+1)%dots.size();selected=(int)dots.get(next)[2];onArrow.accept(selected);invalidate();return true;}
}
