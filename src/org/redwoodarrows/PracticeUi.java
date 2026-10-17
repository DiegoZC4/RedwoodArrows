package org.redwoodarrows;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.util.*;

/** Direct distance controls, with per-shooter defaults for new captures. */
final class PracticeUi {
    static Practice forPerson(MainActivity a,ShooterRoster.Person person,List<Store.Group> history)throws Exception {String raw=a.getPreferences(0).getString("practice_next:"+person.id,"");return raw.isEmpty()?CaptureDefaults.previous(person,history):Practice.from(new JSONObject(raw)).next();}
    static Practice next(MainActivity a)throws Exception {List<Store.Group> history=a.store.list();return forPerson(a,a.shooters.next(history),history);}
    static String nextLabel(MainActivity a){try{return next(a).label();}catch(Exception e){return "Check distance setting";}}
    static void nextDialog(MainActivity a){try{show(a,null,next(a),()->{if(a.page.equals("capture-setup"))a.captureSetup();else a.home();});}catch(Exception e){a.message("Saved range settings could not be read; they have been preserved. "+e.getMessage());}}
    static void groupDialog(MainActivity a,Store.Group g){show(a,g,g.practice,()->a.results());}
    static View inline(MainActivity a,Store.Group g,String caption,Runnable saved){
        try{
            List<Store.Group> history=g==null?a.store.list():Collections.emptyList();ShooterRoster.Person person=g==null?a.shooters.next(history):g.shooter;
            Practice source=g==null?forPerson(a,person,history):g.practice;final DistanceControl[] control=new DistanceControl[1];
            control[0]=new DistanceControl(a,source.meters,source.unit,!source.arrowMeters.isEmpty(),caption,(meters,unit)->{
                if(a.busy)return false;
                try{
                    Practice p=g==null?source.next():Practice.from(g.practice.json());p.meters=meters;p.unit=unit;
                    if(g==null){if(!a.getPreferences(0).edit().putString("practice_next:"+person.id,p.json().toString()).commit())throw new IllegalStateException("Could not save next distance");}
                    else{Store.Group changed=a.store.updatePractice(g,p);g.practice=changed.practice;g.diskDigest=changed.diskDigest;if(a.group!=null&&a.group.id.equals(g.id)){a.group=g;a.undo.clear();a.analysis=null;}}
                    if(saved!=null)saved.run();return true;
                }catch(Exception e){a.message("Could not save distance: "+e.getMessage());return false;}
            },()->distanceDialog(a,control[0]));return control[0];
        }catch(Exception e){Button error=a.button("Check distance",()->a.message("Saved distance could not be read; it has been preserved. "+e.getMessage()));return error;}
    }
    /** Optional single-step exact entry; everyday changes need no dialog. */
    static AlertDialog distanceDialog(MainActivity a,DistanceControl control){
        LinearLayout body=new LinearLayout(a);body.setOrientation(1);body.setPadding(a.dp(20),a.dp(8),a.dp(20),a.dp(8));body.setBackgroundColor(MainActivity.BG);
        String[] unit={control.unit()};EditText value=field(a,body,"Exact distance · blank = unknown",Practice.distance(control.meters())?number(Practice.fromMeters(control.meters(),unit[0])):"","inline-distance-value",true);
        TwoShooterSwitch units=new TwoShooterSwitch(a,"Yards","Meters",unit[0].equals("yd")?0:1,"Distance units",choice->{try{double meters=parseDistance(value,unit[0]);unit[0]=choice==0?"yd":"m";if(Practice.distance(meters))value.setText(number(Practice.fromMeters(meters,unit[0])));return true;}catch(Exception e){value.setError("Fix the distance before switching units");return false;}});units.setTag("inline-distance-units");a.add(body,units);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Distance").setView(body).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{if(control.commit(parseDistance(value,unit[0]),unit[0]))dialog.dismiss();}catch(Exception e){value.setError("Enter a positive distance up to 1,000 meters, or leave blank");}}));dialog.show();value.selectAll();value.requestFocus();dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);return dialog;
    }
    static String number(double n){return String.format(Locale.US,"%.6f",n).replaceAll("0+$","").replaceAll("\\.$","");}
    static double parseDistance(EditText input,String unit){String value=input.getText().toString().trim();if(value.isEmpty())return Double.NaN;double meters=Practice.toMeters(Double.parseDouble(value),unit);if(!Practice.distance(meters))throw new IllegalArgumentException("Enter a positive distance up to 1,000 meters, or leave it blank");return meters;}
    static int count(EditText input){String s=input.getText().toString().trim();return s.isEmpty()?-1:Integer.parseInt(s);}
    static EditText field(MainActivity a,LinearLayout body,String label,String value,String tag,boolean numeric){
        TextView title=a.text(label,12,MainActivity.MUTED);body.addView(title);EditText input=new EditText(a);input.setTextColor(MainActivity.FG);input.setHintTextColor(MainActivity.MUTED);input.setSingleLine(true);input.setInputType(numeric?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);input.setText(value);input.setTag(tag);input.setContentDescription(label);body.addView(input,new LinearLayout.LayoutParams(-1,a.dp(48)));return input;
    }
    static AlertDialog show(MainActivity a,Store.Group g,Practice source,Runnable done){
        final String futureKey=g==null?"practice_next:"+a.shooters.next().id:"";
        LinearLayout body=new LinearLayout(a);body.setOrientation(1);body.setPadding(a.dp(18),a.dp(8),a.dp(18),a.dp(12));body.setBackgroundColor(MainActivity.BG);ScrollView scroll=new ScrollView(a);scroll.addView(body);
        String[] unit={source.unit};EditText distance=field(a,body,"Marked shooting distance · blank = unknown",Practice.distance(source.meters)?number(Practice.fromMeters(source.meters,unit[0])):"","practice-distance",true);
        Map<Integer,EditText> perArrow=new LinkedHashMap<>();
        TwoShooterSwitch units=new TwoShooterSwitch(a,"Yards","Meters",unit[0].equals("yd")?0:1,"Distance units",choice->{
            String next=choice==0?"yd":"m";try{double base=parseDistance(distance,unit[0]);Map<EditText,Double> converted=new LinkedHashMap<>();for(EditText field:perArrow.values())converted.put(field,parseDistance(field,unit[0]));
                if(Practice.distance(base))distance.setText(number(Practice.fromMeters(base,next)));for(Map.Entry<EditText,Double> e:converted.entrySet())if(Practice.distance(e.getValue()))e.getKey().setText(number(Practice.fromMeters(e.getValue(),next)));unit[0]=next;return true;
            }catch(Exception e){distance.setError("Fix the distance before switching units");return false;}
        });units.setTag("practice-units");a.add(body,units);
        EditText setup=field(a,body,"Bow / arrow setup · optional",source.setup,"practice-setup",false);setup.setHint("e.g. Recurve · Easton Vector");
        EditText fired=null,misses=null;CheckBox checked=null;
        if(g!=null){
            int located=0;List<Store.Result> rs=Store.results(g);for(Store.Result r:rs)if(Double.isFinite(r.x)&&Double.isFinite(r.y))located++;
            a.add(body,a.text(located+" plotted · "+(rs.size()-located)+" unresolved",13,MainActivity.MUTED));
            fired=field(a,body,"Arrows shot · blank = unknown",source.fired<0?"":""+source.fired,"practice-fired",true);
            misses=field(a,body,"Misses with no coordinates · blank = unknown",source.misses<0?"":""+source.misses,"practice-misses",true);
            checked=new CheckBox(a);checked.setText("I checked positions and accounted for every arrow");checked.setTextColor(MainActivity.FG);checked.setTag("practice-checked");checked.setChecked(source.isChecked(g));a.add(body,checked);
            if(!rs.isEmpty()){
                LinearLayout overrides=new LinearLayout(a);overrides.setOrientation(1);overrides.setVisibility(source.arrowMeters.isEmpty()?View.GONE:View.VISIBLE);
                Button walk=a.button("Walk-up distances",()->overrides.setVisibility(overrides.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));walk.setTag("practice-walkup");a.add(body,walk);
                overrides.addView(a.text("Same units as above. Blank uses the end distance. Arrow IDs are not firing order.",12,MainActivity.MUTED));
                for(Store.Result r:rs){Double value=source.arrowMeters.get(r.id);perArrow.put(r.id,field(a,overrides,"Arrow #"+r.id,value==null?"":number(Practice.fromMeters(value,unit[0])),"practice-arrow:"+r.id,true));}body.addView(overrides);
            }
        }else a.add(body,a.text("Remembered for new ends only. Recheck when you change shooting position. Older groupings are untouched.",13,MainActivity.MUTED));
        TextView error=a.text("",13,0xFFFFBE91);error.setTag("practice-error");a.add(body,error);
        final EditText firedInput=fired,missInput=misses;final CheckBox reviewed=checked;
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle(g==null?"Next shooting distance":"Distance & shot check").setView(scroll).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            Practice p=new Practice();p.meters=parseDistance(distance,unit[0]);p.unit=unit[0];p.setup=setup.getText().toString().trim();p.validate();
            if(g==null){if(!a.getPreferences(0).edit().putString(futureKey,p.json().toString()).commit())throw new IllegalStateException("Could not save range settings");}
            else{p.fired=count(firedInput);p.misses=count(missInput);for(Map.Entry<Integer,EditText> e:perArrow.entrySet()){double meters=parseDistance(e.getValue(),unit[0]);if(Practice.distance(meters))p.arrowMeters.put(e.getKey(),meters);}p.validateCounts(g);if(reviewed.isChecked())p.markChecked(g);a.group=a.store.updatePractice(g,p);a.undo.clear();a.analysis=null;}
            dialog.dismiss();done.run();
        }catch(Exception e){error.setText(e instanceof NumberFormatException?"Use numbers for distance and whole numbers for arrow counts.":e.getMessage());}}));dialog.show();return dialog;
    }
}
