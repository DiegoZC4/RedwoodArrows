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
        Button distance=a.button(PracticeUi.nextLabel(a)+" · next end",()->PracticeUi.nextDialog(a));distance.setTag("quick-distance");distance.setTextSize(12);distance.setContentDescription("Next grouping distance: "+PracticeUi.nextLabel(a)+". Change distance and equipment setup.");a.add(a.root,distance);
        LinearLayout actions=a.row();Button scan=a.button("Scan next",a::quickCapture);scan.setTag("quick-scan");scan.setBackground(a.rounded(MainActivity.ACCENT));scan.setTextColor(MainActivity.BG);actions.addView(scan,new LinearLayout.LayoutParams(0,a.dp(48),1));
        Button setup=a.button("Options",a::captureSetup);setup.setTag("new-grouping");setup.setContentDescription("New grouping options: import photos or change setup");LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(a.dp(88),a.dp(48));sp.setMargins(a.dp(8),0,0,0);actions.addView(setup,sp);a.add(a.root,actions);
    }
}
