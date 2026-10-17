package org.redwoodarrows;

import android.text.TextUtils;
import android.view.*;
import android.widget.*;

/** The next end's context stays visible without a setup detour. */
final class QuickCapture {
    static void show(MainActivity a){
        LinearLayout context=a.row();context.setGravity(Gravity.CENTER_VERTICAL);context.setTag("next-group-context");
        LinearLayout target=a.row();target.setGravity(Gravity.CENTER_VERTICAL);target.setBackground(a.rounded(MainActivity.PANEL));target.setPadding(a.dp(5),0,a.dp(5),0);target.setTag("quick-target");
        TargetThumbnail thumb=new TargetThumbnail(a,a.selected);thumb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);target.addView(thumb,new LinearLayout.LayoutParams(a.dp(44),a.dp(52)));
        LinearLayout labels=new LinearLayout(a);labels.setOrientation(1);TextView title=a.text("TARGET",10,MainActivity.MUTED);title.setPadding(0,0,0,0);labels.addView(title);
        TextView name=a.text(a.selected.family+" · "+(a.selected.circular()?TargetPreset.number(a.selected.diameterMm/10)+" cm":"paper"),12,MainActivity.FG);name.setSingleLine();name.setEllipsize(TextUtils.TruncateAt.END);name.setPadding(0,a.dp(3),0,0);labels.addView(name);target.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        target.setFocusable(true);target.setClickable(true);target.setContentDescription("Next target: "+a.selected.name+". "+a.selected.dimensions()+". Tap to change.");target.setOnClickListener(v->{if(!a.busy)a.showCatalog();});
        context.addView(target,new LinearLayout.LayoutParams(0,a.dp(52),1.4f));
        LinearLayout owner=new LinearLayout(a);owner.setOrientation(1);
        LinearLayout range=new LinearLayout(a);range.setOrientation(1);Runnable refreshDistance=()->{range.removeAllViews();View distance=PracticeUi.inline(a,null,"Next grouping",null);distance.setTag("quick-distance");range.addView(distance,new LinearLayout.LayoutParams(-1,a.dp(48)));};
        if(a.shooters.roster.all().isEmpty()){
            Button choose=a.button("Add shooter",a.shooters::chooseActive);choose.setTag("quick-shooter");owner.addView(choose,new LinearLayout.LayoutParams(-1,a.dp(48)));
        }else{
            ShooterPager choose=new ShooterPager(a,a.shooters.roster.all(),a.shooters.next(),false,"Next grouping",person->{if(!a.shooters.selectActive(person.id))return false;refreshDistance.run();return true;});choose.setTag("quick-shooter");owner.addView(choose,new LinearLayout.LayoutParams(-1,a.dp(48)));
        }
        LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(0,-2,1);op.setMargins(a.dp(6),0,0,0);context.addView(owner,op);
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,-2,.8f);rp.setMargins(a.dp(6),0,0,0);context.addView(range,rp);refreshDistance.run();a.add(a.root,context);
        LinearLayout actions=a.row();Button photo=a.button("Take photo",()->a.takePhoto(false));photo.setTag("quick-photo");photo.setContentDescription("Take a photo of a new grouping. Save on this phone and sync automatically when connected.");photo.setBackground(a.rounded(MainActivity.ACCENT));photo.setTextColor(MainActivity.BG);
        Button add=a.button("Add photos",()->a.addPhotos(false));add.setTag("quick-import");add.setContentDescription("Add camera-roll photos of one grouping. Originals stay untouched.");
        for(Button button:new Button[]{photo,add}){button.setTextSize(15);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,a.dp(52),1);p.setMargins(a.dp(2),0,a.dp(2),0);actions.addView(button,p);}a.add(a.root,actions);
    }
}
