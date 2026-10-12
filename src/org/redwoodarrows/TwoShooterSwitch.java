package org.redwoodarrows;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;

/** One shared capsule: every click/tap toggles once, even the selected half. */
final class TwoShooterSwitch extends View {
    interface Change {boolean select(int index);}
    private final String[] names;private final Change change;private final String description;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final TextPaint text=new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private int selected;private float thumb;private ValueAnimator animation;
    TwoShooterSwitch(Context context,String first,String second,int selected,Change change){
        this(context,first,second,selected,"Shooting as",change);
    }
    TwoShooterSwitch(Context context,String first,String second,int selected,String description,Change change){
        super(context);this.names=new String[]{first,second};this.selected=selected;this.thumb=selected;this.change=change;this.description=description;
        setClickable(true);setFocusable(true);setMinimumHeight(dp(48));describe();
    }
    int selectedIndex(){return selected;}
    private int dp(float size){return Math.round(size*getResources().getDisplayMetrics().density);}
    private void describe(){setContentDescription(description+" "+names[selected]+". Tap anywhere to switch to "+names[1-selected]+".");setStateDescription(names[selected]);}
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int height=Math.max(dp(48),(int)(getResources().getDisplayMetrics().scaledDensity*18+dp(24)));
        setMeasuredDimension(resolveSize(dp(240),widthSpec),resolveSize(height,heightSpec));
    }
    @Override protected void onDraw(Canvas c){
        float inset=dp(4),slot=(getWidth()-2*inset)/2,r=getHeight()/2f;
        paint.setColor(MainActivity.PANEL);c.drawRoundRect(0,0,getWidth(),getHeight(),r,r,paint);
        paint.setColor(MainActivity.ACCENT);c.drawRoundRect(inset+thumb*slot,inset,inset+(thumb+1)*slot,getHeight()-inset,r-inset,r-inset,paint);
        text.setTextSize(15*getResources().getDisplayMetrics().scaledDensity);text.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));text.setTextAlign(Paint.Align.CENTER);
        float baseline=(getHeight()-text.ascent()-text.descent())/2;
        for(int i=0;i<2;i++){text.setColor(i==selected?MainActivity.BG:MainActivity.FG);String label=TextUtils.ellipsize(names[i],text,Math.max(1,slot-2*dp(8)),TextUtils.TruncateAt.END).toString();c.drawText(label,inset+slot*(i+.5f),baseline,text);}
        if(isFocused()){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setColor(MainActivity.FG);c.drawRoundRect(dp(1),dp(1),getWidth()-dp(1),getHeight()-dp(1),r,r,paint);paint.setStyle(Paint.Style.FILL);}
    }
    private boolean choose(int next){
        if(!isEnabled()||next==selected)return false;
        if(!change.select(next))return false;
        selected=next;describe();if(animation!=null)animation.cancel();animation=ValueAnimator.ofFloat(thumb,next);animation.setDuration(180);
        animation.addUpdateListener(v->{thumb=(float)v.getAnimatedValue();invalidate();});animation.start();sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED);return true;
    }
    @Override public boolean performClick(){if(!isEnabled())return false;super.performClick();return choose(ShooterRoster.toggle(selected));}
    @Override public boolean onKeyDown(int key,KeyEvent event){
        if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){if(isEnabled())choose(key==KeyEvent.KEYCODE_DPAD_LEFT?0:1);return true;}
        if(key==KeyEvent.KEYCODE_SPACE){if(isEnabled()&&event.getRepeatCount()==0)setPressed(true);return true;}
        return super.onKeyDown(key,event);
    }
    @Override public boolean onKeyUp(int key,KeyEvent event){
        if(key==KeyEvent.KEYCODE_SPACE){boolean pressed=isPressed();setPressed(false);if(pressed&&!event.isCanceled())performClick();return true;}
        if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT)return true;
        return super.onKeyUp(key,event);
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(android.widget.Button.class.getName());}
    @Override protected void onDetachedFromWindow(){if(animation!=null)animation.cancel();super.onDetachedFromWindow();}
}
