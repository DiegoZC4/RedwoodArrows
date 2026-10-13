package org.redwoodarrows;

import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;

/** Direct horizontal scrubber; preview while held, persist once on release. */
final class DistanceControl extends View {
    interface Change {boolean save(double meters,String unit);}
    final CyclicPagerMotion motion;
    private final Change change;private final Runnable exact;private final String caption;private final boolean walkup;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private double meters;private String unit;private int pointer=-1;private boolean heldReset;
    private long holdStart;private float holdProgress;
    private final Runnable hold=new Runnable(){public void run(){
        if(pointer<0||motion.moved||!isEnabled())return;
        holdProgress=Math.min(1,(SystemClock.uptimeMillis()-holdStart)/600f);invalidate();
        if(holdProgress<1){postDelayed(this,16);return;}
        heldReset=true;commit(Double.NaN,unit);releaseParent();motion.rest();invalidate();
    }};
    DistanceControl(Context context,double meters,String unit,boolean walkup,String caption,Change change,Runnable exact){
        super(context);this.meters=meters;this.unit=unit;this.walkup=walkup;this.caption=caption;this.change=change;this.exact=exact;
        motion=new CyclicPagerMotion(1,0,dp(4),ViewConfiguration.get(context).getScaledTouchSlop(),true);
        setClickable(true);setLongClickable(true);setFocusable(true);setMinimumHeight(dp(44));
        setTooltipText("Swipe horizontally to adjust; tap for exact distance; hold to clear.");describe();
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    double meters(){return meters;}String unit(){return unit;}
    private int steps(){return motion.steps+(Math.abs(motion.offset)>motion.extent()/2?(motion.offset<0?1:-1):0);}
    double preview(){return motion.held&&!motion.yielded?DistanceSteps.at(meters,unit,steps()):meters;}
    private String label(double value){return Practice.distance(value)?TargetPreset.number(Practice.fromMeters(value,unit))+" "+unit:"— "+unit;}
    private void describe(){setStateDescription(Practice.distance(meters)?label(meters):"Distance not set");setContentDescription(caption+" distance. "+getStateDescription()+(walkup?". Base distance only; per-arrow walk-up distances are preserved":"")+". Swipe left to increase, right to decrease. Tap for exact entry. Hold to clear. Vertical swipes scroll groupings.");}
    @Override protected void onMeasure(int w,int h){setMeasuredDimension(resolveSize(dp(100),w),resolveSize(Math.max(dp(44),Math.round(14*getResources().getDisplayMetrics().scaledDensity)+dp(walkup?30:22)),h));}
    @Override protected void onDraw(Canvas c){
        float d=getResources().getDisplayMetrics().density;paint.setStyle(Paint.Style.FILL);paint.setColor(MainActivity.PANEL);c.drawRoundRect(1,1,getWidth()-1,getHeight()-1,dp(8),dp(8),paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(isFocused()?2:1));paint.setColor(isFocused()?MainActivity.ACCENT:0xff476153);c.drawRoundRect(1,1,getWidth()-1,getHeight()-1,dp(8),dp(8),paint);paint.setStyle(Paint.Style.FILL);
        paint.setColor(MainActivity.FG);paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(14*getResources().getDisplayMetrics().scaledDensity);
        String value=label(preview());if(paint.measureText(value)>getWidth()-dp(10))paint.setTextSize(paint.getTextSize()*(getWidth()-dp(10))/paint.measureText(value));
        c.drawText(value,getWidth()/2f,(getHeight()-paint.ascent()-paint.descent())/2-dp(walkup?7:2),paint);
        paint.setColor(MainActivity.MUTED);paint.setStrokeWidth(d);
        if(walkup){paint.setTextSize(9*getResources().getDisplayMetrics().scaledDensity);c.drawText("walk-up base",getWidth()/2f,getHeight()-dp(7),paint);}
        else {float offset=motion.held?motion.offset:0;for(float x=getWidth()/2f+offset-dp(112);x<getWidth();x+=dp(28))if(x>dp(6))c.drawLine(x,getHeight()-dp(7),x,getHeight()-dp(4),paint);}
        if(holdProgress>0){paint.setColor(MainActivity.ACCENT);paint.setStrokeWidth(dp(2));c.drawLine(dp(6),getHeight()-dp(2),dp(6)+(getWidth()-dp(12))*holdProgress,getHeight()-dp(2),paint);}
    }
    boolean commit(double value,String units){
        if(!isEnabled()||(!Double.isNaN(value)&&!Practice.distance(value))||(!units.equals("yd")&&!units.equals("m")))return false;
        if(DistanceSteps.same(value,meters)&&units.equals(unit))return true;
        if(!change.save(value,units))return false;
        meters=value;unit=units;describe();invalidate();sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);performHapticFeedback(HapticFeedbackConstants.CONFIRM);return true;
    }
    private void releaseParent(){removeCallbacks(hold);holdProgress=0;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);pointer=-1;setPressed(false);}
    private void cancel(){releaseParent();if(motion!=null)motion.rest();invalidate();}
    private boolean move(float x,float y){motion.move(x,y);if(motion.moved){removeCallbacks(hold);holdProgress=0;}if(motion.yielded){cancel();return false;}if(motion.lockedAxis==CyclicPagerMotion.X&&getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);invalidate();return true;}
    @Override public boolean onTouchEvent(MotionEvent e){
        if(!isEnabled())return false;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:cancel();heldReset=false;pointer=e.getPointerId(0);motion.begin(e.getX(),e.getY(),dp(28),getHeight());setPressed(true);holdStart=SystemClock.uptimeMillis();postDelayed(hold,120);return true;
            case MotionEvent.ACTION_MOVE:case MotionEvent.ACTION_UP:
                if(pointer<0)return true;int p=e.findPointerIndex(pointer);if(p<0){cancel();return true;}
                for(int i=0;i<e.getHistorySize();i++)if(!move(e.getHistoricalX(p,i),e.getHistoricalY(p,i)))return true;
                if(!move(e.getX(p),e.getY(p)))return true;
                if(e.getActionMasked()==MotionEvent.ACTION_UP){boolean tap=!motion.moved&&!heldReset;double next=preview();boolean horizontal=motion.lockedAxis==CyclicPagerMotion.X;cancel();if(tap)performClick();else if(horizontal)commit(next,unit);}return true;
            case MotionEvent.ACTION_POINTER_DOWN:case MotionEvent.ACTION_CANCEL:cancel();return true;
            default:return true;
        }
    }
    private boolean advance(int direction){if(!isEnabled()||motion.held)return false;return commit(DistanceSteps.at(meters,unit,direction),unit);}
    @Override public boolean performClick(){if(!isEnabled())return false;super.performClick();exact.run();return true;}
    @Override public boolean performLongClick(){if(!isEnabled())return false;cancel();return commit(Double.NaN,unit);}
    @Override public boolean onKeyDown(int key,KeyEvent e){if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){advance(key==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1);return true;}if(key==KeyEvent.KEYCODE_DEL||key==KeyEvent.KEYCODE_FORWARD_DEL){commit(Double.NaN,unit);return true;}return super.onKeyDown(key,e);}
    @Override public boolean onGenericMotionEvent(MotionEvent e){if(e.getAction()==MotionEvent.ACTION_SCROLL&&e.getAxisValue(MotionEvent.AXIS_VSCROLL)==0&&e.getAxisValue(MotionEvent.AXIS_HSCROLL)!=0)return advance(e.getAxisValue(MotionEvent.AXIS_HSCROLL)>0?1:-1);return super.onGenericMotionEvent(e);}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(android.widget.SeekBar.class.getName());info.setScrollable(true);info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,0,(float)Practice.fromMeters(1000,unit),Practice.distance(meters)?(float)Practice.fromMeters(meters,unit):0));info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK,"Clear distance"));}
    @Override public boolean performAccessibilityAction(int action,Bundle args){if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)return advance(1);if(action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)return advance(-1);if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()&&args!=null){double value=Practice.toMeters(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,Float.NaN),unit);return Practice.distance(value)&&commit(value,unit);}return super.performAccessibilityAction(action,args);}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(!focused)cancel();}
    @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);if(!enabled)cancel();}
    @Override protected void onDetachedFromWindow(){cancel();super.onDetachedFromWindow();}
}
