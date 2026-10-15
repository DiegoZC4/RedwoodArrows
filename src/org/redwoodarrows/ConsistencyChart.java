package org.redwoodarrows;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.text.SimpleDateFormat;
import java.util.*;

/** True chronological x-axis; no zero-fill/interpolation for missing evidence. */
final class ConsistencyChart extends View {
    interface Select {void at(long time);}
    final ConsistencyStats data;final Select callback;final Paint p=new Paint(3);final float density,scaled;
    long selected;float downX,downY;boolean dragging,canceled;final int slop;
    ConsistencyChart(MainActivity a,ConsistencyStats data,long selected,Select callback){super(a);this.data=data;this.selected=selected;this.callback=callback;density=getResources().getDisplayMetrics().density;scaled=getResources().getDisplayMetrics().scaledDensity;slop=ViewConfiguration.get(a).getScaledTouchSlop();setBackground(a.rounded(MainActivity.PANEL));setClickable(true);setFocusable(true);select(selected);}
    void select(long time){selected=time;StringBuilder text=new StringBuilder("Angular grouping consistency, milliradians. Lower is tighter. ");
        if(data.times.isEmpty())text.append("No known-distance measurements.");else{text.append(new SimpleDateFormat("MMM d, yyyy",Locale.US).format(new Date(time))).append(". ");for(ConsistencyStats.Series series:data.series){ConsistencyStats.Sample sample=data.at(series,time);text.append(series.person.label()).append(": ").append(sample==null?"no data":ProgressUi.fmt(sample.spread)+" milliradians, "+sample.located+" located arrows").append(". ");}}
        setContentDescription(text+" Swipe horizontally or use previous/next to inspect time.");invalidate();}
    float left(){return 43*density;}float right(){return Math.max(left()+1,getWidth()-18*density);}float top(){return 32*density;}float bottom(){return Math.max(top()+1,getHeight()-38*density);}
    float x(long time){if(data.times.size()<2)return (left()+right())/2;return left()+(right()-left())*(float)((double)(time-data.times.first())/(data.times.last()-data.times.first()));}
    static double niceStep(double target){double power=Math.pow(10,Math.floor(Math.log10(Math.max(.001,target))));double n=target/power;return(n<=1?1:n<=2?2:n<=2.5?2.5:n<=5?5:10)*power;}
    @Override protected void onDraw(Canvas c){
        double max=1;int finite=0;for(ConsistencyStats.Series series:data.series)for(ConsistencyStats.Sample sample:series.samples)if(Double.isFinite(sample.spread)){max=Math.max(max,sample.spread*1.12);finite++;}
        double step=niceStep(max/4),limit=Math.ceil(max/step)*step;float l=left(),r=right(),t=top(),b=bottom();
        p.setStyle(Paint.Style.FILL);p.setPathEffect(null);p.setTextSize(10*scaled);p.setTextAlign(Paint.Align.RIGHT);
        for(double value=0;value<=limit+step*.01;value+=step){float y=b-(float)(value/limit)*(b-t);p.setColor(0xFF42584C);p.setStrokeWidth(density);c.drawLine(l,y,r,y,p);p.setColor(MainActivity.MUTED);c.drawText(TargetPreset.number(value),l-7*density,y+3*scaled,p);}
        p.setTextAlign(Paint.Align.LEFT);c.drawText("mrad",l,18*density,p);
        if(selected>0){p.setColor(0x887F9587);p.setStrokeWidth(density);c.drawLine(x(selected),t,x(selected),b,p);}
        for(int index=0;index<data.series.size();index++){
            ConsistencyStats.Series series=data.series.get(index);ConsistencyStats.Sample previous=null;
            for(ConsistencyStats.Sample sample:series.samples){
                if(!Double.isFinite(sample.spread)){previous=null;continue;}
                float xx=x(sample.time),yy=b-(float)(sample.spread/limit)*(b-t);p.setColor(ConsistencyUi.COLORS[index%ConsistencyUi.COLORS.length]);p.setStrokeWidth(2*density);
                p.setPathEffect(index%2==0?null:new DashPathEffect(new float[]{7*density,4*density},0));
                if(previous!=null&&previous.rangeKey.equals(sample.rangeKey))c.drawLine(x(previous.time),b-(float)(previous.spread/limit)*(b-t),xx,yy,p);
                p.setPathEffect(null);boolean hollow=sample.usableRounds<3||(data.mode>0&&sample.records<data.mode);float radius=(sample.time==selected?5:3.5f)*density;
                p.setStyle(hollow?Paint.Style.STROKE:Paint.Style.FILL);
                if(index%2==0)c.drawCircle(xx,yy,radius,p);else c.drawRect(xx-radius,yy-radius,xx+radius,yy+radius,p);
                p.setStyle(Paint.Style.FILL);previous=sample;
            }
        }
        p.setPathEffect(null);p.setStyle(Paint.Style.FILL);p.setColor(MainActivity.MUTED);p.setTextSize(11*scaled);
        if(!data.times.isEmpty()){
            boolean oneDay=ConsistencyStats.day(data.times.first())==ConsistencyStats.day(data.times.last());SimpleDateFormat label=new SimpleDateFormat(data.mode!=0&&oneDay?"h:mm a":"MMM d",Locale.US);
            if(data.times.size()==1){p.setTextAlign(Paint.Align.CENTER);c.drawText(label.format(new Date(data.times.first())),(l+r)/2,getHeight()-13*density,p);}
            else{p.setTextAlign(Paint.Align.LEFT);c.drawText(label.format(new Date(data.times.first())),l,getHeight()-13*density,p);p.setTextAlign(Paint.Align.RIGHT);c.drawText(label.format(new Date(data.times.last())),r,getHeight()-13*density,p);}
        }
        if(finite==0){p.setTextAlign(Paint.Align.CENTER);c.drawText("Not enough known-distance positions",(l+r)/2,(t+b)/2,p);}
        else if(data.mode==0&&data.times.size()==1){p.setTextAlign(Paint.Align.RIGHT);p.setTextSize(10*scaled);c.drawText("One labeled day",r,18*density,p);}
        if(isFocused()){p.setStyle(Paint.Style.STROKE);p.setColor(MainActivity.FG);p.setStrokeWidth(density);c.drawRoundRect(density,density,getWidth()-density,getHeight()-density,14*density,14*density,p);p.setStyle(Paint.Style.FILL);}
    }
    void choose(float xx){if(data.times.isEmpty())return;long time=data.times.first();if(data.times.size()>1)time+=(long)(Math.max(0,Math.min(1,(xx-left())/(right()-left())))*(data.times.last()-data.times.first()));callback.at(data.nearest(time));}
    void advance(int direction){List<Long> times=new ArrayList<>(data.times);if(times.isEmpty())return;int i=times.indexOf(selected);callback.at(times.get(Math.max(0,Math.min(times.size()-1,i+direction))));}
    @Override public boolean performClick(){super.performClick();choose(downX);return true;}
    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();dragging=false;canceled=false;return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE){float dx=e.getX()-downX,dy=e.getY()-downY;if(!dragging&&!canceled&&Math.hypot(dx,dy)>slop){if(Math.abs(dx)>Math.abs(dy)*1.2){dragging=true;getParent().requestDisallowInterceptTouchEvent(true);}else canceled=true;}if(dragging)choose(e.getX());return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_UP){if(dragging)choose(e.getX());else if(!canceled)performClick();getParent().requestDisallowInterceptTouchEvent(false);return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){canceled=true;dragging=false;getParent().requestDisallowInterceptTouchEvent(false);return true;}return super.onTouchEvent(e);
    }
    @Override public boolean onKeyDown(int key,KeyEvent e){if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){advance(key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1);return true;}return super.onKeyDown(key,e);}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setScrollable(true);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);}
    @Override public boolean performAccessibilityAction(int action,Bundle args){if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){advance(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?1:-1);return true;}return super.performAccessibilityAction(action,args);}
}
