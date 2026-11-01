package org.redwoodarrows;

import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/** Native menus keep every shooter and recorded date directly reachable. */
final class LibraryFilters {
    static Spinner menu(MainActivity a,List<String> labels,int selected,String tag,String description,IntConsumer chosen){
        Spinner menu=new Spinner(a,Spinner.MODE_DROPDOWN);menu.setTag(tag);menu.setContentDescription(description);
        menu.setMinimumHeight(a.dp(44));pill(a,menu);
        menu.setPopupBackgroundDrawable(new ColorDrawable(MainActivity.PANEL));
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,labels){
            public View getView(int at,View old,ViewGroup parent){
                TextView text=(TextView)super.getView(at,old,parent);text.setTextColor(MainActivity.FG);text.setTextSize(15);text.setSingleLine();text.setEllipsize(TextUtils.TruncateAt.END);selected(a,text);return text;
            }
            public View getDropDownView(int at,View old,ViewGroup parent){
                TextView text=(TextView)super.getDropDownView(at,old,parent);text.setTextColor(MainActivity.FG);text.setTextSize(16);text.setSingleLine(false);text.setMaxLines(3);text.setEllipsize(TextUtils.TruncateAt.END);text.setMinHeight(a.dp(52));text.setPadding(a.dp(16),a.dp(10),a.dp(16),a.dp(10));return text;
            }
        };
        menu.setAdapter(adapter);menu.setSelection(Math.max(0,selected));menu.setEnabled(!labels.isEmpty());
        menu.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            int current=Math.max(0,selected);
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int at,long id){if(current==at)return;current=at;chosen.accept(at);}
        });return menu;
    }
    static Spinner shooter(MainActivity a,String tag,Runnable refresh){
        List<String> ids=new ArrayList<>(),labels=new ArrayList<>();ids.add(ShooterRoster.ALL);labels.add("All shooters");
        for(ShooterRoster.Person person:a.shooters.roster.all()){ids.add(person.id);labels.add(person.label());}
        ids.add("");labels.add("Unassigned");
        return menu(a,labels,ids.indexOf(a.shooters.filter),tag,"Shooter filter: "+a.shooters.filterLabel(),at->{
            if(!a.busy&&a.shooters.setFilter(ids.get(at)))refresh.run();
        });
    }
    static Spinner day(MainActivity a,List<Long> days,long selected,boolean all,String tag,LongConsumer chosen){
        List<Long> values=new ArrayList<>(days);values.sort(Collections.reverseOrder());
        List<String> labels=new ArrayList<>();SimpleDateFormat date=new SimpleDateFormat("MMM d, yyyy",Locale.US);
        if(all){values.add(0,0L);labels.add("All days");}
        for(long value:values)if(value!=0)labels.add(date.format(new Date(value)));
        if(values.isEmpty()){values.add(0L);labels.add("No sessions");}
        Spinner menu=menu(a,labels,values.indexOf(selected),tag,"Session date",at->chosen.accept(values.get(at)));
        menu.setEnabled(all||!days.isEmpty());return menu;
    }
    /** The values ("All shooters", "Oct 24, 2026", "15 yd") name themselves, so the label is spoken, not shown. */
    static LinearLayout labeled(MainActivity a,String label,View control){
        LinearLayout column=new LinearLayout(a);column.setOrientation(1);column.addView(control,new LinearLayout.LayoutParams(-1,a.dp(44)));
        if(control.getContentDescription()==null)control.setContentDescription(label);return column;
    }
    /** Dropdowns share the rounded panel shape of the app's buttons instead of Android's underlined default. */
    static void pill(MainActivity a,Spinner menu){menu.setBackground(ReviewControls.outline(a));menu.setPadding(a.dp(6),0,a.dp(4),0);}
    static void selected(MainActivity a,TextView text){android.graphics.drawable.Drawable chevron=HomeUi.drawable(a,"chevron-right",MainActivity.MUTED);android.graphics.drawable.RotateDrawable caret=new android.graphics.drawable.RotateDrawable();caret.setDrawable(chevron);caret.setFromDegrees(90);caret.setToDegrees(90);caret.setLevel(10000);caret.setBounds(0,0,a.dp(18),a.dp(18));
        text.setCompoundDrawables(null,null,caret,null);text.setCompoundDrawablePadding(a.dp(6));text.setPadding(a.dp(8),0,a.dp(4),0);text.setGravity(android.view.Gravity.CENTER_VERTICAL);}
}
