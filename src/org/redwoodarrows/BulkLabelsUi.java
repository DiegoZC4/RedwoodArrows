package org.redwoodarrows;

import android.app.AlertDialog;
import android.widget.*;
import java.util.*;

/** One sheet, explicit field scope, one durable local save for the selection. */
final class BulkLabelsUi {
    final MainActivity a;AlertDialog dialog;
    BulkLabelsUi(MainActivity a){this.a=a;}
    boolean open(){return dialog!=null&&dialog.isShowing();}
    void show(List<Store.Group> selected){
        if(selected.isEmpty())return;
        final List<Store.Group> snapshot=new ArrayList<>(selected);final BulkLabels.Change change=new BulkLabels.Change();
        LinearLayout body=new LinearLayout(a);body.setOrientation(1);body.setPadding(a.dp(18),a.dp(8),a.dp(18),a.dp(8));body.setBackgroundColor(MainActivity.BG);
        CheckBox shooter=new CheckBox(a);shooter.setText("Change shooter");shooter.setTextColor(MainActivity.FG);shooter.setTag("bulk-change-shooter");body.addView(shooter);
        List<ShooterRoster.Person> people=new ArrayList<>(a.shooters.roster.all());people.removeIf(p->!p.assigned());people.add(ShooterRoster.UNASSIGNED);
        ShooterPager names=new ShooterPager(a,people,ShooterRoster.UNASSIGNED,false,"Selected rounds",p->{change.person=p;shooter.setChecked(true);return true;});names.setTag("bulk-shooter");a.add(body,names);
        CheckBox distance=new CheckBox(a);distance.setText("Change base distance");distance.setTextColor(MainActivity.FG);distance.setTag("bulk-change-distance");body.addView(distance);
        final DistanceControl[] control=new DistanceControl[1];control[0]=new DistanceControl(a,Double.NaN,"yd",false,"Selected rounds",(meters,unit)->{change.meters=meters;change.unit=unit;distance.setChecked(true);return true;},()->PracticeUi.distanceDialog(a,control[0]));control[0].setTag("bulk-distance");a.add(body,control[0]);
        LinearLayout quick=a.row();for(int yards:new int[]{10,15,20,25}){Button button=a.button(yards+" yd",()->control[0].commit(Practice.toMeters(yards,"yd"),"yd"));button.setTag("bulk-distance:"+yards);a.cell(quick,button);}a.add(body,quick);
        a.add(body,a.text("Only checked fields change. Per-arrow walk-up distances, photos and positions stay intact.",12,MainActivity.MUTED));
        TextView error=a.text("",13,0xFFFFBE91);error.setTag("bulk-error");a.add(body,error);
        ScrollView scroll=new ScrollView(a);scroll.addView(body);
        dialog=new AlertDialog.Builder(a,android.R.style.Theme_Material_Dialog_Alert).setTitle("Label "+snapshot.size()+" rounds").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Apply",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            change.shooter=shooter.isChecked();change.distance=distance.isChecked();
            try{int count=BulkLabels.apply(a.store,snapshot,change);a.bulkSelection.clear();a.bulkSelecting=false;a.bulkNotice=count==0?"Labels already match":count+" rounds labeled · saved offline";dialog.dismiss();a.home();}
            catch(Exception e){error.setText(e.getMessage());}
        }));dialog.show();
    }
}
