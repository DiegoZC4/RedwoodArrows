package org.redwoodarrows;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Practice overview, with capture always available and the photo library separate. */
final class HomeUi {
    static final int CORAL=0xFFD94C43,CAPTURE=MainActivity.ACCENT,AMBER=0xFFF4C967;
    final MainActivity a;final SharedPreferences preferences;
    final HomeStats data;final List<Store.Group> groups;
    HomeUi(MainActivity a){
        this.a=a;preferences=a.getSharedPreferences("home-view",0);groups=LibraryFilter.groups(a.libraryGroups(),a.shooters.filter);
        double distance=Double.longBitsToDouble(preferences.getLong("distance",Double.doubleToLongBits(Double.NaN)));
        if(!Practice.distance(distance))distance=Double.NaN;
        String setup=preferences.getString("setup",HomeStats.latestSetup(groups));
        boolean exists=groups.isEmpty();for(Store.Group group:groups)if(group.practice.setup.equals(setup))exists=true;
        if(!exists)setup=HomeStats.latestSetup(groups);
        data=new HomeStats(groups,LibraryFilter.people(a.shooters.roster.all(),a.shooters.filter),setup,distance);
    }
    void show(){
        a.shell("Your progress");a.root.removeView(a.heading);
        LinearLayout header=a.row();header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(a.heading,new LinearLayout.LayoutParams(0,-2,1));
        ImageButton menu=icon(a,"ellipsis-vertical","More",a::homeMenu);menu.setTag("home-menu");header.addView(menu,new LinearLayout.LayoutParams(a.dp(48),a.dp(48)));a.add(a.root,header);
        if(SyncRuntime.enabled(a)){a.syncStatus=a.text(a.syncLabel(),12,MainActivity.MUTED);a.syncStatus.setTag("shared-library");a.syncStatus.setMinHeight(a.dp(36));a.syncStatus.setGravity(Gravity.CENTER_VERTICAL);a.syncStatus.setOnClickListener(v->new SyncUi(a).show());a.add(a.root,a.syncStatus);a.refreshSync();}
        a.add(a.root,LibraryFilters.labeled(a,"Shooter",LibraryFilters.shooter(a,"overview-shooter-filter",a::showOverview)));
        ScrollView scroll=new ScrollView(a);scroll.setTag("overview-scroll");scroll.setFillViewport(false);
        LinearLayout body=new LinearLayout(a);body.setOrientation(1);scroll.addView(body);a.root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        attention(body);
        LinearLayout context=a.row();context.setGravity(Gravity.CENTER_VERTICAL);
        TextView metric=a.text("Grouping spread",17,MainActivity.FG);metric.setTypeface(null,1);context.setPadding(0,a.dp(12),0,0);context.addView(metric,new LinearLayout.LayoutParams(0,-2,1));
        List<Double> ranges=new ArrayList<>();ranges.add(Double.NaN);for(long range:data.progress.distanceChoices)ranges.add(range/1000.);
        if(Practice.distance(data.distance)&&ranges.stream().noneMatch(d->Double.isFinite(d)&&Math.abs(d-data.distance)<.001))ranges.add(data.distance);
        List<String> labels=new ArrayList<>();int selected=0;for(int i=0;i<ranges.size();i++){double range=ranges.get(i);labels.add(Practice.distance(range)?ConsistencyUi.rangeLabel(range):"All ranges");if(Practice.distance(range)&&Math.abs(range-data.distance)<.001)selected=i;}
        Spinner filter=new Spinner(a);filter.setTag("overview-distance");filter.setContentDescription("Progress distance filter");LibraryFilters.pill(a,filter);filter.setPopupBackgroundDrawable(new ColorDrawable(MainActivity.PANEL));
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,labels){
            public View getView(int position,View recycled,ViewGroup parent){TextView text=(TextView)super.getView(position,recycled,parent);text.setTextColor(MainActivity.FG);text.setTextSize(14);text.setSingleLine();text.setEllipsize(android.text.TextUtils.TruncateAt.END);LibraryFilters.selected(a,text);return text;}
            public View getDropDownView(int position,View recycled,ViewGroup parent){TextView text=(TextView)super.getDropDownView(position,recycled,parent);text.setTextColor(MainActivity.FG);text.setTextSize(16);text.setBackgroundColor(MainActivity.PANEL);text.setMinimumHeight(a.dp(48));return text;}
        };filter.setAdapter(adapter);filter.setSelection(selected);final int initial=selected;
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){int current=initial;public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> parent,View v,int position,long id){if(current==position)return;current=position;preferences.edit().putLong("distance",Double.doubleToLongBits(ranges.get(position))).apply();a.showOverview();}});
        context.addView(filter,new LinearLayout.LayoutParams(a.dp(142),a.dp(44)));a.add(body,context);
        if(data.progress.setups.size()>1){Button setup=a.button(data.setup.isEmpty()?"Unrecorded equipment":data.setup,this::chooseSetup);setup.setTag("overview-setup");setup.setBackgroundColor(Color.TRANSPARENT);a.add(body,setup);}
        TextView unit=a.text("mrad \u00b7 lower is tighter \u00b7 estimated",12,MainActivity.MUTED);unit.setTag("overview-units");a.add(body,unit);
        Trend chart=new Trend(a,data);chart.setTag("overview-chart");chart.setFocusable(true);chart.setOnClickListener(v->details());body.addView(chart,new LinearLayout.LayoutParams(-1,a.dp(192+Math.max(0,a.getResources().getConfiguration().fontScale-1)*44)));
        // Same visual key as Progress, limited to the marks this chart uses.
        LinearLayout.LayoutParams kp=new LinearLayout.LayoutParams(-1,-2);kp.setMargins(a.dp(4),a.dp(2),0,a.dp(4));body.addView(new ConsistencyChart.Key(a,0,4),kp);
        List<String> partial=new ArrayList<>();for(long day:data.chartDays)if(data.partiallyMeasured(day))partial.add(date(day));
        if(!partial.isEmpty()){TextView coverage=a.text("Partial measurements: "+String.join(", ",partial),11,AMBER);coverage.setTag("overview-partial-measurements");a.add(body,coverage);}
        readings(body);
        activity(body);
        HomeStats.Session latest=data.latest();
        LinearLayout recent=a.row();recent.setGravity(Gravity.CENTER_VERTICAL);recent.setPadding(0,a.dp(12),0,0);TextView title=a.text("Recent sessions",17,MainActivity.FG);title.setTypeface(null,1);recent.addView(title,new LinearLayout.LayoutParams(0,-2,1));ImageButton all=icon(a,"chevron-right","All photos",a::showLibrary);all.setTag("overview-all-photos");recent.addView(all,new LinearLayout.LayoutParams(a.dp(48),a.dp(48)));a.add(body,recent);
        if(latest==null)a.add(body,a.text("No sessions yet",15,MainActivity.MUTED));
        for(int i=0;i<Math.min(3,data.sessions.size());i++)session(body,data.sessions.get(i));
        capture();navigation(a,"overview");
    }
    /** Work waiting for the user, shown only when there is some. Counts are read off the UI thread; nothing is changed. */
    void attention(LinearLayout body){
        LinearLayout strip=a.row();strip.setGravity(Gravity.CENTER_VERTICAL);strip.setTag("overview-attention");strip.setBackground(ReviewControls.background(a,MainActivity.PANEL));strip.setPadding(a.dp(14),a.dp(10),a.dp(8),a.dp(10));strip.setClickable(true);strip.setFocusable(true);strip.setVisibility(View.GONE);
        View dot=new View(a);GradientDrawable mark=new GradientDrawable();mark.setShape(GradientDrawable.OVAL);mark.setColor(AMBER);dot.setBackground(mark);LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(a.dp(8),a.dp(8));dp.setMargins(0,0,a.dp(12),0);strip.addView(dot,dp);
        TextView text=a.text("",14,MainActivity.FG);text.setMaxLines(2);strip.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        ImageView more=new ImageView(a);more.setImageDrawable(drawable(a,"chevron-right",MainActivity.MUTED));more.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);strip.addView(more,new LinearLayout.LayoutParams(a.dp(20),a.dp(24)));a.add(body,strip);
        HomeStats.Session latest=data.latest();List<Store.Group> scope=new ArrayList<>(groups);
        a.worker.execute(()->{
            int unchecked=0,detecting=0,unowned=0;
            for(Store.Group g:scope){if(g.hasData()&&!g.shooter.assigned())unowned++;if(g.coordinatesOnly()||g.views.isEmpty())continue;unchecked+=PhotoAssignments.photoIndices(g).size()-TrainingReviews.checkedPhotos(a.store,g).size();if(g.views.stream().anyMatch(v->v.detectionState.equals("pending")))detecting++;}
            final int review=unchecked,running=detecting,noShooter=unowned,unmeasured=latest==null?0:latest.marked-latest.located;
            a.runOnUiThread(()->{if(!strip.isAttachedToWindow())return;
                List<String> parts=new ArrayList<>();
                if(review>0)parts.add(review+" photo"+(review==1?"":"s")+" to review");
                if(noShooter>0)parts.add(noShooter+" grouping"+(noShooter==1?"":"s")+" need"+(noShooter==1?"s":"")+" a shooter");
                if(running>0)parts.add(running+" still finding arrows");
                if(unmeasured>0)parts.add(unmeasured+" arrow"+(unmeasured==1?"":"s")+" from "+date(latest.day)+" not measured");
                if(parts.isEmpty())return;text.setText(String.join(" · ",parts));strip.setContentDescription(text.getText()+". Open photos");strip.setVisibility(View.VISIBLE);
                strip.setOnClickListener(v->{if(a.busy)return;if(review>0){a.overviewSelected=false;a.groupingDay=0;a.bulkSelecting=false;a.bulkSelection.clear();a.reviewOnly=true;a.groupGridState=null;a.home();}
                    else if(noShooter>0&&a.shooters.setFilter("")){a.overviewSelected=false;a.groupingDay=0;a.bulkSelecting=false;a.bulkSelection.clear();a.reviewOnly=false;a.groupGridState=null;a.home();}
                    else if(unmeasured>0)a.showSession(latest.day);else a.showLibrary();});
            });
        });
    }
    /** Days shooting, weekly streak and arrows recorded for the current shooter filter. Read-only. */
    void activity(LinearLayout body){
        List<ShooterRoster.Person> people=LibraryFilter.people(a.shooters.roster.all(),a.shooters.filter);ActivityStats stats=new ActivityStats(groups,people);if(stats.total.days.isEmpty())return;
        long now=System.currentTimeMillis();int streak=stats.weekStreak(now),since=stats.daysSince(now);boolean counted=stats.shotThisWeek(now);
        TextView title=a.text("Activity",17,MainActivity.FG);title.setTypeface(null,1);title.setPadding(0,a.dp(12),0,a.dp(4));a.add(body,title);
        LinearLayout tiles=a.row();tiles.setTag("overview-activity");
        tile(tiles,""+stats.total.days.size(),stats.total.days.size()==1?"day shooting":"days shooting","overview-days");
        tile(tiles,streak==0?"—":streak+(streak==1?" week":" weeks"),"weekly streak","overview-streak");
        tile(tiles,String.format(Locale.US,"%,d",stats.total.arrows),"arrows recorded","overview-arrows");a.add(body,tiles);
        if(people.size()>1)for(ShooterRoster.Person person:people){ActivityStats.Tally tally=stats.people.get(person.id);if(tally==null||tally.days.isEmpty())continue;
            android.text.SpannableStringBuilder line=new android.text.SpannableStringBuilder(person.label());line.setSpan(new android.text.style.ForegroundColorSpan(a.shooters.color(person)),0,line.length(),0);line.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),0,line.length(),0);
            String best=bestDay(person);line.append("   "+String.format(Locale.US,"%,d",tally.arrows)+" arrows · "+tally.days.size()+(tally.days.size()==1?" day":" days")+(best.isEmpty()?"":" · best "+best));
            TextView text=a.text("",13,MainActivity.FG);text.setText(line);text.setTag("overview-activity-person:"+person.id);text.setPadding(a.dp(2),a.dp(3),0,a.dp(3));body.addView(text);}
        if(people.size()==1){String best=bestDay(people.get(0));if(!best.isEmpty()){TextView text=a.text("Tightest day: "+best,13,MainActivity.FG);text.setTag("overview-activity-best");text.setPadding(a.dp(2),a.dp(3),0,a.dp(3));body.addView(text);}}
        // Without this line the per-person counts would not add up to the total.
        if(people.size()>1&&stats.unassigned.arrows>0){TextView loose=a.text("Unassigned   "+String.format(Locale.US,"%,d",stats.unassigned.arrows)+" arrows · "+stats.unassigned.days.size()+(stats.unassigned.days.size()==1?" day":" days"),13,MainActivity.MUTED);loose.setTag("overview-activity-unassigned");loose.setPadding(a.dp(2),a.dp(3),0,a.dp(3));body.addView(loose);}
        if(!stats.distances.isEmpty()){TextView ranges=a.text("Distances: "+stats.distanceSummary(),12,MainActivity.MUTED);ranges.setTag("overview-activity-distances");ranges.setPadding(a.dp(2),a.dp(6),0,0);body.addView(ranges);}
        Calendar end=Calendar.getInstance();end.setTimeInMillis(ActivityStats.week(now));end.add(Calendar.DAY_OF_MONTH,6);String last=new SimpleDateFormat("EEEE",Locale.US).format(end.getTime());
        String note=counted?"This week counted"+(streak>1?" · "+streak+" weeks in a row":""):streak>0?"Shoot by "+last+" to make it "+(streak+1)+" weeks in a row":"Start a streak: shoot once this week";
        TextView streakNote=a.text(note,13,streak>0||counted?MainActivity.ACCENT:MainActivity.MUTED);streakNote.setTag("overview-streak-note");streakNote.setPadding(a.dp(2),a.dp(6),0,0);body.addView(streakNote);
        String month=new SimpleDateFormat("MMMM",Locale.US).format(new Date(now));int thisMonth=stats.daysThisMonth(now);
        TextView recency=a.text("Last session "+date(stats.total.days.last())+(since==0?" · today":since==1?" · yesterday":" · "+since+" days ago")+" · "+thisMonth+(thisMonth==1?" day":" days")+" in "+month,12,MainActivity.MUTED);recency.setTag("overview-recency");recency.setPadding(a.dp(2),a.dp(2),0,a.dp(6));body.addView(recency);
        // Arrows recorded = saved dots or the entered shot count; two groupings of the same physical end would count twice.
        tiles.setContentDescription(stats.total.days.size()+" days shooting. "+(streak==0?"No current weekly streak":streak+" week streak")+". "+stats.total.arrows+" arrows recorded.");
    }
    void tile(LinearLayout row,String value,String label,String tag){LinearLayout cell=new LinearLayout(a);cell.setOrientation(1);cell.setGravity(Gravity.CENTER);cell.setBackground(a.rounded(MainActivity.PANEL));cell.setPadding(a.dp(6),a.dp(12),a.dp(6),a.dp(12));
        TextView n=a.text(value,20,MainActivity.FG);n.setTypeface(null,1);n.setGravity(Gravity.CENTER);n.setPadding(0,0,0,0);n.setSingleLine(true);n.setTag(tag);cell.addView(n);TextView l=a.text(label,11,MainActivity.MUTED);l.setGravity(Gravity.CENTER);l.setPadding(0,a.dp(2),0,0);cell.addView(l);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(row.getChildCount()==0?0:a.dp(8),0,0,0);row.addView(cell,p);}
    void readings(LinearLayout body){
        if(data.progress.series.isEmpty()){a.add(body,a.text(a.shooters.filter.isEmpty()?"Unassigned rounds have no individual progress":"No measured sessions yet",15,MainActivity.MUTED));return;}
        LinearLayout row=null;for(int i=0;i<data.progress.series.size();i++){
            if(i%2==0){row=a.row();a.add(body,row);}
            ConsistencyStats.Series series=data.progress.series.get(i);ConsistencyStats.Sample sample=HomeStats.latestMeasured(series);
            LinearLayout cell=new LinearLayout(a);cell.setOrientation(1);cell.setPadding(a.dp(12),a.dp(8),a.dp(10),a.dp(10));cell.setBackground(a.rounded(MainActivity.PANEL));
            TextView person=a.text(series.person.label(),14,a.shooters.color(series.person));person.setTypeface(null,1);person.setMaxLines(2);cell.addView(person);
            TextView value=a.text(sample==null?"Not measured":ProgressUi.fmt(sample.spread)+" mrad",sample==null?17:23,MainActivity.FG);value.setTypeface(null,1);value.setTag("overview-spread:"+series.person.id);cell.addView(value);
            if(sample!=null){
                // Change since this shooter's previous measured day; lower is tighter.
                ConsistencyStats.Sample before=null;for(int j=series.samples.size()-1;j>=0;j--){ConsistencyStats.Sample s=series.samples.get(j);if(s.time<sample.time&&Double.isFinite(s.spread)){before=s;break;}}
                if(before!=null){double delta=sample.spread-before.spread;TextView change=a.text(Math.abs(delta)<.05?"Same as "+date(before.time):(delta<0?"▼ ":"▲ ")+ProgressUi.fmt(Math.abs(delta))+(delta<0?" tighter":" wider")+" than "+date(before.time),12,delta<=-.05?MainActivity.ACCENT:MainActivity.MUTED);change.setTag("overview-change:"+series.person.id);cell.addView(change);}
                // Spread is RMS distance from each round's own center; at one known range it converts directly to cm.
                if(sample.ranges.size()==1){long range=sample.ranges.first();TextView size=a.text("≈ "+Math.round(sample.spread*range/10000.)+" cm from group center",12,MainActivity.MUTED);size.setTag("overview-size:"+series.person.id);size.setContentDescription("Arrows land about "+Math.round(sample.spread*range/10000.)+" centimeters from their group's center at this distance");cell.addView(size);}
                String center=centerLabel(sample);if(!center.isEmpty()){TextView aim=a.text(center,12,MainActivity.MUTED);aim.setTag("overview-center:"+series.person.id);cell.addView(aim);}
            }
            String range=sample!=null&&sample.ranges.size()==1?" · "+TargetPreset.number(Math.round(Practice.fromMeters(sample.ranges.first()/1000.,"yd")))+" yd":"";
            TextView source=a.text(sample==null?"Awaiting measurements":date(sample.time)+range,12,MainActivity.MUTED);source.setTag("overview-measured-date:"+series.person.id);cell.addView(source);
            // Coverage only when something is missing; a complete day needs no bookkeeping line.
            if(sample!=null&&sample.unlocated>0){TextView coverage=a.text(sample.unlocated+" not measured yet",11,AMBER);coverage.setTag("overview-coverage:"+series.person.id);cell.addView(coverage);}
            a.cell(row,cell);
        }
    }
    void session(LinearLayout body,HomeStats.Session session){
        LinearLayout row=a.row();row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,a.dp(10),0,a.dp(10));row.setMinimumHeight(a.dp(80));row.setTag("overview-session:"+session.day);row.setFocusable(true);row.setClickable(true);row.setBackgroundResource(android.R.drawable.list_selector_background);row.setOnClickListener(v->{if(!a.busy)a.showSession(session.day);});
        Store.Group photo=null;for(Store.Group group:session.groups)if(!group.views.isEmpty()){photo=group;break;}
        if(photo!=null){View image=a.groupImages.new Preview(photo,GroupImages.reference(photo),GroupImages.ORIGINAL,240);image.setClickable(false);image.setFocusable(false);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(image,new LinearLayout.LayoutParams(a.dp(58),a.dp(58)));}
        LinearLayout labels=new LinearLayout(a);labels.setOrientation(1);labels.setPadding(a.dp(12),0,a.dp(8),0);
        TextView name=a.text(date(session.day)+" \u00b7 "+session.photoLabel(),15,MainActivity.FG);name.setTypeface(null,1);labels.addView(name);
        // Each shooter's result that day, in their color; counts appear only when something is missing.
        android.text.SpannableStringBuilder results=new android.text.SpannableStringBuilder();
        for(ConsistencyStats.Series series:data.progress.series){ConsistencyStats.Sample s=data.progress.at(series,session.day);if(s==null||!Double.isFinite(s.spread))continue;if(results.length()>0)results.append("   ");int start=results.length();results.append(series.person.label());results.setSpan(new android.text.style.ForegroundColorSpan(a.shooters.color(series.person)),start,results.length(),0);results.append(" "+ProgressUi.fmt(s.spread));}
        TextView summary=a.text("",13,MainActivity.FG);summary.setTag("overview-session-results:"+session.day);if(results.length()>0){results.append(" mrad");summary.setText(results);}else{summary.setText(session.marked+" marked entries");summary.setTextColor(MainActivity.MUTED);}labels.addView(summary);
        if(session.located<session.marked)labels.addView(a.text(session.measurementLabel(),12,AMBER));row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        ImageView arrow=new ImageView(a);arrow.setImageDrawable(drawable(a,"chevron-right",MainActivity.MUTED));arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(arrow,new LinearLayout.LayoutParams(a.dp(20),a.dp(24)));
        row.setContentDescription(date(session.day)+". "+session.photoLabel()+". "+(results.length()>0?results+". ":"")+session.marked+" marked entries. "+session.measurementLabel()+". Open photos.");a.add(body,row);divider(a,body);
    }
    void capture(){
        divider(a,a.root);
        LinearLayout context=a.row();context.setGravity(Gravity.CENTER_VERTICAL);
        TextView next=a.text("Next: "+a.shooters.next(a.libraryGroups()).label()+" \u00b7 "+PracticeUi.nextLabel(a),13,MainActivity.MUTED);next.setMaxLines(2);next.setTag("overview-next");context.addView(next,new LinearLayout.LayoutParams(0,-2,1));
        ImageButton settings=icon(a,"sliders-horizontal","Next photo settings",a::captureSetup);settings.setTag("overview-capture-settings");context.addView(settings,new LinearLayout.LayoutParams(a.dp(48),a.dp(48)));a.root.addView(context);
        LinearLayout actions=a.row();Button camera=a.button("Take photo",()->a.takePhoto(false));camera.setTag("overview-camera");camera.setTextColor(MainActivity.BG);camera.setTextSize(18);camera.setTypeface(null,1);camera.setBackground(ReviewControls.background(a,CAPTURE));camera.setCompoundDrawables(drawable(a,"camera",MainActivity.BG),null,null,null);camera.setCompoundDrawablePadding(a.dp(12));camera.setPadding(a.dp(22),a.dp(12),a.dp(22),a.dp(12));camera.setContentDescription("Take a new target photo");
        int height=a.dp(64+Math.max(0,a.getResources().getConfiguration().fontScale-1)*24);actions.addView(camera,new LinearLayout.LayoutParams(0,height,1));ImageButton add=ReviewControls.icon(a,"image-plus","Import photos",()->a.addPhotos(false));add.setTag("overview-import");LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(a.dp(60),height);ip.setMargins(a.dp(8),0,0,0);actions.addView(add,ip);a.add(a.root,actions);
    }
    void details(){if(a.busy)return;if(a.progress==null)a.progress=new ProgressUi(a);a.progress.comparing=true;a.progress.comparison.setup=data.setup;a.progress.comparison.contextInitialized=true;a.progress.comparison.distance=data.distance;a.progress.comparison.checkedOnly=false;a.progress.comparison.selection=0;a.showProgress();}
    void chooseSetup(){List<String> values=new ArrayList<>(data.progress.setups);String[] labels=new String[values.size()];for(int i=0;i<labels.length;i++)labels[i]=values.get(i).isEmpty()?"Unrecorded equipment":values.get(i);new AlertDialog.Builder(a).setTitle("Equipment").setItems(labels,(dialog,index)->{preferences.edit().putString("setup",values.get(index)).apply();a.showOverview();}).setNegativeButton("Cancel",null).show();}
    static void divider(MainActivity a,LinearLayout parent){View line=new View(a);line.setBackgroundColor(MainActivity.PANEL);parent.addView(line,new LinearLayout.LayoutParams(-1,a.dp(1)));}
    static String date(long time){return new SimpleDateFormat("MMM d",Locale.US).format(new Date(time));}
    /** Lowest daily spread with enough evidence to mean something (12+ measured arrows over 2+ usable rounds), e.g. "8.5 mrad (Oct 18)"; "" when no day qualifies. */
    String bestDay(ShooterRoster.Person person){ConsistencyStats.Sample best=null;for(ConsistencyStats.Series series:data.progress.series)if(series.person.id.equals(person.id))for(ConsistencyStats.Sample s:series.samples)
        if(Double.isFinite(s.spread)&&s.located>=12&&s.usableRounds>=2&&(best==null||s.spread<best.spread))best=s;return best==null?"":ProgressUi.fmt(best.spread)+" mrad ("+date(best.time)+")";}
    /** Where the day's arrows centered relative to the bullseye (the same mean as Progress's center offset), e.g. "Group sits 3 cm low-left"; "" when unknown. */
    static String centerLabel(ConsistencyStats.Sample sample){
        if(sample==null||sample.ranges.size()!=1)return "";double x=0,y=0;int n=0;for(ProgressStats.End e:sample.ends)for(double[] p:e.points){x+=p[0];y+=p[1];n++;}if(n==0)return "";
        double range=sample.ranges.first()/1000.,cx=x/n*range/10,cy=y/n*range/10,size=Math.hypot(cx,cy);// mrad × m = mm; ÷10 = cm. Positive y is high, positive x is right.
        if(size<1)return "Group centered on the bullseye";
        String v=Math.abs(cy)>=size*.38?(cy>0?"high":"low"):"",h=Math.abs(cx)>=size*.38?(cx>0?"right":"left"):"";
        return "Group sits "+Math.round(size)+" cm "+(v.isEmpty()?h:h.isEmpty()?v:v+"-"+h);
    }
    static Drawable drawable(MainActivity a,String name,int color){int id=a.getResources().getIdentifier("review_"+name.replace('-','_'),"drawable",a.getPackageName());Drawable d=a.getDrawable(id).mutate();d.setTint(color);d.setBounds(0,0,a.dp(24),a.dp(24));return d;}
    static ImageButton icon(MainActivity a,String name,String label,Runnable action){ImageButton button=ReviewControls.icon(a,name,label,action);button.setBackgroundColor(Color.TRANSPARENT);return button;}
    /** Text action for a top bar: no pill, so it reads like the icon buttons beside it. */
    static void quiet(MainActivity a,Button button,int color){button.setBackgroundColor(Color.TRANSPARENT);button.setTextColor(color);button.setTextSize(15);button.setPadding(a.dp(10),0,a.dp(10),0);}
    static View gapKey(MainActivity a){
        LinearLayout row=a.row();row.setGravity(Gravity.CENTER_VERTICAL);row.setTag("chart-gap-key");
        View swatch=new View(a){final Paint paint=new Paint(3);protected void onDraw(Canvas canvas){paint.setColor(MainActivity.MUTED);paint.setStrokeWidth(a.dp(1.5f));paint.setPathEffect(new DashPathEffect(new float[]{a.dp(5),a.dp(3)},0));canvas.drawLine(0,getHeight()/2f,getWidth()-a.dp(6),getHeight()/2f,paint);}};
        swatch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(swatch,new LinearLayout.LayoutParams(a.dp(32),a.dp(20)));
        row.addView(a.text("Gap / changed range",11,MainActivity.MUTED),new LinearLayout.LayoutParams(0,-2,1));return row;
    }
    static void navigation(MainActivity a,String selected){
        LinearLayout bar=a.row();bar.setTag("main-navigation");bar.setPadding(0,a.dp(4),0,0);
        String[] pages={"overview","progress","home"},names={"Home","Progress","Photos"},icons={"house","chart-no-axes-combined","images"};
        for(int i=0;i<pages.length;i++){final String page=pages[i];boolean current=page.equals(selected);int color=current?CAPTURE:0xFF8A9A90;
            LinearLayout tab=new LinearLayout(a);tab.setOrientation(1);tab.setGravity(Gravity.CENTER);tab.setTag("nav:"+page);tab.setSelected(page.equals(selected));tab.setFocusable(true);tab.setClickable(true);tab.setContentDescription(names[i]+(page.equals(selected)?", selected":""));tab.setTooltipText(names[i]);tab.setBackground(navigationBackground(a.getResources().getDisplayMetrics().density));
            ImageView image=new ImageView(a);image.setImageDrawable(drawable(a,icons[i],color));image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);image.setPadding(a.dp(18),a.dp(4),a.dp(18),a.dp(4));
            if(current){GradientDrawable pill=new GradientDrawable();pill.setColor(0x33A9D4C2);pill.setCornerRadius(a.dp(16));image.setBackground(pill);}tab.addView(image,new LinearLayout.LayoutParams(a.dp(60),a.dp(32)));
            TextView label=a.text(names[i],12,color);if(current)label.setTypeface(null,1);label.setGravity(Gravity.CENTER);label.setPadding(0,a.dp(3),0,0);label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);tab.addView(label);tab.setOnClickListener(v->{if(a.busy||a.page.equals(page))return;if(page.equals("overview"))a.showOverview();else if(page.equals("home"))a.showLibrary();else a.showProgress();});bar.addView(tab,new LinearLayout.LayoutParams(0,a.dp(58+Math.max(0,a.getResources().getConfiguration().fontScale-1)*18),1));
        }a.root.addView(bar);
    }
    static Drawable navigationBackground(float density){
        StateListDrawable states=new StateListDrawable();
        for(int state:new int[]{android.R.attr.state_pressed,android.R.attr.state_focused}){
            GradientDrawable highlight=new GradientDrawable();highlight.setColor(0x26A9D4C2);highlight.setCornerRadius(6*density);
            states.addState(new int[]{android.R.attr.state_enabled,state},highlight);
        }
        states.addState(new int[]{},new ColorDrawable(Color.TRANSPARENT));return states;
    }
    static final class Trend extends View {
        final HomeStats data;final Paint p=new Paint(3);final float d,s;final MainActivity a;
        Trend(MainActivity a,HomeStats data){super(a);this.a=a;this.data=data;d=getResources().getDisplayMetrics().density;s=getResources().getDisplayMetrics().scaledDensity;StringBuilder description=new StringBuilder("Grouping spread in milliradians. Lower is tighter. Estimated measurements. Dashed connections mark a gap or changed range; no missing measurement is invented. ");for(long day:data.chartDays){description.append(date(day)).append(" ").append(data.rangeLabel(day)).append(data.measured(day)?" measured. ":" measurements pending. ");for(ConsistencyStats.Series series:data.progress.series){ConsistencyStats.Sample sample=data.progress.at(series,day);description.append(series.person.label()).append(sample==null||!Double.isFinite(sample.spread)?": no measurement. ":": "+ProgressUi.fmt(sample.spread)+" mrad, "+sample.located+" measured. ");}}setContentDescription(description);}
        @Override protected void onDraw(Canvas canvas){
            float left=32*d,right=getWidth()-36*d,top=14*d,bottom=getHeight()-62*s;double high=1,low=Double.MAX_VALUE;
            for(ConsistencyStats.Series series:data.progress.series)for(ConsistencyStats.Sample sample:series.samples)if(data.chartDays.contains(sample.time)&&Double.isFinite(sample.spread)){high=Math.max(high,sample.spread);low=Math.min(low,sample.spread);}
            if(low==Double.MAX_VALUE)low=0;
            // Tighter axis: round, labeled gridlines just outside the values' own range (not from 0), so day-to-day changes are visible.
            double step=ConsistencyChart.niceStep(Math.max(high-low,1)/3),max=Math.ceil(high*1.04/step)*step,min=Math.max(0,Math.floor(low*.96/step)*step);if(max-min<step)max=min+step;
            final double floor=min,span=max-min;p.setTextSize(10*s);p.setTextAlign(Paint.Align.RIGHT);
            for(double value=min;value<=max+step*.01;value+=step){float y=bottom-(float)((value-floor)/span)*(bottom-top);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d);p.setColor(MainActivity.PANEL);canvas.drawLine(left,y,right,y,p);p.setStyle(Paint.Style.FILL);p.setColor(MainActivity.MUTED);canvas.drawText(TargetPreset.number(value),left-8*d,y+3*s,p);}
            List<float[]> ends=new ArrayList<>();
            for(int i=0;i<data.progress.series.size();i++){ConsistencyStats.Series series=data.progress.series.get(i);float px=0,py=0;ConsistencyStats.Sample previous=null;int color=a.shooters.color(series.person);
                for(int j=0;j<data.chartDays.size();j++){ConsistencyStats.Sample sample=data.progress.at(series,data.chartDays.get(j));if(sample==null||!Double.isFinite(sample.spread))continue;
                    float dodge=data.progress.series.size()<2?0:(i%2==0?-2.5f:2.5f)*d;
                    float x=x(j,left,right)+dodge,y=bottom-(float)((sample.spread-floor)/span)*(bottom-top);p.setStyle(Paint.Style.STROKE);p.setColor(color);p.setStrokeWidth(3*d);p.setStrokeCap(Paint.Cap.ROUND);
                    if(previous!=null){p.setPathEffect(ConsistencyStats.dashedLink(previous,sample,data.chartDays)?new DashPathEffect(new float[]{6*d,6*d},0):null);canvas.drawLine(px,py,x,y,p);}
                    p.setPathEffect(null);p.setStyle(Paint.Style.FILL);canvas.drawCircle(x,y,4.5f*d,p);previous=sample;px=x;py=y;
                }
                if(previous!=null)ends.add(new float[]{px,py,(float)previous.spread,color});
            }
            // Latest value at the end of each line, nudged apart vertically when they would overlap.
            ends.sort((m,n)->Float.compare(m[1],n[1]));p.setTextSize(11*s);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextAlign(Paint.Align.LEFT);float last=-Float.MAX_VALUE;
            for(float[] end:ends){float y=Math.max(end[1]+4*s,last+13*s);last=y;p.setColor((int)end[3]);canvas.drawText(ProgressUi.fmt(end[2]),end[0]+8*d,y,p);}
            p.setTypeface(Typeface.DEFAULT);p.setStrokeCap(Paint.Cap.BUTT);
            p.setTextAlign(Paint.Align.CENTER);for(int i=0;i<data.chartDays.size();i++){long day=data.chartDays.get(i);float x=x(i,left,right);if(!data.measured(day)){p.setColor(AMBER);p.setTextSize(10*s);canvas.drawText("Pending",x,bottom+18*s,p);}p.setTextSize(10*s);p.setColor(MainActivity.MUTED);canvas.drawText(date(day),x,getHeight()-22*s,p);p.setTextSize(9*s);canvas.drawText(data.rangeLabel(day),x,getHeight()-7*s,p);}
            if(data.chartDays.isEmpty()){p.setTextSize(14*s);p.setColor(MainActivity.MUTED);canvas.drawText("No measurements yet",(left+right)/2,(top+bottom)/2,p);}
        }
        float x(int i,float left,float right){return data.chartDays.size()<2?(left+right)/2:left+(right-left)*i/(data.chartDays.size()-1);}
    }
}
