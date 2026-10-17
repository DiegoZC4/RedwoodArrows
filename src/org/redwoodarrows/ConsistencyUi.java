package org.redwoodarrows;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Future;

/** Offline, two-or-more shooter comparison; direct range filters, no data edits. */
final class ConsistencyUi {
    final MainActivity a;final ProgressUi owner;final SharedPreferences preferences;
    int mode=ConsistencyStats.DAILY;double distance;String setup;boolean checkedOnly,contextInitialized,showTrend;
    Future<?> intervalTask;TextView unit;
    long selection;ConsistencyStats data;ConsistencyChart chart;ScrollView scroll;
    LinearLayout readings;TextView selectedLabel,quality,rangeNote;AlertDialog options;
    static final int[] COLORS={0xFFC6C4EA,0xFFA4D0D0,0xFFE7C295,0xFFDDBAD0};
    ConsistencyUi(ProgressUi owner){this.owner=owner;a=owner.a;preferences=a.getSharedPreferences("consistency-view",0);
        showTrend=preferences.getBoolean("trend100",preferences.getInt("mode",0)==100);
        distance=Double.longBitsToDouble(preferences.getLong("distance",Double.doubleToLongBits(Double.NaN)));if(!Practice.distance(distance))distance=Double.NaN;
        setup=preferences.getString("setup","");contextInitialized=preferences.contains("setup");checkedOnly=preferences.getBoolean("checked",false);
    }
    void save(){preferences.edit().remove("mode").putBoolean("trend100",showTrend).putLong("distance",Double.doubleToLongBits(distance)).putString("setup",setup).putBoolean("checked",checkedOnly).apply();}
    void cancelIntervals(){if(intervalTask!=null){intervalTask.cancel(true);intervalTask=null;}}
    boolean optionsOpen(){return options!=null&&options.isShowing();}
    void show(){
        cancelIntervals();
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
        TwoShooterSwitch trend=new TwoShooterSwitch(a,"Daily","Daily + 100",showTrend?1:0,"100-arrow trend overlay",i->{showTrend=i==1;save();chart.showTrend(showTrend);unit.setText(legend());select(selection);return true;});trend.setTag("comparison-trend");a.add(a.root,trend);
        scroll=new ScrollView(a);scroll.setTag("comparison-scroll");LinearLayout body=new LinearLayout(a);body.setOrientation(1);scroll.addView(body);a.root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        unit=a.text(legend(),12,MainActivity.MUTED);unit.setTag("comparison-unit");a.add(body,unit);
        chart=new ConsistencyChart(a,data,selection,this::select);chart.showTrend(showTrend);chart.setTag("comparison-chart");body.addView(chart,new LinearLayout.LayoutParams(-1,a.dp(285)));
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
        final ConsistencyStats calculated=data;
        intervalTask=a.worker.submit(()->{calculated.computeIntervals();a.runOnUiThread(()->{
            if(!a.isDestroyed()&&a.page.equals("progress")&&owner.comparing&&data==calculated)select(selection);
        });});
    }
    String legend(){return "mrad · lower is tighter · daily dots + 95% CI"+(showTrend?"\nLines: last 100 recorded arrows · overlapping windows":"")+"\nHollow: insufficient data or interval pending";}
    Button choiceButton(String text,boolean active,Runnable action){Button button=a.button(text,action);button.setTextSize(13);button.setSelected(active);button.setBackground(a.rounded(active?MainActivity.ACCENT:MainActivity.PANEL));button.setTextColor(active?MainActivity.BG:MainActivity.FG);return button;}
    void choice(LinearLayout row,String label,boolean selected,String tag,Runnable action){Button button=choiceButton(label,selected,action);button.setTag(tag);button.setContentDescription("Distance filter: "+label+(selected?", selected":""));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,a.dp(44));lp.setMargins(0,0,a.dp(6),0);row.addView(button,lp);}
    void changeDistance(double value){if((Double.isNaN(value)&&Double.isNaN(distance))||value==distance)return;distance=value;selection=0;save();owner.show();}
    static String rangeLabel(double meters){return TargetPreset.number(Practice.fromMeters(meters,"yd"))+" yd";}
    static String ranges(ConsistencyStats.Sample sample){List<String> values=new ArrayList<>();for(long key:sample.ranges)values.add(rangeLabel(key/1000.));return String.join(" + ",values);}
    void step(int delta){List<Long> times=new ArrayList<>(data.times);int at=times.indexOf(selection);if(at<0||times.isEmpty())return;select(times.get(Math.max(0,Math.min(times.size()-1,at+delta))));}
    void select(long time){
        selection=time;chart.select(time);readings.removeAllViews();
        selectedLabel.setText(time==0?"No dated measurements":new SimpleDateFormat("EEE, MMM d · yyyy",Locale.US).format(new Date(time)));
        View prev=a.root.findViewWithTag("comparison-previous"),next=a.root.findViewWithTag("comparison-next");if(prev!=null)prev.setEnabled(!data.times.isEmpty()&&time>data.times.first());if(next!=null)next.setEnabled(!data.times.isEmpty()&&time<data.times.last());
        LinearLayout row=null;int unlocated=0,unchecked=0,misses=0,shortRounds=0;boolean countsKnown=true;Set<String> rangeKeys=new HashSet<>();
        for(int i=0;i<data.series.size();i++){
            ConsistencyStats.Series series=data.series.get(i);ConsistencyStats.Sample sample=data.at(series,time);
            if(i%2==0){row=a.row();a.add(readings,row);}LinearLayout card=new LinearLayout(a);card.setOrientation(1);card.setPadding(a.dp(12),a.dp(6),a.dp(12),a.dp(8));card.setBackground(a.rounded(MainActivity.PANEL));a.cell(row,card);
            String marker=i%2==0?"● ":"■ ";TextView name=a.text(marker+series.person.label(),14,COLORS[i%COLORS.length]);name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(name);
            TextView value=a.text(sample==null?"—":ProgressUi.fmt(sample.spread)+" mrad",26,MainActivity.FG);value.setTypeface(null,1);value.setTag("comparison-value:"+series.person.id);card.addView(value);
            TextView interval=a.text(intervalLabel(sample),12,MainActivity.MUTED);interval.setTag("comparison-ci:"+series.person.id);card.addView(interval);
            String coverage=sample==null?"No data for this date":sample.located+" located · "+sample.usableRounds+" usable rounds";
            TextView counts=a.text(coverage,11,MainActivity.MUTED);counts.setTag("comparison-count:"+series.person.id);card.addView(counts);
            if(showTrend&&sample!=null){ConsistencyStats.Sample rolling=data.trendAt(series,time);String trendLabel=ConsistencyStats.trendReady(rolling)?"100-arrow trend: "+ProgressUi.fmt(rolling.spread)+" mrad\n"+rolling.records+" recorded · "+rolling.located+" located":"100-arrow trend: insufficient data";TextView trend=a.text(trendLabel,11,MainActivity.MUTED);trend.setTag("comparison-trend-value:"+series.person.id);card.addView(trend);}
            if(sample!=null){unlocated+=sample.unlocated;unchecked+=sample.unchecked;misses+=sample.misses;countsKnown&=sample.countsKnown;shortRounds+=sample.ends.size()-sample.usableRounds;rangeKeys.add(sample.rangeKey);}
        }
        String note=Practice.distance(distance)?rangeLabel(distance)+" only":("All ranges · equal weight per distance"+(rangeKeys.size()>1?" · range mix differs":""));
        if(!Practice.distance(distance)){for(ConsistencyStats.Series series:data.series){ConsistencyStats.Sample sample=data.at(series,time);if(sample!=null){note+="\n"+series.person.label()+": "+ranges(sample);}}}
        rangeNote.setText(note);
        String warning=(unlocated>0?unlocated+" unlocated arrows excluded. ":"")+(misses>0?misses+" reported misses, not plotted. ":"")+(shortRounds>0?shortRounds+" rounds lack two located arrows. ":"")+(unchecked>0?"Unchecked estimates. ":"")+(!countsKnown?"Total shots/misses unknown. ":"");
        quality.setText((warning+"Intervals exclude photo-calibration error.").trim());
    }
    static String intervalLabel(ConsistencyStats.Sample sample){
        if(sample==null)return "";ConsistencyStats.Interval ci=sample.interval;
        if(ci.available())return "95% CI "+ProgressUi.fmt(ci.lower)+"–"+ProgressUi.fmt(ci.upper)+" mrad";
        if(ci.state==ConsistencyStats.Interval.PENDING)return "Estimating 95% CI…";
        if(ci.state==ConsistencyStats.Interval.DEGENERATE)return "Insufficient data\nNo between-round variation to estimate CI";
        return "Insufficient data for 95% CI\n"+sample.minRoundsPerDistance+" / "+ConsistencyStats.MIN_INTERVAL_ROUNDS+" usable rounds"+(sample.ranges.size()>1?" at the least-sampled distance":" at this distance");
    }
    void filters(){
        LinearLayout body=new LinearLayout(a);body.setOrientation(1);body.setPadding(a.dp(20),a.dp(8),a.dp(20),a.dp(8));
        body.addView(a.text("Equipment setup",14,MainActivity.FG));Spinner gear=new Spinner(a);List<String> values=new ArrayList<>(data.setups);if(!values.contains(setup))values.add(0,setup);List<String> labels=new ArrayList<>();for(String s:values)labels.add(s.isEmpty()?"Unrecorded setup":s);ArrayAdapter<String> adapter=new ArrayAdapter<>(a,android.R.layout.simple_spinner_dropdown_item,labels);gear.setAdapter(adapter);gear.setSelection(values.indexOf(setup));gear.setTag("comparison-setup");body.addView(gear,new LinearLayout.LayoutParams(-1,a.dp(48)));
        final boolean[] check={checkedOnly};TwoShooterSwitch review=new TwoShooterSwitch(a,"All estimates","Checked only",checkedOnly?1:0,"Position review filter",i->{check[0]=i==1;return true;});review.setTag("comparison-review");body.addView(review,new LinearLayout.LayoutParams(-1,a.dp(48)));
        body.addView(a.text("Distances are filters, not edits. Unknown distances and unconfirmed owners stay out of this graph.",13,MainActivity.MUTED));
        options=new AlertDialog.Builder(a).setTitle("Comparison filters").setView(body).setNegativeButton("Cancel",null).setPositiveButton("Apply",(d,w)->{setup=values.get(gear.getSelectedItemPosition());checkedOnly=check[0];save();selection=0;}).create();
        options.setOnDismissListener(d->{options=null;if(a.page.equals("progress")&&owner.comparing)owner.show();});options.show();
    }
    void help(){a.message("Daily dots show pooled within-round radial standard deviation, in mrad. Each round is centered on its own average impact before pooling squared deviations with (located arrows − 1) degrees of freedom. This measures grouping tightness, not bullseye accuracy or between-round aim drift. More shots do not automatically lower this number.\n\nWhiskers are approximate 95% percentile bootstrap confidence intervals for that estimated spread, not a region containing 95% of arrows. We resample whole usable rounds with replacement 4,096 times, separately within each distance, and take the 2.5th and 97.5th percentiles. Arrows and repeated photos are never independently resampled. All ranges keeps equal weight per distance in both the estimate and every resample. The seeded calculation is repeatable and runs offline in the background.\n\nAt least "+ConsistencyStats.MIN_INTERVAL_ROUNDS+" usable rounds at EACH included distance are required. A usable round has at least two located arrows. This is a minimum guardrail, not a guarantee of coverage. Fewer rounds, or no observed variation across rounds, show Insufficient data and a hollow provisional dot with no interval. Intervals assume comparable, independent rounds; they cannot account for persistent fatigue, changing conditions, omitted misses or systematic photo-calibration errors.\n\nDaily means one calendar day. Daily + 100 adds a trend sampled at the end of each shooting day using the last approximately 100 recorded arrows, keeping whole rounds. Partial windows are not plotted. Trend windows overlap and are not independent evidence of improvement; no confidence band is claimed for the trend. Lines break when the included distance mix changes. Filter one distance and equipment setup for the fairest comparisons. Tap or swipe horizontally to inspect a date.\n\nRepeated views count once. Unlocated arrows and reported misses have no invented coordinates. Unknown distances, unconfirmed owners and mixed-distance walk-up rounds stay out of this graph. Missing days are not filled or carried forward. Shared corrections refresh the local calculation; sign-in and saved groupings are unchanged.");}
}
