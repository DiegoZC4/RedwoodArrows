package org.redwoodarrows;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.text.SimpleDateFormat;
import java.util.*;

/** True chronological x-axis; gap connectors never create samples. */
final class ConsistencyChart extends View {
    interface Select {void at(long time);}
    final MainActivity a;final ConsistencyStats data;final Select callback;final Paint p=new Paint(3);final float density,scaled;
    long selected;float downX,downY;boolean dragging,canceled,showTrend;final int slop;
    ConsistencyChart(MainActivity a,ConsistencyStats data,long selected,Select callback){super(a);this.a=a;this.data=data;this.selected=selected;this.callback=callback;density=getResources().getDisplayMetrics().density;scaled=getResources().getDisplayMetrics().scaledDensity;slop=ViewConfiguration.get(a).getScaledTouchSlop();setBackground(a.rounded(MainActivity.PANEL));setClickable(true);setFocusable(true);select(selected);}
    void select(long time){selected=time;StringBuilder text=new StringBuilder("Angular grouping consistency, milliradians. Lower is tighter. ");
        if(data.times.isEmpty())text.append("No known-distance measurements.");else{text.append(new SimpleDateFormat("MMM d, yyyy",Locale.US).format(new Date(time))).append(". ");for(ConsistencyStats.Series series:data.series){ConsistencyStats.Sample sample=data.at(series,time);text.append(series.person.label()).append(": ").append(sample==null?"no data":ProgressUi.fmt(sample.spread)+" milliradians, "+sample.located+" measured arrows. "+ConsistencyUi.intervalLabel(sample)).append(". ");}}
        if(showTrend)text.append("100-arrow rolling trend shown; windows overlap. ");
        LabelCoverage.Day coverage=data.labelDays.get(time);if(coverage!=null&&!coverage.pending.isEmpty())text.append(coverage.summary()).append(". No angular value invented. ");
        setContentDescription(text+" Dashed connections mark a gap or changed range, not a measurement on a missing date. Swipe horizontally or use previous/next to inspect time.");invalidate();}
    void showTrend(boolean value){showTrend=value;select(selected);}
    float left(){return 43*density;}float right(){return Math.max(left()+1,getWidth()-18*density);}float top(){return 32*density;}float bottom(){return Math.max(top()+1,getHeight()-38*density);}
    float plotLeft(){return left()+10*density;}float plotRight(){return right()-10*density;}
    float x(long time){if(data.times.size()<2)return (left()+right())/2;return plotLeft()+(plotRight()-plotLeft())*(float)((double)(time-data.times.first())/(data.times.last()-data.times.first()));}
    double scaleMaximum(){double max=1;for(ConsistencyStats.Series series:data.series){for(ConsistencyStats.Sample sample:series.samples){if(Double.isFinite(sample.spread))max=Math.max(max,sample.spread);if(sample.interval.available())max=Math.max(max,sample.interval.upper);}if(showTrend)for(ConsistencyStats.Sample sample:series.trend)if(ConsistencyStats.trendReady(sample))max=Math.max(max,sample.spread);}return max*1.12;}
    /** Lowest plotted value, including whisker bottoms and the trend, for a tighter axis; 0 when nothing is plotted. */
    double scaleMinimum(){double min=Double.MAX_VALUE;for(ConsistencyStats.Series series:data.series){for(ConsistencyStats.Sample sample:series.samples){if(Double.isFinite(sample.spread))min=Math.min(min,sample.spread);if(sample.interval.available())min=Math.min(min,sample.interval.lower);}if(showTrend)for(ConsistencyStats.Sample sample:series.trend)if(ConsistencyStats.trendReady(sample))min=Math.min(min,sample.spread);}return min==Double.MAX_VALUE?0:min;}
    float y(double value,double floor,double limit,float t,float b){return b-(float)((value-floor)/(limit-floor))*(b-t);}
    static double niceStep(double target){double power=Math.pow(10,Math.floor(Math.log10(Math.max(.001,target))));double n=target/power;return(n<=1?1:n<=2?2:n<=2.5?2.5:n<=5?5:10)*power;}
    @Override protected void onDraw(Canvas c){
        double max=scaleMaximum();int finite=0;for(ConsistencyStats.Series series:data.series)for(ConsistencyStats.Sample sample:series.samples)if(Double.isFinite(sample.spread))finite++;
        // Tighter axis: labeled round gridlines just outside the plotted range (whiskers included), not from 0.
        double low=scaleMinimum(),step=niceStep(Math.max(max/1.12-low,1)/4),limit=Math.ceil(max/step)*step,floor=Math.max(0,Math.floor(low*.95/step)*step);if(limit-floor<step)limit=floor+step;float l=left(),r=right(),t=top(),b=bottom();
        p.setStyle(Paint.Style.FILL);p.setPathEffect(null);p.setTextSize(10*scaled);p.setTextAlign(Paint.Align.RIGHT);
        for(double value=floor;value<=limit+step*.01;value+=step){float y=y(value,floor,limit,t,b);p.setColor(0xFF42584C);p.setStrokeWidth(density);c.drawLine(l,y,r,y,p);p.setColor(MainActivity.MUTED);c.drawText(TargetPreset.number(value),l-7*density,y+3*scaled,p);}
        p.setTextAlign(Paint.Align.LEFT);c.drawText("mrad",l,18*density,p);
        if(selected>0){p.setColor(0x887F9587);p.setStrokeWidth(density);c.drawLine(x(selected),t,x(selected),b,p);}
        for(int index=0;index<data.series.size();index++){
            ConsistencyStats.Series series=data.series.get(index);ConsistencyStats.Sample previous=null;
            for(ConsistencyStats.Sample sample:showTrend?series.trend:series.samples){
                if(showTrend?!ConsistencyStats.trendReady(sample):!Double.isFinite(sample.spread))continue;
                p.setColor(a.shooters.color(series.person));p.setStrokeWidth(2.5f*density);p.setStrokeCap(Paint.Cap.ROUND);
                float dodge=showTrend||data.series.size()<2?0:(index%2==0?-3:3)*density;
                if(previous!=null){p.setPathEffect(ConsistencyStats.dashedLink(previous,sample,data.times)?new DashPathEffect(new float[]{6*density,6*density},0):null);c.drawLine(x(previous.time)+dodge,y(previous.spread,floor,limit,t,b),x(sample.time)+dodge,y(sample.spread,floor,limit,t,b),p);}
                previous=sample;
            }
            p.setPathEffect(null);
            for(ConsistencyStats.Sample sample:series.samples){
                if(!Double.isFinite(sample.spread))continue;
                // Slight visual dodge keeps same-day shooters' whiskers distinct;
                // both still belong to the exact same calendar date.
                float dodge=data.series.size()<2?0:(index%2==0?-3:3)*density;
                float xx=x(sample.time)+dodge,yy=y(sample.spread,floor,limit,t,b);p.setColor(a.shooters.color(series.person));p.setStrokeWidth(2*density);p.setStrokeCap(Paint.Cap.BUTT);
                ConsistencyStats.Interval ci=sample.interval;
                if(ci.available()){
                    float lower=y(ci.lower,floor,limit,t,b),upper=y(ci.upper,floor,limit,t,b),cap=5*density;
                    c.drawLine(xx,upper,xx,lower,p);c.drawLine(xx-cap,upper,xx+cap,upper,p);c.drawLine(xx-cap,lower,xx+cap,lower,p);
                }
                boolean hollow=!ci.available();float radius=(sample.time==selected?6:4.5f)*density;
                p.setStyle(hollow?Paint.Style.STROKE:Paint.Style.FILL);
                if(index%2==0)c.drawCircle(xx,yy,radius,p);else c.drawRect(xx-radius,yy-radius,xx+radius,yy+radius,p);
                p.setStyle(Paint.Style.FILL);
            }
        }
        p.setPathEffect(null);p.setStyle(Paint.Style.FILL);p.setColor(MainActivity.MUTED);p.setTextSize(11*scaled);
        if(data.mode==ConsistencyStats.DAILY){p.setTextAlign(Paint.Align.CENTER);p.setColor(HomeUi.AMBER);for(LabelCoverage.Day day:data.labelDays.values())if(!day.pending.isEmpty())c.drawText("?",x(day.time),b+13*density,p);p.setColor(MainActivity.MUTED);}
        if(!data.times.isEmpty()){
            boolean oneDay=ConsistencyStats.day(data.times.first())==ConsistencyStats.day(data.times.last());SimpleDateFormat label=new SimpleDateFormat(data.mode!=0&&oneDay?"h:mm a":"MMM d",Locale.US);
            if(data.times.size()==1){p.setTextAlign(Paint.Align.CENTER);c.drawText(label.format(new Date(data.times.first())),(l+r)/2,getHeight()-13*density,p);}
            else{
                // A tick for every sample time; up to five evenly chosen dates, skipped when they would overlap.
                p.setColor(0xFF42584C);p.setStrokeWidth(density);for(long time:data.times)c.drawLine(x(time),b,x(time),b+4*density,p);p.setColor(MainActivity.MUTED);
                List<Long> times=new ArrayList<>(data.times);int want=Math.min(times.size(),5);float edge=-Float.MAX_VALUE;String shown="";p.setTextAlign(Paint.Align.CENTER);
                for(int k=0;k<want;k++){long time=times.get(Math.round(k*(times.size()-1f)/Math.max(1,want-1)));String text=label.format(new Date(time));float w=p.measureText(text),cx=Math.max(l+w/2,Math.min(r-w/2,x(time)));
                    if(text.equals(shown)||cx-w/2<edge+8*density)continue;c.drawText(text,cx,getHeight()-13*density,p);edge=cx+w/2;shown=text;}
            }
        }
        if(finite==0){p.setTextAlign(Paint.Align.CENTER);c.drawText(data.labelDays.values().stream().anyMatch(day->!day.pending.isEmpty())?"Saved days need labels":"No eligible positions for these filters",(l+r)/2,(t+b)/2,p);}
        else if(data.mode==0&&data.times.size()==1){p.setTextAlign(Paint.Align.RIGHT);p.setTextSize(10*scaled);c.drawText("One labeled day",r,18*density,p);}
        if(isFocused()){p.setStyle(Paint.Style.STROKE);p.setColor(MainActivity.FG);p.setStrokeWidth(density);c.drawRoundRect(density,density,getWidth()-density,getHeight()-density,14*density,14*density,p);p.setStyle(Paint.Style.FILL);}
    }
    void choose(float xx){if(data.times.isEmpty())return;long time=data.times.first();if(data.times.size()>1)time+=(long)(Math.max(0,Math.min(1,(xx-plotLeft())/Math.max(1,plotRight()-plotLeft())))*(data.times.last()-data.times.first()));callback.at(data.nearest(time));}
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
    /** Visual key for the marks above: one glyph per meaning, wrapped to the width. */
    static final class Key extends View {
        static final String[] LABELS={"Daily spread","Likely range","No range yet","Missing labels","Gap or range change"};
        static final String[] SPOKEN={"Filled marks: daily spread.","Whiskers: likely range, about 95%.","Hollow marks: no range yet.","Amber question marks: days with missing labels, not zero.","Dashed lines: a gap or changed range."};
        final Paint p=new Paint(3);final float d,s;final int[] items;
        /** Shows the given entries of LABELS (all when none are given), so each chart explains only marks it draws. */
        Key(Context c,int... shown){super(c);d=getResources().getDisplayMetrics().density;s=getResources().getDisplayMetrics().scaledDensity;setTag("chart-key");items=shown.length==0?new int[]{0,1,2,3,4}:shown;
            StringBuilder spoken=new StringBuilder("Key.");for(int i:items)spoken.append(' ').append(SPOKEN[i]);setContentDescription(spoken);}
        float width(int i){p.setTextSize(12*s);return 24*d+p.measureText(LABELS[i])+14*d;}
        @Override protected void onMeasure(int w,int h){int width=MeasureSpec.getSize(w),rows=1;float x=0;for(int i:items){float item=width(i);if(x>0&&x+item>width){rows++;x=0;}x+=item;}setMeasuredDimension(width,Math.round(rows*26*d+4*d));}
        @Override protected void onDraw(Canvas c){float x=0,y=15*d;
            for(int i:items){float item=width(i);if(x>0&&x+item>getWidth()){x=0;y+=26*d;}glyph(c,i,x+10*d,y);
                p.setStyle(Paint.Style.FILL);p.setPathEffect(null);p.setColor(MainActivity.MUTED);p.setTextSize(12*s);p.setTextAlign(Paint.Align.LEFT);c.drawText(LABELS[i],x+24*d,y+4*s,p);x+=item;}
        }
        void glyph(Canvas c,int i,float cx,float cy){p.setColor(MainActivity.FG);p.setStrokeWidth(1.5f*d);p.setPathEffect(null);
            if(i==0){p.setStyle(Paint.Style.FILL);c.drawCircle(cx,cy,3.5f*d,p);}
            else if(i==1){p.setStyle(Paint.Style.STROKE);c.drawLine(cx,cy-7*d,cx,cy+7*d,p);c.drawLine(cx-4*d,cy-7*d,cx+4*d,cy-7*d,p);c.drawLine(cx-4*d,cy+7*d,cx+4*d,cy+7*d,p);p.setStyle(Paint.Style.FILL);c.drawCircle(cx,cy,2.5f*d,p);}
            else if(i==2){p.setStyle(Paint.Style.STROKE);c.drawCircle(cx,cy,3.5f*d,p);}
            else if(i==3){p.setStyle(Paint.Style.FILL);p.setColor(HomeUi.AMBER);p.setTextSize(13*s);p.setTextAlign(Paint.Align.CENTER);c.drawText("?",cx,cy+4.5f*s,p);}
            else{p.setStyle(Paint.Style.STROKE);p.setPathEffect(new DashPathEffect(new float[]{4*d,3*d},0));c.drawLine(cx-9*d,cy,cx+9*d,cy,p);}
        }
    }
}
