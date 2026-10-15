package org.redwoodarrows;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Offline, two-or-more shooter comparison; direct range filters, no data edits. */
final class ConsistencyUi {
    final MainActivity a;final ProgressUi owner;final SharedPreferences preferences;
    int mode;double distance;String setup;boolean checkedOnly,contextInitialized;
    long selection;ConsistencyStats data;ConsistencyChart chart;ScrollView scroll;
    LinearLayout readings;TextView selectedLabel,quality,rangeNote;AlertDialog options;
    static final int[] COLORS={0xFFC6C4EA,0xFFA4D0D0,0xFFE7C295,0xFFDDBAD0};
    ConsistencyUi(ProgressUi owner){this.owner=owner;a=owner.a;preferences=a.getSharedPreferences("consistency-view",0);
        mode=preferences.getInt("mode",ConsistencyStats.DAILY);if(mode!=0&&mode!=30&&mode!=100)mode=0;
        distance=Double.longBitsToDouble(preferences.getLong("distance",Double.doubleToLongBits(Double.NaN)));if(!Practice.distance(distance))distance=Double.NaN;
        setup=preferences.getString("setup","");contextInitialized=preferences.contains("setup");checkedOnly=preferences.getBoolean("checked",false);
    }
    void save(){preferences.edit().putInt("mode",mode).putLong("distance",Double.doubleToLongBits(distance)).putString("setup",setup).putBoolean("checked",checkedOnly).apply();}
    boolean optionsOpen(){return options!=null&&options.isShowing();}
    void show(){
        int oldScroll=a.page.equals("progress")&&scroll!=null?scroll.getScrollY():0;
        a.rememberGroups();a.page="progress";a.canvas=null;
        List<Store.Group> groups=a.store.list();a.shooters=new ShootersUi(a);
        if(!contextInitialized){for(Store.Group group:groups)if(group.hasData()&&group.shooter.assigned()){setup=group.practice.setup;break;}contextInitialized=true;}
        long previousLatest=data==null?0:data.latest();boolean followLatest=selection==0||selection==previousLatest;
        data=new ConsistencyStats(groups,a.shooters.roster.all(),setup,checkedOnly,distance,mode);
        selection=followLatest?data.latest():data.nearest(selection);
        owner.header(true);
        LinearLayout description=a.row();TextView title=a.text("Angular consistency",17,MainActivity.FG);title.setTypeface(null,1);description.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button filters=a.button("Filters",this::filters);filters.setTag("comparison-filters");filters.setTextSize(12);description.addView(filters,new LinearLayout.LayoutParams(a.dp(66),a.dp(44)));a.add(a.root,description);
        HorizontalScrollView ranges=new HorizontalScrollView(a);ranges.setHorizontalScrollBarEnabled(false);ranges.setTag("comparison-distances");LinearLayout choices=a.row();ranges.addView(choices);
        choice(choices,"All ranges",!Practice.distance(distance),"comparison-distance:all",()->changeDistance(Double.NaN));
        TreeSet<Long> distances=new TreeSet<>(data.distanceChoices);if(Practice.distance(distance))distances.add(ConsistencyStats.rangeKey(distance));
        for(long value:distances){double meters=value/1000.;choice(choices,rangeLabel(meters),Practice.distance(distance)&&Math.abs(distance-meters)<.001,"comparison-distance:"+value,()->changeDistance(meters));}a.add(a.root,ranges);
        LinearLayout modes=a.row();for(int value:new int[]{0,30,100}){
            Button button=choiceButton(value==0?"Daily":value+" arrows",mode==value,()->{if(mode!=value){mode=value;selection=0;save();owner.show();}});
            button.setTag("comparison-mode:"+value);button.setContentDescription((value==0?"Daily consistency":"Rolling "+value+" observed arrows, whole rounds")+(mode==value?", selected":""));a.cell(modes,button);
        }a.add(a.root,modes);
        scroll=new ScrollView(a);scroll.setTag("comparison-scroll");LinearLayout body=new LinearLayout(a);body.setOrientation(1);scroll.addView(body);a.root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView unit=a.text("mrad · lower is tighter"+(mode==0?" · one point per day":" · rolling whole rounds"),12,MainActivity.MUTED);unit.setTag("comparison-unit");a.add(body,unit);
        chart=new ConsistencyChart(a,data,selection,this::select);chart.setTag("comparison-chart");body.addView(chart,new LinearLayout.LayoutParams(-1,a.dp(285)));
        LinearLayout navigation=a.row();Button previous=a.button("‹",()->step(-1));previous.setTag("comparison-previous");previous.setContentDescription("Previous plotted date or round");navigation.addView(previous,new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));
        selectedLabel=a.text("",15,MainActivity.FG);selectedLabel.setGravity(Gravity.CENTER);selectedLabel.setTag("comparison-selected");navigation.addView(selectedLabel,new LinearLayout.LayoutParams(0,a.dp(44),1));
        Button next=a.button("›",()->step(1));next.setTag("comparison-next");next.setContentDescription("Next plotted date or round");navigation.addView(next,new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));a.add(body,navigation);
        readings=new LinearLayout(a);readings.setOrientation(1);readings.setTag("comparison-readings");a.add(body,readings);
        rangeNote=a.text("",12,MainActivity.MUTED);rangeNote.setTag("comparison-range-note");a.add(body,rangeNote);
        quality=a.text("",12,0xFFFFBE91);quality.setTag("comparison-quality");a.add(body,quality);
        int missing=data.missingDistance;TextView excluded=a.text((missing>0?missing+" rounds need distances. ":"")+(data.unconfirmed>0?data.unconfirmed+" unconfirmed owners. ":"")+(data.mixedDistance>0?data.mixedDistance+" walk-up rounds in Details. ":"")+(data.unchecked>0?data.unchecked+" unchecked rounds filtered. ":"")+(data.unsupported>0?data.unsupported+" unsupported paper faces. ":""),12,MainActivity.MUTED);excluded.setTag("comparison-excluded");if(!excluded.getText().toString().isEmpty())a.add(body,excluded);
        if(data.series.isEmpty())a.add(body,a.text("Add shooter names in the gallery to compare their progress.",14,MainActivity.MUTED));
        a.add(body,a.text("Setup: "+(setup.isEmpty()?"unrecorded":setup)+" · "+(checkedOnly?"checked positions":"all estimates"),12,MainActivity.MUTED));
        select(selection);if(oldScroll>0)scroll.post(()->scroll.scrollTo(0,oldScroll));
    }
    Button choiceButton(String text,boolean active,Runnable action){Button button=a.button(text,action);button.setTextSize(13);button.setSelected(active);button.setBackground(a.rounded(active?MainActivity.ACCENT:MainActivity.PANEL));button.setTextColor(active?MainActivity.BG:MainActivity.FG);return button;}
    void choice(LinearLayout row,String label,boolean selected,String tag,Runnable action){Button button=choiceButton(label,selected,action);button.setTag(tag);button.setContentDescription("Distance filter: "+label+(selected?", selected":""));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,a.dp(44));lp.setMargins(0,0,a.dp(6),0);row.addView(button,lp);}
    void changeDistance(double value){if((Double.isNaN(value)&&Double.isNaN(distance))||value==distance)return;distance=value;selection=0;save();owner.show();}
    static String rangeLabel(double meters){return TargetPreset.number(Practice.fromMeters(meters,"yd"))+" yd";}
    static String ranges(ConsistencyStats.Sample sample){List<String> values=new ArrayList<>();for(long key:sample.ranges)values.add(rangeLabel(key/1000.));return String.join(" + ",values);}
    void step(int delta){List<Long> times=new ArrayList<>(data.times);int at=times.indexOf(selection);if(at<0||times.isEmpty())return;select(times.get(Math.max(0,Math.min(times.size()-1,at+delta))));}
    void select(long time){
        selection=time;chart.select(time);readings.removeAllViews();
        selectedLabel.setText(time==0?"No dated measurements":new SimpleDateFormat(mode==0?"EEE, MMM d · yyyy":"MMM d · h:mm a",Locale.US).format(new Date(time)));
        View prev=a.root.findViewWithTag("comparison-previous"),next=a.root.findViewWithTag("comparison-next");if(prev!=null)prev.setEnabled(!data.times.isEmpty()&&time>data.times.first());if(next!=null)next.setEnabled(!data.times.isEmpty()&&time<data.times.last());
        LinearLayout row=null;int unlocated=0,unchecked=0,misses=0,shortRounds=0;boolean countsKnown=true,warmup=false;Set<String> rangeKeys=new HashSet<>();
        for(int i=0;i<data.series.size();i++){
            ConsistencyStats.Series series=data.series.get(i);ConsistencyStats.Sample sample=data.at(series,time);
            if(i%2==0){row=a.row();a.add(readings,row);}LinearLayout card=new LinearLayout(a);card.setOrientation(1);card.setPadding(a.dp(12),a.dp(6),a.dp(12),a.dp(8));card.setBackground(a.rounded(MainActivity.PANEL));a.cell(row,card);
            String marker=i%2==0?"● ":"■ ";TextView name=a.text(marker+series.person.label(),14,COLORS[i%COLORS.length]);name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(name);
            TextView value=a.text(sample==null?"—":ProgressUi.fmt(sample.spread)+" mrad",26,MainActivity.FG);value.setTypeface(null,1);value.setTag("comparison-value:"+series.person.id);card.addView(value);
            String coverage=sample==null?"No data for this date":sample.located+" located · "+sample.ends.size()+" rounds";
            if(sample!=null&&mode!=0)coverage+="\nThrough "+new SimpleDateFormat("MMM d, h:mm a",Locale.US).format(new Date(sample.time));
            TextView counts=a.text(coverage,11,MainActivity.MUTED);counts.setTag("comparison-count:"+series.person.id);card.addView(counts);
            if(sample!=null){unlocated+=sample.unlocated;unchecked+=sample.unchecked;misses+=sample.misses;countsKnown&=sample.countsKnown;shortRounds+=sample.ends.size()-sample.usableRounds;warmup|=mode>0&&sample.records<mode;rangeKeys.add(sample.rangeKey);}
        }
        String note=Practice.distance(distance)?rangeLabel(distance)+" only":("All ranges · equal weight per distance"+(rangeKeys.size()>1?" · range mix differs":""));
        if(!Practice.distance(distance)){for(ConsistencyStats.Series series:data.series){ConsistencyStats.Sample sample=data.at(series,time);if(sample!=null){note+="\n"+series.person.label()+": "+ranges(sample);}}}
        rangeNote.setText(note);
        String warning=(warmup?"Hollow dots: building the selected arrow window. ":"")+(unlocated>0?unlocated+" unlocated arrows excluded. ":"")+(misses>0?misses+" reported misses, not plotted. ":"")+(shortRounds>0?shortRounds+" rounds lack two located arrows. ":"")+(unchecked>0?"Unchecked estimates. ":"")+(!countsKnown?"Total shots/misses unknown. ":"");
        quality.setText(warning.isEmpty()?"Located positions only · spread, not measurement accuracy.":warning.trim());
    }
    void filters(){
        LinearLayout body=new LinearLayout(a);body.setOrientation(1);body.setPadding(a.dp(20),a.dp(8),a.dp(20),a.dp(8));
        body.addView(a.text("Equipment setup",14,MainActivity.FG));Spinner gear=new Spinner(a);List<String> values=new ArrayList<>(data.setups);if(!values.contains(setup))values.add(0,setup);List<String> labels=new ArrayList<>();for(String s:values)labels.add(s.isEmpty()?"Unrecorded setup":s);ArrayAdapter<String> adapter=new ArrayAdapter<>(a,android.R.layout.simple_spinner_dropdown_item,labels);gear.setAdapter(adapter);gear.setSelection(values.indexOf(setup));gear.setTag("comparison-setup");body.addView(gear,new LinearLayout.LayoutParams(-1,a.dp(48)));
        final boolean[] check={checkedOnly};TwoShooterSwitch review=new TwoShooterSwitch(a,"All estimates","Checked only",checkedOnly?1:0,"Position review filter",i->{check[0]=i==1;return true;});review.setTag("comparison-review");body.addView(review,new LinearLayout.LayoutParams(-1,a.dp(48)));
        body.addView(a.text("Distances are filters, not edits. Unknown distances and unconfirmed owners stay out of this graph.",13,MainActivity.MUTED));
        options=new AlertDialog.Builder(a).setTitle("Comparison filters").setView(body).setNegativeButton("Cancel",null).setPositiveButton("Apply",(d,w)->{setup=values.get(gear.getSelectedItemPosition());checkedOnly=check[0];save();selection=0;}).create();
        options.setOnDismissListener(d->{options=null;if(a.page.equals("progress")&&owner.comparing)owner.show();});options.show();
    }
    void help(){a.message("Angular consistency is pooled within-round radial standard deviation: square-root of the sum of squared distances from each round’s mean, divided by the sum of (located arrows − 1). Coordinates are divided by the entered shooting distance to give mrad. 1 mrad ≈ 3.44 MOA. Lower is tighter; this is not standard error, a full group diameter, or bullseye accuracy.\n\nAll ranges gives each represented distance equal weight in variance. Line segments stop when the range mix changes; filter a single range for like-for-like trends. Equipment setups stay separate.\n\nDaily uses all eligible rounds that calendar day. 30/100-arrow views end at each round and retain whole rounds; overlapping windows are not independent. Hollow points have fewer than three usable rounds or are still building the selected window. Tap or drag horizontally on the chart, or use the arrows, to inspect dates. Rolling readouts show each shooter’s most recent point at or before the selected time.\n\nOne photo per arrow is not required: repeated views count once. Unlocated arrows and reported misses have no invented coordinates. Unknown distances, unconfirmed owners and mixed-distance walk-up rounds are excluded; the latter remain in Details. All estimates are exploratory. Photo calibration and intended-target assignments can be wrong, and apparent changes are not proof of lasting improvement.\n\nThe chart is computed offline from your saved groupings and refreshes after shared edits sync.");}
}
