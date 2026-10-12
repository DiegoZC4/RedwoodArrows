package org.redwoodarrows;

import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.*;

/** One grouping per table row: 2D, rectified evidence, untouched photo, shooter. */
final class GroupingGallery {
    final MainActivity a;final List<Store.Group> all,visible=new ArrayList<>();
    final Map<String,GroupPresentation> summaries=new HashMap<>();GridView grid;TextView count;Button undo;
    GroupingGallery(MainActivity a,List<Store.Group> groups){this.a=a;all=groups;for(Store.Group g:groups)if(g.hasData()&&ShooterRoster.matches(a.shooters.filter,g.shooter)){visible.add(g);summaries.put(g.id,new GroupPresentation(g));}}
    void show(){
        LinearLayout heading=a.row();heading.setGravity(Gravity.CENTER_VERTICAL);
        count=a.text("",12,MainActivity.MUTED);count.setMaxLines(2);count.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(count,new LinearLayout.LayoutParams(0,-2,1));
        undo=a.button("Undo",a::undoShooter);undo.setTag("shooter-undo");LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(a.dp(58),a.dp(44));up.setMargins(a.dp(4),0,a.dp(6),0);heading.addView(undo,up);
        Button filter=a.button(a.shooters.filterLabel(),()->a.shooters.chooseFilter(all));filter.setTag("shooter-history-filter");filter.setTextSize(13);
        heading.addView(filter,new LinearLayout.LayoutParams(a.dp(116),a.dp(44)));a.add(a.root,heading);updateHeading();
        LinearLayout columns=a.row();columns.setPadding(a.dp(3),0,a.dp(3),0);columns.setTag("group-columns");for(String label:new String[]{"2D","Warped","Original","Shooter"}){TextView t=line(label,11,MainActivity.MUTED);t.setGravity(Gravity.CENTER);column(columns,t,-2);}a.root.addView(columns);
        grid=new GridView(a);grid.setTag("grouping-grid");grid.setNumColumns(1);grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(a.dp(5));grid.setPadding(0,a.dp(4),0,a.dp(8));grid.setClipToPadding(false);grid.setSelector(android.R.color.transparent);
        grid.setAdapter(new BaseAdapter(){
            // This adapter is a fixed snapshot, not a stable-ID adapter. Android's
            // saved scroll state treats negative IDs as absent, so UUID hashes
            // must not be used as item IDs here.
            public int getCount(){return visible.size();}public Store.Group getItem(int p){return visible.get(p);}public long getItemId(int p){return p;}
            public View getView(int position,View recycled,ViewGroup parent){return card(getItem(position));}
        });
        grid.setOnItemClickListener((p,v,position,id)->{remember();a.openGroup(visible.get(position));});
        a.root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout empty=new LinearLayout(a);empty.setGravity(Gravity.CENTER);empty.setOrientation(1);
        empty.addView(new TargetCanvas(a,TargetCatalog.defaultPreset()).thumbnail(),new LinearLayout.LayoutParams(a.dp(180),a.dp(180)));
        TextView prompt=a.text(all.stream().anyMatch(Store.Group::hasData)?"No groupings for this shooter":"Your groupings will appear here",16,MainActivity.MUTED);prompt.setGravity(Gravity.CENTER);empty.addView(prompt);
        a.root.addView(empty,new LinearLayout.LayoutParams(-1,0,1));grid.setEmptyView(empty);
        // Keep keyboard focus on the screen, not the grid's first item. Taking
        // focus after restoring a native grid otherwise scrolls it back to row 0.
        a.root.setFocusableInTouchMode(true);a.root.requestFocus();
        if(a.groupGridState!=null&&a.shooters.filter.equals(a.groupGridFilter))grid.onRestoreInstanceState(a.groupGridState);
    }
    void column(LinearLayout row,View view,int height){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,height,1);p.setMargins(a.dp(2),0,a.dp(2),0);row.addView(view,p);}
    View card(Store.Group group){
        GroupPresentation s=summaries.get(group.id);LinearLayout row=a.row();row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(a.dp(3),a.dp(5),a.dp(3),a.dp(5));row.setBackground(a.rounded(MainActivity.PANEL));row.setTag("saved-group:"+group.id);
        int height=a.dp(120)+Math.round(Math.max(0,a.getResources().getConfiguration().fontScale-1)*a.dp(78)),reference=GroupImages.reference(group);
        TargetCanvas plot=new TargetCanvas(a,group).thumbnail();plot.setTag("group-plot:"+group.id);plot.setContentDescription(s.caption()+". 2D grouping. Open full screen");plot.setOnClickListener(v->a.showGroupImage(group,GroupImages.PLOT,reference));column(row,plot,height);
        GroupImages.Preview warped=a.groupImages.new Preview(group,reference,GroupImages.WARPED);warped.setTag("group-warp:"+group.id);warped.setOnClickListener(v->a.showGroupImage(group,GroupImages.WARPED,reference));column(row,warped,height);
        GroupImages.Preview original=a.groupImages.new Preview(group,reference,GroupImages.ORIGINAL);original.setTag("group-raw:"+group.id);original.setOnClickListener(v->a.showGroupImage(group,GroupImages.ORIGINAL,reference));column(row,original,height);
        LinearLayout owner=new LinearLayout(a);owner.setOrientation(1);owner.setGravity(Gravity.CENTER_VERTICAL);owner.setTag("group-owner:"+group.id);
        ShooterPager name=new ShooterPager(a,a.shooters.roster.all(),group.shooter,s.provisionalOwner(),s.caption(),person->assign(group,person));name.setTag("group-shooter-pager:"+group.id);owner.addView(name,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout info=new LinearLayout(a);info.setOrientation(1);info.setGravity(Gravity.CENTER_VERTICAL);info.setTag("group-details:"+group.id);info.setFocusable(true);info.setClickable(true);info.setOnClickListener(v->{remember();a.openGroup(group);});info.setContentDescription(s.caption()+". "+s.count()+". Open grouping details.");
        info.addView(line(s.caption(),11,MainActivity.MUTED));info.addView(line(new java.text.SimpleDateFormat("MMM d",Locale.US).format(new Date(group.created)),10,MainActivity.MUTED));info.addView(line(s.count(),10,MainActivity.FG));if(s.photos>1)info.addView(line(s.photos+" photos",10,MainActivity.MUTED));for(int i=0;i<info.getChildCount();i++)info.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);owner.addView(info,new LinearLayout.LayoutParams(-1,0,1));
        if(Practice.distance(group.practice.meters))info.addView(line(group.practice.label(),10,MainActivity.MUTED));
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
