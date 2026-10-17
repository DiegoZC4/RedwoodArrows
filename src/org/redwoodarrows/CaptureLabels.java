package org.redwoodarrows;

import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.*;

/** The same compact, direct labels before the shutter and in photo review. */
final class CaptureLabels extends LinearLayout {
    interface Change {boolean save(ShooterRoster.Person person,Practice practice)throws Exception;}
    final MainActivity a;final Supplier<Store.Group> source;final Change change;
    final boolean recall;final List<Store.Group> history;final Map<String,Practice> edited=new HashMap<>();
    final LinearLayout distanceSlot;ShooterPager shooter;DistanceControl distance;Button add;
    CaptureLabels(MainActivity a,Supplier<Store.Group> source,boolean recall,Change change){
        super(a);this.a=a;this.source=source;this.recall=recall;this.change=change;history=a.store.list();
        setOrientation(HORIZONTAL);setTag("capture-labels");
        LinearLayout names=column("SHOOTER");distanceSlot=column("DISTANCE");
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1.3f);left.setMargins(0,0,a.dp(8),0);addView(names,left);addView(distanceSlot,new LinearLayout.LayoutParams(0,-2,1));
        if(a.shooters.roster.all().isEmpty()){
            add=a.button("Add shooter",()->a.shooters.pickForCapture(person->{if(person.assigned()&&select(person)){names.removeView(add);names.addView(pager(),new LinearLayout.LayoutParams(-1,a.dp(48)));}}));add.setTag("capture-shooter");names.addView(add,new LinearLayout.LayoutParams(-1,a.dp(48)));
        }else names.addView(pager(),new LinearLayout.LayoutParams(-1,a.dp(48)));
        refreshDistance();
    }
    private LinearLayout column(String title){LinearLayout column=new LinearLayout(a);column.setOrientation(VERTICAL);TextView label=a.text(title,10,MainActivity.MUTED);label.setPadding(a.dp(2),a.dp(2),0,a.dp(4));column.addView(label);return column;}
    private ShooterPager pager(){shooter=new ShooterPager(a,a.shooters.roster.all(),source.get().shooter,new GroupPresentation(source.get()).provisionalOwner(),"This grouping",this::select);shooter.setTag("capture-shooter");return shooter;}
    private boolean select(ShooterRoster.Person person){
        if(!isEnabled())return false;
        try{
            Store.Group current=source.get();Practice old=Practice.from(current.practice.json());
            Practice next=!recall||current.shooter.id.equals(person.id)?old:edited.containsKey(person.id)?Practice.from(edited.get(person.id).json()):PracticeUi.forPerson(a,person,history);
            String oldId=current.shooter.id;if(!change.save(person,next))return false;
            edited.put(oldId,old);refreshDistance();return true;
        }catch(Exception e){a.message("Could not change shooter: "+e.getMessage());return false;}
    }
    void refreshDistance(){
        if(distance!=null)distanceSlot.removeView(distance);Practice p=source.get().practice;
        distance=new DistanceControl(a,p.meters,p.unit,!p.arrowMeters.isEmpty(),"This grouping",(meters,unit)->{
            if(!isEnabled())return false;
            try{Store.Group current=source.get();Practice updated=Practice.from(current.practice.json());updated.meters=meters;updated.unit=unit;
                if(!change.save(current.shooter,updated))return false;edited.put(current.shooter.id,Practice.from(updated.json()));return true;
            }catch(Exception e){a.message("Could not change distance: "+e.getMessage());return false;}
        },()->PracticeUi.distanceDialog(a,distance));distance.setTag("capture-distance");distance.setEnabled(isEnabled());distanceSlot.addView(distance,new LinearLayout.LayoutParams(-1,a.dp(48)));
    }
    @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);if(shooter!=null)shooter.setEnabled(enabled);if(add!=null)add.setEnabled(enabled);if(distance!=null)distance.setEnabled(enabled);}
}
