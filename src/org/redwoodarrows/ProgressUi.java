package org.redwoodarrows;

import android.app.AlertDialog;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Local, graphical progress. No inference about improvement from a p-value or detector score. */
final class ProgressUi {
    final MainActivity a;String shooter="",setup="";boolean angular,checkedOnly=false,initialized;int window=100;double distance=Double.NaN;
    ProgressStats stats;ProgressStats.Window current;List<Store.Group> groups;
    boolean comparing=true;final ConsistencyUi comparison;long syncedGeneration;
    ProgressUi(MainActivity a){this.a=a;comparison=new ConsistencyUi(this);}
    void show(){
        syncedGeneration=SyncRuntime.libraryGeneration.get();
        if(comparing){comparison.show();return;}
        a.rememberGroups();a.page="progress";a.canvas=null;groups=a.store.list();
        if(!initialized){shooter=a.shooters.active().id;if(shooter.isEmpty()&&!a.shooters.roster.all().isEmpty())shooter=a.shooters.roster.all().get(0).id;selectContext();initialized=true;}
        stats=new ProgressStats(groups,shooter,setup,angular,checkedOnly,distance);current=stats.recent(window);
        header(false);
        if(a.shooters.roster.all().isEmpty()){a.add(a.root,a.text("Add a shooter to keep your progress separate.",16,MainActivity.MUTED));a.add(a.root,a.button("People",a.shooters::chooseActive));return;}
        ShooterRoster.Person person=a.shooters.roster.find(shooter);if(person==null)person=ShooterRoster.UNASSIGNED;
        ShooterPager people=new ShooterPager(a,a.shooters.roster.all(),person,false,"Progress for",p->{shooter=p.id;selectContext();show();return true;});people.setTag("progress-shooter");a.add(a.root,people);
        LinearLayout contexts=a.row();Button gear=a.button(setup.isEmpty()?"Unrecorded setup":setup,this::chooseSetup);gear.setTag("progress-setup");a.cell(contexts,gear);Button ranges=a.button(Practice.distance(distance)?TargetPreset.number(distance)+" m":"All distances",this::chooseDistance);ranges.setTag("progress-distance");a.cell(contexts,ranges);a.add(a.root,contexts);
        LinearLayout modes=a.row();TwoShooterSwitch units=new TwoShooterSwitch(a,"mm","mrad",angular?1:0,"Coordinate units",i->{angular=i==1;show();return true;});units.setTag("progress-units");a.cell(modes,units);
        TwoShooterSwitch windows=new TwoShooterSwitch(a,"Last 30","Last 100",window==30?0:1,"Approximate shot window",i->{window=i==0?30:100;show();return true;});windows.setTag("progress-window");a.cell(modes,windows);a.add(a.root,modes);
        ScrollView scroll=new ScrollView(a);scroll.setTag("progress-scroll");LinearLayout body=new LinearLayout(a);body.setOrientation(1);scroll.addView(body);a.root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        String count=current.records+" "+(current.countsKnown?"shots":"observed records")+" · "+current.ends.size()+" ends · "+current.sessions()+" days";TextView coverage=a.text(count,13,MainActivity.MUTED);coverage.setTag("progress-coverage");a.add(body,coverage);
        if(current.points.isEmpty())a.add(body,a.text(angular?"No positions with known distances in this selection. Set an end’s distance below, or switch to mm.":"No positions in this selection. Try All estimates or check the shooter and setup.",15,MainActivity.MUTED));
        Plot plot=new Plot(a,current,angular);plot.setTag("progress-plot");body.addView(plot,new LinearLayout.LayoutParams(-1,a.dp(245)));
        String unit=angular?"mrad":"mm";LinearLayout first=a.row();metric(first,"Tightness · RMS",current.points.size()>1?fmt(current.spread):"—",unit,"progress-spread");metric(first,"Center offset",fmt(current.offset()),unit,"progress-offset");a.add(body,first);
        LinearLayout second=a.row();metric(second,"Error from bullseye",fmt(current.error),unit,"progress-error");metric(second,"Unlocated misses",Double.isFinite(current.missRate())?String.format(Locale.US,"%.0f%%",100*current.missRate()):"?",current.misses+" reported","progress-misses");a.add(body,second);
        if(!current.points.isEmpty()){
            String diameter="80% circle diameter: "+fmt(2*current.r80)+" "+unit;
            if(angular)diameter+=" · ≈ "+fmt(4*current.r80)+" cm at 20 m";
            a.add(body,a.text(diameter,12,MainActivity.MUTED));a.add(body,a.text(String.format(Locale.US,"Center: x %+.1f, y %+.1f %s · H/V spread: %.1f / %.1f",current.x,current.y,unit,current.sx,current.sy),12,MainActivity.MUTED));
        }
        TextView quality=a.text((current.unchecked>0?current.unchecked+" unchecked ends · exploratory. ":"")+(current.unlocated>0?current.unlocated+" unlocated arrows. ":"")+(!current.countsKnown?"Total shot/miss counts unknown. ":"")+"Position stats use located arrows only.",12,0xFFFFBE91);quality.setTag("progress-quality");a.add(body,quality);
        TwoShooterSwitch checked=new TwoShooterSwitch(a,"All estimates","Checked only",checkedOnly?1:0,"Position review filter",i->{checkedOnly=i==1;show();return true;});checked.setTag("progress-review");a.add(body,checked);
        a.add(body,a.text("TIGHTNESS TREND · "+unit,12,MainActivity.MUTED));Trend trend=new Trend(a,stats.trend(window));trend.setTag("progress-trend");body.addView(trend,new LinearLayout.LayoutParams(-1,a.dp(145)));
        ProgressStats.Window now100=stats.recent(100),before100=stats.previous(100);String compare="Last "+now100.records+": "+fmt(now100.spread)+"  /  Previous "+before100.records+": "+fmt(before100.spread)+" "+unit;
        if(now100.records>=100&&before100.records>=100&&before100.spread>0&&Double.isFinite(now100.spread))compare+=String.format(Locale.US,"  (%+.0f%%)",100*(now100.spread/before100.spread-1));else compare+=" · building 100-shot baselines";
        TextView baseline=a.text(compare,13,MainActivity.FG);baseline.setTag("progress-baseline");a.add(body,baseline);
        a.add(body,a.text("Whole ends stay together; arrow order is unknown. Comparison blocks do not overlap. This is descriptive, not proof of improvement.",12,MainActivity.MUTED));
        List<ProgressStats.Window> sessions=stats.sessions();if(!sessions.isEmpty()){
            a.add(body,a.text("BY DAY · tightness / shots",12,MainActivity.MUTED));SimpleDateFormat date=new SimpleDateFormat("MMM d",Locale.US);for(int i=0;i<Math.min(7,sessions.size());i++){ProgressStats.Window w=sessions.get(i);a.add(body,a.text(date.format(new Date(w.ends.get(0).group.created))+"    "+fmt(w.spread)+" "+unit+"    /    "+w.records+(w.countsKnown?" shots":" observed"),13,MainActivity.FG));}
        }
        String exclusions=stats.noDistance+" ends lack distance · "+stats.unconfirmedOwner+" have unconfirmed owners · "+stats.notChecked+" unchecked excluded"+(stats.wrongDistance>0?" · "+stats.wrongDistance+" outside fixed-distance filter":"")+(stats.unsupportedTarget>0?" · "+stats.unsupportedTarget+" paper faces have no defined bullseye":"");TextView excluded=a.text(exclusions,12,MainActivity.MUTED);excluded.setTag("progress-excluded");a.add(body,excluded);
        a.add(body,a.text("END CONTEXT · tap to set distance or check counts",12,MainActivity.MUTED));int rows=0;
        for(Store.Group g:groups)if(g.hasData()&&g.shooter.id.equals(shooter)&&g.practice.setup.equals(setup)&&rows++<50){
            Button end=a.button(new GroupPresentation(g).caption()+" · "+(g.practice.arrowMeters.isEmpty()?g.practice.label():"Walk-up distances")+" · "+(g.practice.isChecked(g)?"checked":"unchecked"),()->{a.returnToProgress=true;a.openGroup(g);});end.setTag("progress-end:"+g.id);a.add(body,end);
        }
    }
    void header(boolean compare){
        a.shell("Progress");a.root.removeView(a.heading);LinearLayout top=a.row();top.setGravity(Gravity.CENTER_VERTICAL);
        Button back=a.button("‹",a::home);back.setTag("progress-back");back.setContentDescription("Back to grouping gallery");top.addView(back,new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));
        a.heading.setPadding(a.dp(10),0,0,0);top.addView(a.heading,new LinearLayout.LayoutParams(0,a.dp(48),1));
        Button switchView=a.button(compare?"Details":"Compare",()->{comparing=!comparing;show();});switchView.setTag(compare?"progress-details":"progress-compare");top.addView(switchView,new LinearLayout.LayoutParams(a.dp(78),a.dp(44)));
        Button help=a.button("?",()->{if(compare)comparison.help();else help();});help.setContentDescription("Progress statistics explained");top.addView(help,new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));a.add(a.root,top);
    }
    void refreshFromSync(){if(comparing&&!comparison.optionsOpen()&&syncedGeneration!=SyncRuntime.libraryGeneration.get())show();}
    void selectContext(){distance=Double.NaN;setup="";angular=false;if(groups!=null)for(Store.Group g:groups)if(g.hasData()&&g.shooter.id.equals(shooter)){setup=g.practice.setup;angular=Practice.distance(g.practice.meters)||!g.practice.arrowMeters.isEmpty();break;}}
    void metric(LinearLayout row,String label,String value,String unit,String tag){LinearLayout cell=new LinearLayout(a);cell.setOrientation(1);cell.setPadding(a.dp(10),a.dp(5),a.dp(10),a.dp(5));cell.setBackground(a.rounded(MainActivity.PANEL));TextView n=a.text(value,25,MainActivity.FG);n.setTypeface(null,1);n.setTag(tag);cell.addView(n);cell.addView(a.text(label,11,MainActivity.MUTED));cell.addView(a.text(unit,11,MainActivity.MUTED));a.cell(row,cell);}
    void chooseSetup(){TreeSet<String> choices=new TreeSet<>();for(Store.Group g:groups)if(g.hasData()&&g.shooter.id.equals(shooter))choices.add(g.practice.setup);if(choices.isEmpty())choices.add("");List<String> values=new ArrayList<>(choices);String[] labels=new String[values.size()];for(int i=0;i<labels.length;i++)labels[i]=values.get(i).isEmpty()?"Unrecorded setup":values.get(i);new AlertDialog.Builder(a).setTitle("Keep equipment setups separate").setItems(labels,(d,i)->{setup=values.get(i);distance=Double.NaN;show();}).setNegativeButton("Cancel",null).show();}
    void chooseDistance(){TreeSet<Double> choices=new TreeSet<>();for(Store.Group g:groups)if(g.hasData()&&g.shooter.id.equals(shooter)&&g.practice.setup.equals(setup)){if(Practice.distance(g.practice.meters))choices.add(g.practice.meters);choices.addAll(g.practice.arrowMeters.values());}List<Double> values=new ArrayList<>(choices);String[] labels=new String[values.size()+1];labels[0]="All distances · angular comparison is approximate";for(int i=0;i<values.size();i++)labels[i+1]=TargetPreset.number(values.get(i))+" m / "+TargetPreset.number(Practice.fromMeters(values.get(i),"yd"))+" yd";new AlertDialog.Builder(a).setTitle("Fixed-distance ends").setItems(labels,(d,i)->{distance=i==0?Double.NaN:values.get(i-1);show();}).setNegativeButton("Cancel",null).show();}
    static String fmt(double value){return Double.isFinite(value)?String.format(Locale.US,"%.1f",value):"—";}
    void help(){a.message("Tightness is RMS distance from the combined mean, not standard error. Center offset is that mean relative to bullseye. Error is RMS distance from bullseye. The 80% circle encloses 80% of observed positions about their mean; all flyers remain visible.\n\nAngular coordinates are x_mm / distance_m and y_mm / distance_m (mrad, small-angle approximation). Distances come only from your entries. No automatic ranging is enabled. Compare the same setup/distance when possible; wind and trajectory still matter. Offsets assume consistent target-up calibration.\n\nMultiple views of one arrow count once. Windows keep whole ends, so Last 100 may contain 102 shots. Rolling points overlap; previous-100 comparison blocks do not. By day combines all shooting that calendar day.\n\nUnlocated misses are reported separately, never given an invented impact coordinate. Unknown counts stay unknown. Checked means you confirmed positions and accounted for all arrows, not certified accuracy. Changing coordinates invalidates that check. Fixed-distance filters exclude mixed-distance ends. All estimates is exploratory, especially if your setup was not recorded.");}
    static final class Plot extends View {
        final ProgressStats.Window w;final boolean angular;final Paint p=new Paint(3);final float density;
        Plot(MainActivity a,ProgressStats.Window w,boolean angular){super(a);this.w=w;this.angular=angular;density=getResources().getDisplayMetrics().density;setBackground(a.rounded(MainActivity.PANEL));setContentDescription("Combined shot plot. "+w.points.size()+" located arrows. Center x "+fmt(w.x)+", y "+fmt(w.y)+". 80 percent circle radius "+fmt(w.r80)+(angular?" milliradians":" millimeters"));}
        protected void onDraw(Canvas c){float cx=getWidth()/2f,cy=getHeight()/2f-8*density,rad=Math.min(getWidth(),getHeight())/2f-28*density;double extent=angular?1:10;for(double[] pt:w.points)extent=Math.max(extent,Math.max(Math.abs(pt[0]),Math.abs(pt[1]))*1.15);if(Double.isFinite(w.r80))extent=Math.max(extent,(Math.max(Math.abs(w.x),Math.abs(w.y))+w.r80)*1.1);float scale=(float)(rad/extent);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(density);p.setColor(0xFF506257);for(int i=1;i<=3;i++)c.drawCircle(cx,cy,rad*i/3,p);c.drawLine(cx-rad,cy,cx+rad,cy,p);c.drawLine(cx,cy-rad,cx,cy+rad,p);
            if(Double.isFinite(w.x)){float x=cx+(float)w.x*scale,y=cy-(float)w.y*scale;p.setColor(0xFFC6C4EA);p.setStrokeWidth(2*density);if(Double.isFinite(w.r80))c.drawCircle(x,y,(float)w.r80*scale,p);c.drawLine(cx,cy,x,y,p);c.drawLine(x-5*density,y,x+5*density,y,p);c.drawLine(x,y-5*density,x,y+5*density,p);}
            p.setStyle(Paint.Style.FILL);p.setColor(0xFFFFB56A);for(double[] pt:w.points)c.drawCircle(cx+(float)pt[0]*scale,cy-(float)pt[1]*scale,2.8f*density,p);p.setColor(MainActivity.FG);c.drawCircle(cx,cy,2*density,p);p.setTextSize(11*getResources().getDisplayMetrics().scaledDensity);p.setTextAlign(Paint.Align.CENTER);p.setColor(MainActivity.MUTED);c.drawText("±"+fmt(extent)+(angular?" mrad":" mm")+" · white: bullseye · lilac: 80%",cx,getHeight()-10*density,p);
        }
    }
    static final class Trend extends View {
        final List<ProgressStats.Window> windows;final Paint p=new Paint(3);final float density;
        Trend(MainActivity a,List<ProgressStats.Window> windows){super(a);this.windows=windows;density=getResources().getDisplayMetrics().density;setBackground(a.rounded(MainActivity.PANEL));setContentDescription("Rolling group tightness by end, oldest to newest. Adjacent windows overlap. Lower is tighter.");}
        protected void onDraw(Canvas c){float left=34*density,right=getWidth()-15*density,top=15*density,bottom=getHeight()-28*density;double max=1;for(ProgressStats.Window w:windows)if(Double.isFinite(w.spread))max=Math.max(max,w.spread*1.15);p.setColor(0xFF506257);p.setStrokeWidth(density);c.drawLine(left,bottom,right,bottom,p);p.setTextSize(10*getResources().getDisplayMetrics().scaledDensity);p.setTextAlign(Paint.Align.LEFT);p.setColor(MainActivity.MUTED);c.drawText(fmt(max),2*density,top+8*density,p);c.drawText("0",10*density,bottom,p);c.drawText("Older",left,getHeight()-8*density,p);p.setTextAlign(Paint.Align.RIGHT);c.drawText("Newest",right,getHeight()-8*density,p);float px=0,py=0;boolean prior=false;
            for(int i=0;i<windows.size();i++){ProgressStats.Window w=windows.get(i);if(!Double.isFinite(w.spread)||w.points.size()<2){prior=false;continue;}float x=windows.size()==1?right:left+(right-left)*i/(windows.size()-1),y=bottom-(float)(w.spread/max)*(bottom-top);p.setColor(MainActivity.ACCENT);p.setStrokeWidth(2*density);if(prior)c.drawLine(px,py,x,y,p);c.drawCircle(x,y,3*density,p);px=x;py=y;prior=true;}
        }
    }
}
