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
    LinearLayout readings;TextView quality,rangeNote,pending;Button labelRounds;AlertDialog options;Spinner dateMenu;
    List<Long> dateValues=Collections.emptyList();
    ConsistencyUi(ProgressUi owner){this.owner=owner;a=owner.a;preferences=a.getSharedPreferences("consistency-view",0);
        showTrend=preferences.getBoolean("trend100",preferences.getInt("mode",0)==100);
        distance=Double.longBitsToDouble(preferences.getLong("distance",Double.doubleToLongBits(Double.NaN)));if(!Practice.distance(distance))distance=Double.NaN;
        setup=preferences.getString("setup","");contextInitialized=preferences.contains("setup");checkedOnly=false;
    }
    void save(){preferences.edit().remove("mode").putBoolean("trend100",showTrend).putLong("distance",Double.doubleToLongBits(distance)).putString("setup",setup).putBoolean("checked",checkedOnly).apply();}
    void cancelIntervals(){if(intervalTask!=null){intervalTask.cancel(true);intervalTask=null;}}
    boolean optionsOpen(){return options!=null&&options.isShowing();}
    void show(){
        cancelIntervals();
        int oldScroll=a.page.equals("progress")&&scroll!=null?scroll.getScrollY():0;
        a.rememberGroups();a.page="progress";a.canvas=null;
        a.shooters=new ShootersUi(a);List<Store.Group> groups=LibraryFilter.groups(a.libraryGroups(),a.shooters.filter);
        if(!contextInitialized){for(Store.Group group:groups)if(group.hasData()&&group.shooter.assigned()){setup=group.practice.setup;break;}contextInitialized=true;}
        long previousLatest=data==null?0:data.latest();boolean followLatest=selection==0||selection==previousLatest;
        data=new ConsistencyStats(groups,LibraryFilter.people(a.shooters.roster.all(),a.shooters.filter),setup,checkedOnly,distance,mode);
        selection=followLatest?data.latest():data.nearest(selection);
        owner.header(true);
        // One row of controls: shooter and range dropdowns, the same as Home. Nothing scrolls sideways or hides behind another control.
        LinearLayout description=a.row();description.setGravity(Gravity.CENTER_VERTICAL);description.addView(LibraryFilters.shooter(a,"progress-shooter-filter",()->{selection=0;owner.show();}),new LinearLayout.LayoutParams(0,a.dp(44),1));
        List<Double> rangeValues=new ArrayList<>();List<String> rangeLabels=new ArrayList<>();rangeValues.add(Double.NaN);rangeLabels.add("All ranges");int rangeAt=0;
        TreeSet<Long> distances=new TreeSet<>(data.distanceChoices);if(Practice.distance(distance))distances.add(ConsistencyStats.rangeKey(distance));
        for(long value:distances){double meters=value/1000.;if(Practice.distance(distance)&&Math.abs(distance-meters)<.001)rangeAt=rangeValues.size();rangeValues.add(meters);rangeLabels.add(rangeLabel(meters));}
        Spinner range=LibraryFilters.menu(a,rangeLabels,rangeAt,"comparison-distances","Distance filter",at->changeDistance(rangeValues.get(at)));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(a.dp(136),a.dp(44));rp.setMargins(a.dp(8),0,0,0);description.addView(range,rp);a.add(a.root,description);
        // Equipment only matters once more than one setup is recorded; the old "checked only" filter is gone because nothing in the library uses position verification.
        if(data.setups.size()>1){Button gear=a.button("Equipment: "+(setup.isEmpty()?"unrecorded":setup),this::filters);gear.setTag("comparison-filters");gear.setBackground(ReviewControls.outline(a));gear.setTextSize(13);a.add(a.root,gear);}
        scroll=new ScrollView(a);scroll.setTag("comparison-scroll");LinearLayout body=new LinearLayout(a);body.setOrientation(1);scroll.addView(body);a.root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        // The caption explains the chart; the trend is one plainly named on/off chip beside it.
        LinearLayout captionRow=a.row();captionRow.setGravity(Gravity.CENTER_VERTICAL);
        unit=a.text(legend(),12,MainActivity.MUTED);unit.setTag("comparison-unit");captionRow.addView(unit,new LinearLayout.LayoutParams(0,-2,1));
        CheckBox trend=new CheckBox(a);trend.setText("100-arrow trend");trend.setTextSize(12);trend.setChecked(showTrend);trend.setTag("comparison-trend");trend.setContentDescription("Show each shooter's trend over their last 100 arrows");ReviewControls.chip(a,trend,MainActivity.ACCENT,MainActivity.BG);
        trend.setOnCheckedChangeListener((b,on)->{showTrend=on;save();chart.showTrend(showTrend);unit.setText(legend());select(selection);});
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,a.dp(34));tp.setMargins(a.dp(8),0,0,0);captionRow.addView(trend,tp);a.add(body,captionRow);
        pending=a.text("",13,HomeUi.AMBER);pending.setTag("comparison-pending");a.add(body,pending);
        labelRounds=a.button("Label rounds",()->{LabelCoverage.Day day=data.labelDays.get(selection);if(day!=null)a.labelDay(day.time,day.pending);});labelRounds.setTag("comparison-label-rounds");a.add(body,labelRounds);
        chart=new ConsistencyChart(a,data,selection,this::select);chart.showTrend(showTrend);chart.setTag("comparison-chart");body.addView(chart,new LinearLayout.LayoutParams(-1,a.dp(285)));
        LinearLayout.LayoutParams kp=new LinearLayout.LayoutParams(-1,-2);kp.setMargins(a.dp(4),a.dp(6),0,a.dp(4));body.addView(new ConsistencyChart.Key(a),kp);
        LinearLayout navigation=a.row();Button previous=a.button("‹",()->step(-1));previous.setBackground(ReviewControls.outline(a));previous.setTag("comparison-previous");previous.setContentDescription("Previous plotted date or round");navigation.addView(previous,new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));
        dateValues=new ArrayList<>(data.times);dateValues.sort(Collections.reverseOrder());
        dateMenu=LibraryFilters.day(a,dateValues,selection,false,"comparison-date-filter",day->{if(day!=selection)select(day);});navigation.addView(dateMenu,new LinearLayout.LayoutParams(0,-2,1));
        Button next=a.button("›",()->step(1));next.setBackground(ReviewControls.outline(a));next.setTag("comparison-next");next.setContentDescription("Next plotted date or round");navigation.addView(next,new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));a.add(body,navigation);
        readings=new LinearLayout(a);readings.setOrientation(1);readings.setTag("comparison-readings");a.add(body,readings);
        rangeNote=a.text("",12,MainActivity.MUTED);rangeNote.setTag("comparison-range-note");a.add(body,rangeNote);
        quality=a.text("",12,MainActivity.MUTED);quality.setTag("comparison-quality");a.add(body,quality);
        int missing=data.missingDistance;TextView excluded=a.text((missing>0?missing+" rounds need distances. ":"")+(data.unconfirmed>0?data.unconfirmed+" unconfirmed owners. ":"")+(data.mixedDistance>0?data.mixedDistance+" walk-up rounds in Details. ":"")+(data.unchecked>0?data.unchecked+" unchecked rounds filtered. ":"")+(data.unsupported>0?data.unsupported+" unsupported paper faces. ":""),12,MainActivity.MUTED);excluded.setTag("comparison-excluded");if(!excluded.getText().toString().isEmpty())a.add(body,excluded);
        if(data.series.isEmpty())a.add(body,a.text(a.shooters.filter.isEmpty()?"Unassigned rounds have no individual progress":"No shooters in this selection",14,MainActivity.MUTED));
        if(data.setups.size()>1)a.add(body,a.text("Equipment: "+(setup.isEmpty()?"unrecorded":setup),12,MainActivity.MUTED));
        select(selection);if(oldScroll>0)scroll.post(()->scroll.scrollTo(0,oldScroll));
        final ConsistencyStats calculated=data;
        intervalTask=a.worker.submit(()->{calculated.computeIntervals();a.runOnUiThread(()->{
            if(!a.isDestroyed()&&a.page.equals("progress")&&owner.comparing&&data==calculated)select(selection);
        });});
    }
    // The marks are explained by ConsistencyChart.Key under the chart; this caption only names units and what lines mean.
    String legend(){return "Grouping spread · mrad · lower is tighter"+(showTrend?"\nLines: last 100 arrows (overlapping)":"");}
    Button choiceButton(String text,boolean active,Runnable action){Button button=a.button(text,action);button.setTextSize(13);button.setSelected(active);button.setBackground(active?a.rounded(MainActivity.ACCENT):ReviewControls.outline(a));button.setTextColor(active?MainActivity.BG:MainActivity.FG);return button;}
    void choice(LinearLayout row,String label,boolean selected,String tag,Runnable action){Button button=choiceButton(label,selected,action);button.setTag(tag);button.setContentDescription("Distance filter: "+label+(selected?", selected":""));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,a.dp(44));lp.setMargins(0,0,a.dp(6),0);row.addView(button,lp);}
    void changeDistance(double value){if((Double.isNaN(value)&&Double.isNaN(distance))||value==distance)return;distance=value;selection=0;save();owner.show();}
    static String rangeLabel(double meters){return TargetPreset.number(Practice.fromMeters(meters,"yd"))+" yd";}
    static String ranges(ConsistencyStats.Sample sample){List<String> values=new ArrayList<>();for(long key:sample.ranges)values.add(rangeLabel(key/1000.));return String.join(" + ",values);}
    void step(int delta){List<Long> times=new ArrayList<>(data.times);int at=times.indexOf(selection);if(at<0||times.isEmpty())return;select(times.get(Math.max(0,Math.min(times.size()-1,at+delta))));}
    void select(long time){
        selection=time;chart.select(time);readings.removeAllViews();
        int selectedDate=dateValues.indexOf(time);if(selectedDate>=0&&dateMenu.getSelectedItemPosition()!=selectedDate)dateMenu.setSelection(selectedDate);
        LabelCoverage.Day coverageDay=data.labelDays.get(time);boolean needs=coverageDay!=null&&!coverageDay.pending.isEmpty();pending.setVisibility(needs?View.VISIBLE:View.GONE);labelRounds.setVisibility(needs?View.VISIBLE:View.GONE);if(needs){pending.setText(coverageDay.summary());labelRounds.setText("Label "+coverageDay.pending.size()+" rounds");}
        View prev=a.root.findViewWithTag("comparison-previous"),next=a.root.findViewWithTag("comparison-next");if(prev!=null)prev.setEnabled(!data.times.isEmpty()&&time>data.times.first());if(next!=null)next.setEnabled(!data.times.isEmpty()&&time<data.times.last());
        LinearLayout row=null;int unlocated=0,unchecked=0,misses=0,shortRounds=0;boolean countsKnown=true;Set<String> rangeKeys=new HashSet<>();
        for(int i=0;i<data.series.size();i++){
            ConsistencyStats.Series series=data.series.get(i);ConsistencyStats.Sample sample=data.at(series,time);
            if(i%2==0){row=a.row();a.add(readings,row);}LinearLayout card=new LinearLayout(a);card.setOrientation(1);card.setPadding(a.dp(12),a.dp(6),a.dp(12),a.dp(8));card.setBackground(a.rounded(MainActivity.PANEL));a.cell(row,card);
            String marker=i%2==0?"● ":"■ ";TextView name=a.text(marker+series.person.label(),14,a.shooters.color(series.person));name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(name);
            TextView value=a.text(sample==null?"—":ProgressUi.fmt(sample.spread)+" mrad",26,MainActivity.FG);value.setTypeface(null,1);value.setTag("comparison-value:"+series.person.id);card.addView(value);
            TextView interval=a.text(intervalLabel(sample),12,MainActivity.MUTED);interval.setTag("comparison-ci:"+series.person.id);card.addView(interval);
            String coverage=sample==null?(needs?"Saved rounds need labels":"No eligible rounds for this date"):sample.usableRounds+(sample.usableRounds==1?" round":" rounds")+" · "+sample.located+" arrows"+(sample.unlocated>0?" · "+sample.unlocated+" not measured":"");
            TextView counts=a.text(coverage,11,MainActivity.MUTED);counts.setTag("comparison-count:"+series.person.id);card.addView(counts);
            if(showTrend&&sample!=null){ConsistencyStats.Sample rolling=data.trendAt(series,time);String trendLabel=ConsistencyStats.trendReady(rolling)?"100-arrow trend: "+ProgressUi.fmt(rolling.spread)+" mrad\n"+rolling.records+" recorded · "+rolling.located+" measured":"100-arrow trend: insufficient data";TextView trend=a.text(trendLabel,11,MainActivity.MUTED);trend.setTag("comparison-trend-value:"+series.person.id);card.addView(trend);}
            if(sample!=null){unlocated+=sample.unlocated;unchecked+=sample.unchecked;misses+=sample.misses;countsKnown&=sample.countsKnown;shortRounds+=sample.ends.size()-sample.usableRounds;rangeKeys.add(sample.rangeKey);}
        }
        String note=Practice.distance(distance)?rangeLabel(distance)+" only":("All ranges · equal weight per distance"+(rangeKeys.size()>1?" · range mix differs":""));
        if(!Practice.distance(distance)){for(ConsistencyStats.Series series:data.series){ConsistencyStats.Sample sample=data.at(series,time);if(sample!=null){note+="\n"+series.person.label()+": "+ranges(sample);}}}
        rangeNote.setText(note);
        // Plain notes about what is and isn't counted; the statistical detail stays behind the ? button.
        List<String> notes=new ArrayList<>();
        if(unlocated>0)notes.add(unlocated+" arrow"+(unlocated==1?"":"s")+" not measured yet, so left out");
        if(misses>0)notes.add(misses+" reported miss"+(misses==1?"":"es")+", not plotted");
        if(shortRounds>0)notes.add(shortRounds+" round"+(shortRounds==1?"":"s")+" with fewer than 2 measured arrows, left out");
        if(!countsKnown)notes.add("Shot counts not entered, so misses are unknown");
        notes.add("Ranges don't include photo-measurement error");
        quality.setText("· "+String.join("\n· ",notes));
    }
    static String intervalLabel(ConsistencyStats.Sample sample){
        if(sample==null)return "";ConsistencyStats.Interval ci=sample.interval;
        if(ci.available())return "Likely "+ProgressUi.fmt(ci.lower)+"–"+ProgressUi.fmt(ci.upper)+" mrad";
        if(ci.state==ConsistencyStats.Interval.PENDING)return "Working out likely range…";
        if(ci.state==ConsistencyStats.Interval.SINGLE_ROUND)return "No likely range yet\nOnly one usable round"+(sample.ranges.size()>1?" at an included distance":"");
        if(ci.state==ConsistencyStats.Interval.DEGENERATE)return "No likely range\nRounds didn't vary";
        return "Spread unavailable\nNo round with two measured arrows at an included distance";
    }
    void filters(){
        LinearLayout body=new LinearLayout(a);body.setOrientation(1);body.setPadding(a.dp(20),a.dp(8),a.dp(20),a.dp(8));
        body.addView(a.text("Equipment setup",14,MainActivity.FG));Spinner gear=new Spinner(a);List<String> values=new ArrayList<>(data.setups);if(!values.contains(setup))values.add(0,setup);List<String> labels=new ArrayList<>();for(String s:values)labels.add(s.isEmpty()?"Unrecorded setup":s);ArrayAdapter<String> adapter=new ArrayAdapter<>(a,android.R.layout.simple_spinner_dropdown_item,labels);gear.setAdapter(adapter);gear.setSelection(values.indexOf(setup));gear.setTag("comparison-setup");body.addView(gear,new LinearLayout.LayoutParams(-1,a.dp(48)));
        options=new AlertDialog.Builder(a).setTitle("Equipment").setView(body).setNegativeButton("Cancel",null).setPositiveButton("Apply",(d,w)->{setup=values.get(gear.getSelectedItemPosition());save();selection=0;}).create();
        options.setOnDismissListener(d->{options=null;if(a.page.equals("progress")&&owner.comparing)owner.show();});options.show();
    }
    void help(){a.message("Daily dots show pooled within-round radial standard deviation, in mrad. Each round is centered on its own average impact before pooling squared deviations with (measured arrows - 1) degrees of freedom. This measures grouping tightness, not bullseye accuracy or between-round aim drift. More shots do not automatically lower this number.\n\nWhiskers are approximate 95% percentile bootstrap confidence intervals for the spread, not a region containing 95% of arrows. We resample whole usable rounds 4,096 times within each distance. All ranges gives each represented distance equal variance weight. Intervals assume comparable, independent rounds and exclude photo-calibration error, omitted arrows and systematic bias. Repeated photos of the same physical round may violate that independence assumption. A single usable round at an included distance, or no observed round-to-round variation, leaves its interval unavailable.\n\nDaily lines connect recorded sessions. Dashed connections indicate a missing plotted date for that person or a changed distance mix; they do not supply a missing measurement. Daily + 100 instead connects the last approximately 100 recorded arrows at each day's end, keeping whole rounds. Incomplete 100-arrow windows are not plotted. These windows overlap, and no confidence band is claimed for that trend.\n\nMarked dots are saved image positions. Measured dots also have calibrated target coordinates. Dots are correct confirms your image review; it does not supply target calibration. Partial measurements exclude unmeasured entries, so their spread can be biased. Missing dates and reported misses have no invented coordinates. Distance and equipment filters keep comparison contexts separate.");}
}
