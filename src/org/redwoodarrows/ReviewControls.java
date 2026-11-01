package org.redwoodarrows;

import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageButton;

final class ReviewControls {
    static GradientDrawable background(MainActivity a,int color){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(a.dp(6));return shape;}
    static ImageButton icon(MainActivity a,String icon,String label,Runnable action){
        int resource=a.getResources().getIdentifier("review_"+icon.replace('-','_'),"drawable",a.getPackageName());
        ImageButton button=new ImageButton(a);button.setImageResource(resource);button.setColorFilter(MainActivity.FG);
        button.setBackground(background(a,MainActivity.PANEL));button.setPadding(a.dp(12),a.dp(12),a.dp(12),a.dp(12));
        button.setContentDescription(label);button.setTooltipText(label);button.setOnClickListener(v->{if(!a.busy)action.run();});return button;
    }
    static void enabled(View view,boolean enabled){view.setEnabled(enabled);view.setAlpha(enabled?1f:.35f);}
    /** Controls (dropdowns, filter chips, secondary buttons) are outlined; filled panels are kept for content. */
    static final int OUTLINE=0xFF3B5448;
    static GradientDrawable outline(MainActivity a){GradientDrawable shape=new GradientDrawable();shape.setColor(android.graphics.Color.TRANSPARENT);shape.setStroke(a.dp(1),OUTLINE);shape.setCornerRadius(a.dp(14));return shape;}
    /** A checkbox drawn as a rounded chip: filled with `on` when checked, outlined otherwise. Behavior and state are the CheckBox's own. */
    static void chip(MainActivity a,android.widget.CompoundButton box,int on,int onText){
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        GradientDrawable filled=new GradientDrawable();filled.setColor(on);filled.setCornerRadius(a.dp(14));
        states.addState(new int[]{android.R.attr.state_checked},filled);states.addState(new int[]{},outline(a));
        box.setButtonDrawable(null);box.setBackground(states);box.setGravity(android.view.Gravity.CENTER);box.setPadding(a.dp(12),0,a.dp(12),0);
        box.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{onText,MainActivity.FG}));
    }
}
