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
        LinearLayout labels=new LinearLayout(a);labels.setOrientation(1);TextView title=a.text("NEXT TARGET",10,MainActivity.MUTED);title.setPadding(0,0,0,0);labels.addView(title);
        TextView name=a.text(a.selected.family+" · "+(a.selected.circular()?TargetPreset.number(a.selected.diameterMm/10)+" cm":"paper"),12,MainActivity.FG);name.setSingleLine();name.setEllipsize(TextUtils.TruncateAt.END);name.setPadding(0,a.dp(3),0,0);labels.addView(name);target.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        target.setFocusable(true);target.setClickable(true);target.setContentDescription("Next target: "+a.selected.name+". "+a.selected.dimensions()+". Tap to change.");target.setOnClickListener(v->{if(!a.busy)a.showCatalog();});
        context.addView(target,new LinearLayout.LayoutParams(0,a.dp(56),1));
        LinearLayout owner=new LinearLayout(a);owner.setOrientation(1);TextView next=a.text("NEXT SHOOTER",10,MainActivity.MUTED);next.setPadding(0,0,0,0);next.setGravity(Gravity.CENTER);owner.addView(next);
        if(a.shooters.roster.all().isEmpty()){
            Button choose=a.button("Add shooter",a.shooters::chooseActive);choose.setTag("quick-shooter");owner.addView(choose,new LinearLayout.LayoutParams(-1,a.dp(48)));
        }else{
            ShooterPager choose=new ShooterPager(a,a.shooters.roster.all(),a.shooters.active(),false,"Next grouping",person->a.shooters.selectActive(person.id));choose.setTag("quick-shooter");owner.addView(choose,new LinearLayout.LayoutParams(-1,a.dp(48)));
        }
        LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(a.dp(116),-2);op.setMargins(a.dp(8),0,0,0);context.addView(owner,op);a.add(a.root,context);
        View distance=PracticeUi.inline(a,null,"Next grouping",null);distance.setTag("quick-distance");a.add(a.root,distance);
        LinearLayout actions=a.row();Button photo=a.button("Take photo",()->a.takePhoto(false));photo.setTag("quick-photo");photo.setContentDescription("Take a photo of a new grouping. Save on this phone and sync automatically when connected.");photo.setBackground(a.rounded(MainActivity.ACCENT));photo.setTextColor(MainActivity.BG);
        Button add=a.button("Add photos",()->a.addPhotos(false));add.setTag("quick-import");add.setContentDescription("Add camera-roll photos of one grouping. Originals stay untouched.");
        Button scan=a.button("Sweep",a::quickCapture);scan.setTag("quick-scan");scan.setContentDescription("Automatic moving-phone sweep with arrow analysis");
        for(Button button:new Button[]{photo,add,scan}){button.setTextSize(13);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,a.dp(48),1);p.setMargins(a.dp(2),0,a.dp(2),0);actions.addView(button,p);}a.add(a.root,actions);
    }
}
