package org.redwoodarrows;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;

/** Group snapshots carry stable shooter IDs between phones; login is not shooter. */
final class ShootersUi {
    private final MainActivity a;private final SharedPreferences preferences;
    ShooterRoster roster=new ShooterRoster();private String loadError="";
    String filter=ShooterRoster.ALL;
    ShootersUi(MainActivity activity){
        a=activity;preferences=a.getPreferences(0);
        try{roster=ShooterRoster.from(preferences.getString("shooter_roster",""));}catch(Exception e){loadError="Shooter profiles could not be read. The saved profile data has been preserved; no profile changes will be written. "+e.getMessage();}
        // Group snapshots also work after restoring a group without preferences.
        for(Store.Group group:a.store.list())roster.remember(group.shooter);
        filter=preferences.getString("shooter_filter",ShooterRoster.ALL);
        if(!filter.equals(ShooterRoster.ALL)&&roster.find(filter)==null)filter=ShooterRoster.ALL;
    }
    ShooterRoster.Person active(){return roster.active();}
    String label(Store.Group group){return roster.resolve(group.shooter).label();}
    boolean commit(ShooterRoster changed){
        if(!loadError.isEmpty()){a.message(loadError);return false;}
        try{if(!preferences.edit().putString("shooter_roster",changed.json().toString()).commit())throw new IllegalStateException("Could not save shooter profiles");roster=changed;return true;}
        catch(Exception e){a.message(e.getMessage());return false;}
    }
    boolean selectActive(String id){
        if(a.busy)return false;
        try{ShooterRoster copy=roster.copy();copy.select(id);if(!commit(copy))return false;return preferences.edit().putString("capture_shooter_override",id).commit();}catch(Exception e){a.message(e.getMessage());return false;}
    }
    ShooterRoster.Person next(List<Store.Group> history){return CaptureDefaults.next(roster,history,preferences.contains("capture_shooter_override")?preferences.getString("capture_shooter_override",""):null);}
    ShooterRoster.Person next(){return next(a.store.list());}
    void captured(Store.Group group){preferences.edit().remove("capture_shooter_override").remove("practice_next:"+group.shooter.id).apply();}
    void pickForCapture(Consumer<ShooterRoster.Person> chosen){pick("Who shot this grouping?",ShooterRoster.UNASSIGNED,chosen);}
    void addHomeControls(LinearLayout body){
        a.add(body,a.text("SHOOTING AS",12,MainActivity.MUTED));
        List<ShooterRoster.Person> people=roster.all();
        if(people.size()==2&&next().assigned()){
            LinearLayout row=a.row();int current=people.get(0).id.equals(next().id)?0:1;
            TwoShooterSwitch toggle=new TwoShooterSwitch(a,people.get(0).name,people.get(1).name,current,index->{if(!selectActive(people.get(index).id))return false;View range=a.root.findViewWithTag("setup-distance");if(range instanceof Button)((Button)range).setText(PracticeUi.nextLabel(a)+" · setup");return true;});toggle.setTag("shooter-switch");
            row.addView(toggle,new LinearLayout.LayoutParams(0,-2,1));
            Button names=a.button("People",this::chooseActive);names.setContentDescription("Choose or manage shooters");
            LinearLayout.LayoutParams side=new LinearLayout.LayoutParams(a.dp(76),-2);side.setMargins(a.dp(6),0,0,0);row.addView(names,side);a.add(body,row);
        }else{
            Button choose=a.button(next().label()+" · choose shooter",this::chooseActive);choose.setTag("choose-shooter");a.add(body,choose);
            if(people.isEmpty())a.add(body,a.text("Add your names once. Each grouping keeps its shooter when you switch turns.",13,MainActivity.MUTED));
        }
    }
    void chooseActive(){pick("Shooting as",next(),p->{if(selectActive(p.id))a.refreshShooterLabels();});}
    void chooseForGroup(){pick("Who shot this grouping?",a.group.shooter,a::assignShooter);}
    private void pick(String title,ShooterRoster.Person selected,Consumer<ShooterRoster.Person> chosen){
        if(!loadError.isEmpty()){a.message(loadError);return;}
        List<ShooterRoster.Person> options=new ArrayList<>();options.add(ShooterRoster.UNASSIGNED);options.addAll(roster.all());
        String[] labels=new String[options.size()];int checked=0;
        for(int i=0;i<options.size();i++){ShooterRoster.Person p=options.get(i);labels[i]=p.label();if(p.id.equals(selected.id))checked=i;}
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle(title).setSingleChoiceItems(labels,checked,(d,which)->{d.dismiss();chosen.accept(options.get(which));})
            .setPositiveButton("Add shooter",(d,w)->editName(null,chosen)).setNeutralButton("Edit names",(d,w)->chooseRename()).setNegativeButton("Cancel",null).create();
        dialog.show();dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(!roster.all().isEmpty());
    }
    private void chooseRename(){
        List<ShooterRoster.Person> people=roster.all();String[] names=new String[people.size()];for(int i=0;i<names.length;i++)names[i]=people.get(i).name;
        new AlertDialog.Builder(a).setTitle("Rename a shooter").setItems(names,(d,which)->editName(people.get(which),p->a.refreshShooterLabels())).setNegativeButton("Cancel",null).show();
    }
    private void editName(ShooterRoster.Person existing,Consumer<ShooterRoster.Person> chosen){
        EditText input=new EditText(a);input.setSingleLine(true);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setHint("Name");input.setContentDescription("Shooter name");input.setText(existing==null?"":existing.name);input.selectAll();
        LinearLayout body=new LinearLayout(a);body.setPadding(a.dp(20),0,a.dp(20),0);body.addView(input,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle(existing==null?"Add shooter":"Rename shooter").setView(body)
            .setMessage(existing==null?"Sync first to see people already saved in the shared library. A grouping keeps the same shooter on both phones.":"This changes the display name on this phone. It does not assign their arrows to someone else.")
            .setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{ShooterRoster copy=roster.copy();ShooterRoster.Person person;
                if(existing==null)person=copy.add(input.getText().toString());else{copy.rename(existing.id,input.getText().toString());person=copy.find(existing.id);}
                if(commit(copy)){dialog.dismiss();chosen.accept(person);}
            }catch(Exception e){input.setError(e.getMessage());}
        }));dialog.show();input.requestFocus();dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }
    String filterLabel(){return filter.equals(ShooterRoster.ALL)?"All shooters":roster.find(filter)==null?"Unassigned":roster.find(filter).label();}
    void chooseFilter(List<Store.Group> groups){
        List<String> ids=new ArrayList<>();ids.add(ShooterRoster.ALL);ids.add("");for(ShooterRoster.Person p:roster.all())ids.add(p.id);
        String[] labels=new String[ids.size()];int selected=0;
        for(int i=0;i<ids.size();i++){
            String id=ids.get(i);int count=0;for(Store.Group g:groups)if(g.hasData()&&ShooterRoster.matches(id,g.shooter))count++;
            labels[i]=(id.equals(ShooterRoster.ALL)?"All shooters":roster.find(id).label())+" · "+count;if(id.equals(filter))selected=i;
        }
        new AlertDialog.Builder(a).setTitle("Saved groupings by shooter").setSingleChoiceItems(labels,selected,(d,which)->{
            if(preferences.edit().putString("shooter_filter",ids.get(which)).commit()){filter=ids.get(which);d.dismiss();a.home();}else a.message("Could not save the history filter");
        }).setNegativeButton("Cancel",null).show();
    }
}
