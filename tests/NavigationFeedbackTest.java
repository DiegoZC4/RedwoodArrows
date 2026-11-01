package org.redwoodarrows;

import android.graphics.*;
import android.graphics.drawable.Drawable;

public final class NavigationFeedbackTest {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static int pixel(Drawable background,int... state){
        background.setState(state);Bitmap bitmap=Bitmap.createBitmap(120,60,Bitmap.Config.ARGB_8888);
        background.setBounds(0,0,120,60);background.draw(new Canvas(bitmap));int value=bitmap.getPixel(60,30);bitmap.recycle();return value;
    }
    static void run(){
        Drawable background=HomeUi.navigationBackground(1);
        int enabled=android.R.attr.state_enabled,pressed=android.R.attr.state_pressed,selected=android.R.attr.state_selected,focused=android.R.attr.state_focused;
        check(pixel(background,enabled)==Color.TRANSPARENT,"idle tab has no rectangular fill");
        check(pixel(background,enabled,selected)==Color.TRANSPARENT,"selection colors icon rather than orange rectangle");
        int feedback=pixel(background,enabled,pressed);
        check(Color.alpha(feedback)==0x26,"press highlight is restrained");
        check(Color.green(feedback)>Color.red(feedback)&&Color.blue(feedback)>Color.red(feedback),"press highlight is mint, not orange");
        check(pixel(background,enabled,selected,pressed)==feedback,"selected tab uses same feedback");
        check(pixel(background,enabled,selected)==Color.TRANSPARENT,"release clears highlight without fade delay");
        check(pixel(background,enabled,focused)==feedback,"keyboard focus remains visible");
        check(pixel(background,pressed)==Color.TRANSPARENT,"disabled tab has no press response");
        check(pixel(background,enabled)==Color.TRANSPARENT,"blur restores transparent background");
        System.out.println("NavigationFeedbackTest: "+checks+" checks passed");
    }
}
