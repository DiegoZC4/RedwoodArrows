package org.redwoodarrows;

import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.*;

/** One grouping per table row: 2D, rectified evidence, untouched photo, shooter. */
final class GroupingGallery {
    final MainActivity a;final List<Store.Group> all,visible=new ArrayList<>();
    final Map<String,GroupPresentation> summaries=new HashMap<>();GridView grid;TextView count;Button undo,edit;BaseAdapter adapter;
    GroupingGallery(MainActivity a,List<Store.Group> groups){this.a=a;all=groups;for(Store.Group g:groups)if(g.hasData()&&ShooterRoster.matches(a.shooters.filter,g.shooter)&&(a.groupingDay==0||ConsistencyStats.day(g.created)==a.groupingDay)){visible.add(g);summaries.put(g.id,new GroupPresentation(g));}Set<String> ids=new HashSet<>();for(Store.Group g:visible)ids.add(g.id);a.bulkSelection.retainAll(ids);}
    void show(){
        LinearLayout heading=a.row();heading.setGravity(Gravity.CENTER_VERTICAL);
        count=a.text("",12,MainActivity.MUTED);count.setMaxLines(2);count.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(count,new LinearLayout.LayoutParams(0,-2,1));
        undo=a.button("Undo",a::undoShooter);undo.setTag("shooter-undo");LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(a.dp(58),a.dp(44));up.setMargins(a.dp(4),0,a.dp(6),0);heading.addView(undo,up);
        Button filter=a.button(a.shooters.filterLabel(),()->a.shooters.chooseFilter(all));filter.setTag("shooter-history-filter");filter.setTextSize(13);
        heading.addView(filter,new LinearLayout.LayoutParams(a.dp(116),a.dp(44)));a.add(a.root,heading);updateHeading();
        HorizontalScrollView days=new HorizontalScrollView(a);days.setHorizontalScrollBarEnabled(false);days.setTag("gallery-days");LinearLayout dates=a.row();days.addView(dates);
        dayButton(dates,0,"All days");for(long day:LabelCoverage.days(all,null,Double.NaN).keySet())dayButton(dates,day,new java.text.SimpleDateFormat("MMM d",Locale.US).format(new Date(day)));a.add(a.root,days);
        LinearLayout selection=a.row();Button select=a.button(a.bulkSelecting?"Done":"Select",()->{remember();a.bulkSelecting=!a.bulkSelecting;if(!a.bulkSelecting)a.bulkSelection.clear();a.home();});select.setTag("bulk-select-mode");a.cell(selection,select);
        if(a.bulkSelecting){for(int mode=0;mode<3;mode++){final int parity=mode;String label=mode==0?"All":mode==1?"1,3,5…":"2,4,6…";Button button=a.button(label,()->selectRows(parity));button.setTag("bulk-select:"+mode);button.setContentDescription(mode==0?"Select or clear all visible rounds":"Select alternating visible rounds, starting with "+(mode==1?"first":"second")+" in chronological order");a.cell(selection,button);}}
        else try{int n=BulkLabels.undoCount(a.store);if(n>0){Button restore=a.button("Undo "+n+" labels",()->{try{BulkLabels.undo(a.store);a.bulkNotice="Bulk labels undone";a.home();}catch(Exception e){a.message(e.getMessage());}});restore.setTag("bulk-undo");a.cell(selection,restore);}}catch(Exception ignored){}
        a.add(a.root,selection);
        if(!a.bulkNotice.isEmpty()){TextView notice=a.text(a.bulkNotice,12,MainActivity.ACCENT);notice.setTag("bulk-notice");a.add(a.root,notice);}
        LinearLayout columns=a.row();columns.setPadding(a.dp(3),0,a.dp(3),0);columns.setTag("group-columns");for(String label:new String[]{"2D","Warped","Original","Shooter"}){TextView t=line(label,11,MainActivity.MUTED);t.setGravity(Gravity.CENTER);column(columns,t,-2);}a.root.addView(columns);
        grid=new GridView(a);grid.setTag("grouping-grid");grid.setNumColumns(1);grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(a.dp(5));grid.setPadding(0,a.dp(4),0,a.dp(8));grid.setClipToPadding(false);grid.setSelector(android.R.color.transparent);
        adapter=new BaseAdapter(){
            // This adapter is a fixed snapshot, not a stable-ID adapter. Android's
            // saved scroll state treats negative IDs as absent, so UUID hashes
            // must not be used as item IDs here.
            public int getCount(){return visible.size();}public Store.Group getItem(int p){return visible.get(p);}public long getItemId(int p){return p;}
            public View getView(int position,View recycled,ViewGroup parent){return card(getItem(position));}
        };grid.setAdapter(adapter);
        grid.setOnItemClickListener((p,v,position,id)->{if(a.bulkSelecting)toggle(visible.get(position));else{remember();a.openGroup(visible.get(position));}});
        a.root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout empty=new LinearLayout(a);empty.setGravity(Gravity.CENTER);empty.setOrientation(1);
        empty.addView(new TargetCanvas(a,TargetCatalog.defaultPreset()).thumbnail(),new LinearLayout.LayoutParams(a.dp(180),a.dp(180)));
        TextView prompt=a.text(all.stream().anyMatch(Store.Group::hasData)?"No groupings for this shooter":"Your groupings will appear here",16,MainActivity.MUTED);prompt.setGravity(Gravity.CENTER);empty.addView(prompt);
        a.root.addView(empty,new LinearLayout.LayoutParams(-1,0,1));grid.setEmptyView(empty);
        if(a.bulkSelecting){edit=a.button("",()->{List<Store.Group> selected=new ArrayList<>();for(Store.Group g:visible)if(a.bulkSelection.contains(g.id))selected.add(g);a.bulkLabelsUi=new BulkLabelsUi(a);a.bulkLabelsUi.show(selected);});edit.setTag("bulk-edit");edit.setBackground(a.rounded(MainActivity.ACCENT));edit.setTextColor(MainActivity.BG);a.add(a.root,edit);updateSelection();}
        // Keep keyboard focus on the screen, not the grid's first item. Taking
        // focus after restoring a native grid otherwise scrolls it back to row 0.
        a.root.setFocusableInTouchMode(true);a.root.requestFocus();
        if(a.groupGridState!=null&&a.shooters.filter.equals(a.groupGridFilter))grid.onRestoreInstanceState(a.groupGridState);
    }
    void dayButton(LinearLayout row,long day,String label){Button b=a.button(label,()->{remember();a.groupingDay=day;a.groupGridState=null;a.bulkSelection.clear();a.home();});b.setTag("gallery-day:"+day);b.setTextSize(12);if(day==a.groupingDay){b.setBackground(a.rounded(MainActivity.ACCENT));b.setTextColor(MainActivity.BG);}LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,a.dp(44));lp.setMargins(0,0,a.dp(6),0);row.addView(b,lp);}
    void selectRows(int mode){boolean clear=mode==0&&a.bulkSelection.size()==visible.size();a.bulkSelection.clear();List<Store.Group> ordered=new ArrayList<>(visible);ordered.sort((x,y)->{int c=Long.compare(x.created,y.created);return c==0?x.id.compareTo(y.id):c;});if(!clear)for(int i=0;i<ordered.size();i++)if(mode==0||i%2==mode-1)a.bulkSelection.add(ordered.get(i).id);adapter.notifyDataSetChanged();updateSelection();}
    void toggle(Store.Group g){if(!a.bulkSelection.remove(g.id))a.bulkSelection.add(g.id);adapter.notifyDataSetChanged();updateSelection();}
    void updateSelection(){if(edit!=null){edit.setText("Label "+a.bulkSelection.size()+" selected rounds");edit.setEnabled(!a.bulkSelection.isEmpty());}if(a.bulkSelecting)count.setText(a.bulkSelection.size()+" / "+visible.size()+" selected");}
    void picture(Store.Group g,int kind,int reference){if(a.bulkSelecting)toggle(g);else a.showGroupImage(g,kind,reference);}
    boolean beginSelection(Store.Group g){if(a.bulkSelecting)toggle(g);else{remember();a.bulkSelecting=true;a.bulkSelection.add(g.id);a.home();}return true;}
    void column(LinearLayout row,View view,int height){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,height,1);p.setMargins(a.dp(2),0,a.dp(2),0);row.addView(view,p);}
    View card(Store.Group group){
        GroupPresentation s=summaries.get(group.id);LinearLayout row=a.row();row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(a.dp(3),a.dp(5),a.dp(3),a.dp(5));row.setBackground(a.rounded(MainActivity.PANEL));row.setTag("saved-group:"+group.id);
        int height=a.dp(164)+Math.round(Math.max(0,a.getResources().getConfiguration().fontScale-1)*a.dp(100)),reference=GroupImages.reference(group);
        TargetCanvas plot=new TargetCanvas(a,group).thumbnail();plot.setTag("group-plot:"+group.id);plot.setContentDescription(s.caption()+". 2D grouping. "+(a.bulkSelecting?"Tap to select round":"Open full screen; hold to select"));plot.setOnClickListener(v->picture(group,GroupImages.PLOT,reference));plot.setOnLongClickListener(v->beginSelection(group));
        if(a.bulkSelecting){FrameLayout frame=new FrameLayout(a);frame.addView(plot,new FrameLayout.LayoutParams(-1,-1));CheckBox check=new CheckBox(a);check.setChecked(a.bulkSelection.contains(group.id));check.setTag("bulk-check:"+group.id);check.setContentDescription("Select "+s.caption());check.setOnClickListener(v->toggle(group));frame.addView(check,new FrameLayout.LayoutParams(a.dp(44),a.dp(44),Gravity.TOP|Gravity.LEFT));column(row,frame,height);row.setBackground(a.rounded(a.bulkSelection.contains(group.id)?0xFF385248:MainActivity.PANEL));}else column(row,plot,height);
        GroupImages.Preview warped=a.groupImages.new Preview(group,reference,GroupImages.WARPED);warped.setTag("group-warp:"+group.id);warped.setOnClickListener(v->picture(group,GroupImages.WARPED,reference));warped.setOnLongClickListener(v->beginSelection(group));column(row,warped,height);
        GroupImages.Preview original=a.groupImages.new Preview(group,reference,GroupImages.ORIGINAL);original.setTag("group-raw:"+group.id);original.setOnClickListener(v->picture(group,GroupImages.ORIGINAL,reference));original.setOnLongClickListener(v->beginSelection(group));column(row,original,height);
        LinearLayout owner=new LinearLayout(a);owner.setOrientation(1);owner.setGravity(Gravity.CENTER_VERTICAL);owner.setTag("group-owner:"+group.id);
        ShooterPager name=new ShooterPager(a,a.shooters.roster.all(),group.shooter,s.provisionalOwner(),s.caption(),person->assign(group,person));name.setTag("group-shooter-pager:"+group.id);owner.addView(name,new LinearLayout.LayoutParams(-1,-2));
        View distance=PracticeUi.inline(a,group,s.caption(),()->{try{a.shooterUndo=a.store.lastShooterEdit();}catch(Exception ignored){a.shooterUndo=null;}updateHeading();});distance.setTag("group-distance:"+group.id);LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-1,-2);dp.setMargins(0,a.dp(4),0,0);owner.addView(distance,dp);
        if(a.bulkSelecting){name.setEnabled(false);distance.setEnabled(false);owner.setOnClickListener(v->toggle(group));}
        LinearLayout info=new LinearLayout(a);info.setOrientation(1);info.setGravity(Gravity.CENTER_VERTICAL);info.setTag("group-details:"+group.id);info.setFocusable(true);info.setClickable(true);info.setOnClickListener(v->{remember();a.openGroup(group);});info.setContentDescription(s.caption()+". "+s.count()+". Open grouping details.");
        if(a.bulkSelecting)info.setOnClickListener(v->toggle(group));info.setOnLongClickListener(v->beginSelection(group));
        info.addView(line(s.caption(),11,MainActivity.MUTED));info.addView(line(new java.text.SimpleDateFormat("MMM d",Locale.US).format(new Date(group.created)),10,MainActivity.MUTED));info.addView(line(s.count(),10,MainActivity.FG));if(s.photos>1)info.addView(line(s.photos+" photos",10,MainActivity.MUTED));for(int i=0;i<info.getChildCount();i++)info.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);owner.addView(info,new LinearLayout.LayoutParams(-1,0,1));
        column(row,owner,height);return row;
    }
    private boolean assign(Store.Group group,ShooterRoster.Person person){
        if(a.busy||a.groupingGallery!=this||!a.page.equals("home"))return false;
        try{
            a.shooterUndo=a.store.editSavedShooter(group,person);Store.Group changed=a.shooterUndo.changed;Store.confirmShooter(group,changed.shooter);summaries.put(group.id,new GroupPresentation(group));updateHeading();
            if(a.group!=null&&a.group.id.equals(group.id)){a.group=changed;a.undo.clear();a.analysis=null;}
            if(!ShooterRoster.matches(a.shooters.filter,person)){remember();grid.post(()->{if(a.groupingGallery==this&&a.page.equals("home"))a.home();});}
            return true;
        }catch(Exception e){a.message("Could not save this grouping's shooter: "+e.getMessage());return false;}
    }
    void updateHeading(){
        boolean available=a.shooterUndo!=null;undo.setVisibility(available?View.VISIBLE:View.GONE);
        if(available){Store.Group changed=a.shooterUndo.changed;String caption=new GroupPresentation(changed).caption(),previous=a.shooters.label(a.shooterUndo.previous);String label=caption+" → "+a.shooters.label(changed);count.setText(label+"\nUndo → "+previous);count.setContentDescription("Saved on this phone. "+label+". Undo restores "+previous);undo.setContentDescription("Undo: restore "+caption+" to "+previous);undo.setTooltipText("Restore "+caption+" to "+previous);}
        else{count.setText(visible.size()+" grouping"+(visible.size()==1?"":"s")+" · on phone");count.setContentDescription(count.getText());}
    }
    private TextView line(String text,int size,int color){TextView t=a.text(text,size,color);t.setPadding(0,a.dp(1),0,a.dp(1));t.setSingleLine(true);t.setEllipsize(TextUtils.TruncateAt.END);return t;}
    void remember(){if(grid!=null){a.groupGridState=grid.onSaveInstanceState();a.groupGridFilter=a.shooters.filter;}}
    int position(String id){for(int i=0;i<visible.size();i++)if(visible.get(i).id.equals(id))return i;return -1;}
}
