package org.redwoodarrows;

import android.animation.*;
import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.view.accessibility.*;
import android.view.animation.DecelerateInterpolator;
import java.util.*;

/** Horizontal cyclic names. Vertical gestures belong to the grouping table. */
final class ShooterPager extends View {
    interface Change {boolean select(ShooterRoster.Person person);}
    private final List<ShooterRoster.Person> people;
    private final Change change;private final String caption;
    final CyclicPagerMotion motion;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text=new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator animation;
    private int pointer=-1,drawBase,drawSteps;private float drawOffset;
    private boolean provisional;
    // Owner identity uses pastels distinct from the lime primary-action colour.
    private static final int[] COLORS={0xffC6C4EA,0xffA4D0D0,0xffE7C295,0xffDDBAD0};

    ShooterPager(Context context,List<ShooterRoster.Person> options,ShooterRoster.Person current,boolean provisional,String caption,Change change){
        super(context);this.people=new ArrayList<>(options);this.change=change;this.caption=caption;this.provisional=provisional;
        if(current.assigned()&&people.stream().noneMatch(p->p.id.equals(current.id)))people.add(current);
        int selected=-1;for(int i=0;i<people.size();i++)if(people.get(i).id.equals(current.id))selected=i;
        motion=new CyclicPagerMotion(Math.max(1,people.size()),selected,dp(4),ViewConfiguration.get(context).getScaledTouchSlop(),true);drawBase=selected;
        setClickable(true);setFocusable(true);setMinimumHeight(dp(48));
        setTooltipText("Tap to advance; swipe horizontally to browse.");
        setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(8));}});setClipToOutline(true);setEnabled(!people.isEmpty());describe();
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    int selectedIndex(){return motion.selected;}
    String value(){return label(motion.selected);}
    private String label(int i){return i<0||people.isEmpty()?"Unassigned":people.get(i).label();}
    private void describe(){
        String state=value()+(provisional?" (unconfirmed)":"");setStateDescription(state);
        setContentDescription(caption()+". "+state+(people.isEmpty()?". Add names from More, People.":". Swipe left for next shooter; right for previous. Swipe vertically to scroll groupings. Tap to advance."));
    }
    private String caption(){return caption+" shooter";}
    @Override protected void onMeasure(int w,int h){setMeasuredDimension(resolveSize(dp(88),w),resolveSize(Math.max(dp(48),Math.round(14*getResources().getDisplayMetrics().scaledDensity)+dp(24)),h));}
    private int drawIndex(int relative){return drawBase>=0?motion.wrap(drawBase+relative):relative==0?-1:motion.wrap(relative>0?relative-1:relative);}
    private void layer(Canvas c,int relative,float x,float y){
        int i=drawIndex(drawSteps+relative);c.save();c.translate(x,y);
        paint.setColor(i<0?MainActivity.PANEL:COLORS[i%COLORS.length]);c.drawRect(0,0,getWidth(),getHeight(),paint);
        text.setTextSize(14*getResources().getDisplayMetrics().scaledDensity);text.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));text.setTextAlign(Paint.Align.CENTER);text.setColor(i<0?MainActivity.FG:MainActivity.BG);
        boolean unconfirmed=provisional&&i==motion.selected;float badge=unconfirmed?Math.max(dp(22),14*getResources().getDisplayMetrics().scaledDensity):0;
        String clipped=TextUtils.ellipsize(label(i),text,Math.max(1,getWidth()-dp(8)-badge),TextUtils.TruncateAt.END).toString();
        c.drawText(clipped,(getWidth()-badge)/2f,(getHeight()-text.ascent()-text.descent())/2,text);
        if(unconfirmed){float cx=getWidth()-badge/2,cy=getHeight()/2f;paint.setColor(i<0?MainActivity.FG:MainActivity.BG);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));c.drawCircle(cx,cy,badge*.34f,paint);paint.setStyle(Paint.Style.FILL);text.setTextSize(10*getResources().getDisplayMetrics().scaledDensity);c.drawText("?",cx,cy-(text.ascent()+text.descent())/2,text);}
        paint.setColor(0x33000000);paint.setStrokeWidth(1);c.drawLine(getWidth()-1,0,getWidth()-1,getHeight(),paint);c.restore();
    }
    @Override protected void onDraw(Canvas c){
        c.save();c.clipRect(0,0,getWidth(),getHeight());
        for(int i=-1;i<=1;i++)layer(c,i,drawOffset+i*getWidth(),0);c.restore();
        if(isFocused()){paint.setStyle(Paint.Style.STROKE);paint.setColor(MainActivity.FG);paint.setStrokeWidth(dp(2));c.drawRoundRect(1,1,getWidth()-1,getHeight()-1,dp(8),dp(8),paint);paint.setStyle(Paint.Style.FILL);}
    }
    private void stopAnimation(){if(animation!=null){ValueAnimator old=animation;animation=null;old.cancel();}}
    private void rest(){stopAnimation();motion.rest();drawBase=motion.selected;drawSteps=0;drawOffset=0;invalidate();}
    private void releaseParent(){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);pointer=-1;setPressed(false);}
    private void cancelGesture(){releaseParent();rest();}
    private void settle(int extra){
        int next=motion.at(motion.steps+extra);float target=-extra*motion.extent();
        if(next!=motion.selected){
            if(people.isEmpty()||!change.select(people.get(next))){rest();return;}
            motion.selected=next;provisional=false;describe();sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
            // Respect system haptic preferences; feedback only follows a
            // successful commit, never preview movement or a cancelled gesture.
            performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        }
        // Save once on release, not during browsing or at an animation callback.
        // Interruption therefore cannot lose an accepted gesture or write later.
        stopAnimation();if(!ValueAnimator.areAnimatorsEnabled()){rest();return;}
        ValueAnimator snap=ValueAnimator.ofFloat(drawOffset,target);animation=snap;snap.setDuration(100);snap.setInterpolator(new DecelerateInterpolator());
        snap.addUpdateListener(v->{drawOffset=(float)v.getAnimatedValue();invalidate();});
        snap.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator v){if(animation==v){animation=null;motion.rest();drawBase=motion.selected;drawSteps=0;drawOffset=0;invalidate();}}});snap.start();
    }
    private boolean advance(int direction){
        if(!isEnabled()||people.isEmpty()||motion.held)return false;
        rest();motion.begin(0,0,getWidth(),getHeight());motion.held=false;settle(direction);return true;
    }
    @Override public boolean performClick(){if(!isEnabled())return false;super.performClick();return advance(1);}
    private boolean moveGesture(float x,float y){
        motion.move(x,y);if(motion.yielded){cancelGesture();return false;}
        if(motion.lockedAxis==CyclicPagerMotion.X&&getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
        drawSteps=motion.steps;drawOffset=motion.offset;invalidate();return true;
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(!isEnabled())return false;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                rest();pointer=e.getPointerId(0);motion.begin(e.getX(),e.getY(),getWidth(),getHeight());setPressed(true);
                // Do not capture on touch-down: GridView must see the first
                // vertical move, including a fast swipe starting on this name.
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);return true;
            case MotionEvent.ACTION_MOVE:
                if(pointer<0)return true;int index=e.findPointerIndex(pointer);if(index<0){cancelGesture();return true;}
                for(int i=0;i<e.getHistorySize();i++)if(!moveGesture(e.getHistoricalX(index,i),e.getHistoricalY(index,i)))return true;
                moveGesture(e.getX(index),e.getY(index));return true;
            case MotionEvent.ACTION_UP:
                if(pointer<0)return true;int p=e.findPointerIndex(pointer);if(p<0){cancelGesture();return true;}
                for(int i=0;i<e.getHistorySize();i++)if(!moveGesture(e.getHistoricalX(p,i),e.getHistoricalY(p,i)))return true;
                if(!moveGesture(e.getX(p),e.getY(p)))return true;
                int extra=motion.release(e.getX(p),e.getY(p));drawSteps=motion.steps;drawOffset=motion.offset;releaseParent();
                if(!motion.moved){super.performClick();}settle(extra);return true;
            case MotionEvent.ACTION_POINTER_DOWN:case MotionEvent.ACTION_CANCEL:cancelGesture();return true;
            default:return true;
        }
    }
    @Override public boolean onGenericMotionEvent(MotionEvent e){
        if(e.getAction()==MotionEvent.ACTION_SCROLL){float vertical=e.getAxisValue(MotionEvent.AXIS_VSCROLL),horizontal=e.getAxisValue(MotionEvent.AXIS_HSCROLL);if(vertical==0&&horizontal!=0)return advance(horizontal>0?1:-1);}
        return super.onGenericMotionEvent(e);
    }
    @Override public boolean onKeyDown(int key,KeyEvent event){
        if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){if(event.getRepeatCount()==0)advance(key==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1);return true;}
        if(key==KeyEvent.KEYCODE_SPACE){if(event.getRepeatCount()==0)setPressed(true);return true;}return super.onKeyDown(key,event);
    }
    @Override public boolean onKeyUp(int key,KeyEvent event){
        if(key==KeyEvent.KEYCODE_SPACE){boolean pressed=isPressed();setPressed(false);if(pressed&&!event.isCanceled())performClick();return true;}
        if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT)return true;return super.onKeyUp(key,event);
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
        super.onInitializeAccessibilityNodeInfo(info);info.setClassName(android.widget.SeekBar.class.getName());info.setScrollable(!people.isEmpty());
        if(!people.isEmpty()){info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,0,people.size(),motion.selected+1));info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);}
    }
    @Override public boolean performAccessibilityAction(int action,Bundle args){
        if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)return advance(1);if(action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)return advance(-1);return super.performAccessibilityAction(action,args);
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(!focused)cancelGesture();}
    @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);if(!enabled&&motion!=null)cancelGesture();}
    @Override protected void onDetachedFromWindow(){cancelGesture();super.onDetachedFromWindow();}
}
