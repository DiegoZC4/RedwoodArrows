package org.redwoodarrows;

import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.*;

/** One readable photo and review state per grouping. */
final class GroupingGallery {
    final MainActivity a;final List<Store.Group> all,visible=new ArrayList<>();
    final Map<String,GroupPresentation> summaries=new HashMap<>();final Map<String,Set<String>> checkedPhotos=new HashMap<>();GridView grid;TextView count;Button undo,edit,review;BaseAdapter adapter;
    GroupingGallery(MainActivity a,List<Store.Group> groups){this.a=a;all=groups;for(Store.Group g:groups)if(g.hasData()&&ShooterRoster.matches(a.shooters.filter,g.shooter)&&(a.groupingDay==0||ConsistencyStats.day(g.created)==a.groupingDay)&&(!a.reviewOnly||!g.views.isEmpty()||!g.practice.isChecked(g))){visible.add(g);summaries.put(g.id,new GroupPresentation(g));}Set<String> ids=new HashSet<>();for(Store.Group g:visible)ids.add(g.id);a.bulkSelection.retainAll(ids);}
    void show(){
        LinearLayout heading=a.row();heading.setGravity(Gravity.CENTER_VERTICAL);
        count=a.text("",12,MainActivity.MUTED);count.setMaxLines(2);count.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(count,new LinearLayout.LayoutParams(0,-2,1));
        undo=a.button("Undo",a::undoShooter);undo.setTag("shooter-undo");LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(a.dp(58),a.dp(44));up.setMargins(a.dp(4),0,a.dp(6),0);heading.addView(undo,up);
        LinearLayout filters=a.row();
        a.cell(filters,LibraryFilters.labeled(a,"Shooter",LibraryFilters.shooter(a,"shooter-history-filter",a::home)));
        a.cell(filters,LibraryFilters.labeled(a,"Day",LibraryFilters.day(a,LibraryFilter.days(all,a.shooters.filter),a.groupingDay,true,"gallery-day-filter",day->{if(day==a.groupingDay)return;a.groupingDay=day;a.groupGridState=null;a.bulkSelection.clear();a.home();})));
        a.add(a.root,filters);
        // Quick filters and Select share one row; the count line above appears only for shooter undo or a selection.
        Button select=a.button(a.bulkSelecting?"Done":"Select",()->{remember();a.bulkSelecting=!a.bulkSelecting;if(!a.bulkSelecting)a.bulkSelection.clear();a.home();});select.setTag("bulk-select-mode");
        LinearLayout selection=a.row();a.add(a.root,heading);
        if(!a.bulkSelecting){LinearLayout quick=a.row();quick.setGravity(Gravity.CENTER_VERTICAL);
            CheckBox needs=new CheckBox(a);needs.setText("Needs review");ReviewControls.chip(a,needs,MainActivity.ACCENT,MainActivity.BG);needs.setTextSize(13);needs.setChecked(a.reviewOnly);needs.setTag("gallery-needs-review");needs.setOnCheckedChangeListener((b,checked)->{a.reviewOnly=checked;a.groupGridState=null;a.home();});quick.addView(needs,new LinearLayout.LayoutParams(-2,a.dp(36)));
            // "No shooter" is the shared shooter filter set to Unassigned, one tap away; it only appears when it can show something.
            int missing=0;for(Store.Group g:all)if(g.hasData()&&!g.shooter.assigned()&&(a.groupingDay==0||ConsistencyStats.day(g.created)==a.groupingDay))missing++;boolean only=a.shooters.filter.isEmpty();
            if(missing>0||only){CheckBox none=new CheckBox(a);none.setText("No shooter · "+missing);ReviewControls.chip(a,none,MainActivity.ACCENT,MainActivity.BG);none.setTextSize(13);none.setChecked(only);none.setTag("gallery-no-shooter");none.setContentDescription(missing+" groupings without a shooter. "+(only?"Showing only these; tap to show all":"Tap to show only these"));
                none.setOnCheckedChangeListener((b,checked)->{if(a.busy||!a.shooters.setFilter(checked?"":ShooterRoster.ALL))return;a.groupGridState=null;a.bulkSelection.clear();a.home();});LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-2,a.dp(36));sp.setMargins(a.dp(8),0,0,0);quick.addView(none,sp);}
            quick.addView(new View(a),new LinearLayout.LayoutParams(0,1,1));HomeUi.quiet(a,select,MainActivity.ACCENT);quick.addView(select,new LinearLayout.LayoutParams(-2,a.dp(44)));a.add(a.root,quick);}
        else a.cell(selection,select);
        updateHeading();
        if(a.bulkSelecting){for(int mode=0;mode<3;mode++){final int parity=mode;String label=mode==0?"All":mode==1?"1,3,5…":"2,4,6…";Button button=a.button(label,()->selectRows(parity));button.setTag("bulk-select:"+mode);button.setContentDescription(mode==0?"Select or clear all visible rounds":"Select alternating visible rounds, starting with "+(mode==1?"first":"second")+" in chronological order");a.cell(selection,button);}}
        else try{int n=BulkLabels.undoCount(a.store);if(n>0){Button restore=a.button("Undo "+n+" labels",()->{try{BulkLabels.undo(a.store);a.bulkNotice="Bulk labels undone";a.home();}catch(Exception e){a.message(e.getMessage());}});restore.setTag("bulk-undo");a.cell(selection,restore);}}catch(Exception ignored){}
        if(selection.getChildCount()>0)a.add(a.root,selection);
        a.reviewQueue=new ReviewQueue(visible);
        if(!a.bulkSelecting&&!a.reviewQueue.items.isEmpty()){review=a.button("",this::startReview);review.setBackground(ReviewControls.outline(a));review.setTag("gallery-review");review.setTextColor(MainActivity.ACCENT);review.setTextSize(14);review.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);review.setPadding(a.dp(14),0,a.dp(14),0);a.root.addView(review,new LinearLayout.LayoutParams(-1,a.dp(44)));updateReview();}
        if(!a.bulkNotice.isEmpty()){TextView notice=a.text(a.bulkNotice,12,MainActivity.ACCENT);notice.setTag("bulk-notice");a.add(a.root,notice);}
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
        TextView prompt=a.text(all.stream().anyMatch(Store.Group::hasData)?"No matching groupings":"No groupings yet",16,MainActivity.MUTED);prompt.setGravity(Gravity.CENTER);empty.addView(prompt);
        a.root.addView(empty,new LinearLayout.LayoutParams(-1,0,1));grid.setEmptyView(empty);
        if(a.bulkSelecting){edit=a.button("",()->{List<Store.Group> selected=new ArrayList<>();for(Store.Group g:visible)if(a.bulkSelection.contains(g.id))selected.add(g);a.bulkLabelsUi=new BulkLabelsUi(a);a.bulkLabelsUi.show(selected);});edit.setTag("bulk-edit");edit.setBackground(a.rounded(MainActivity.ACCENT));edit.setTextColor(MainActivity.BG);a.add(a.root,edit);updateSelection();}
        // Keep keyboard focus on the screen, not the grid's first item. Taking
        // focus after restoring a native grid otherwise scrolls it back to row 0.
        a.root.setFocusableInTouchMode(true);a.root.requestFocus();
        if(a.groupGridState!=null&&a.shooters.filter.equals(a.groupGridFilter))grid.onRestoreInstanceState(a.groupGridState);
        if(a.scrollToGroup!=null){int at=position(a.scrollToGroup);a.scrollToGroup=null;if(at>=0)grid.setSelection(at);}
        List<Store.Group> loading=new ArrayList<>(visible);
        a.worker.execute(()->{
            Map<String,Set<String>> states=new HashMap<>();for(Store.Group group:loading)states.put(group.id,TrainingReviews.checkedPhotos(a.store,group));
            a.runOnUiThread(()->{if(a.isDestroyed()||a.groupingGallery!=this||!a.page.equals("home"))return;
                checkedPhotos.putAll(states);
                if(a.reviewOnly)visible.removeIf(g->summaries.get(g.id).photos>0&&checkedPhotos.get(g.id).size()==summaries.get(g.id).photos);
                a.reviewQueue=new ReviewQueue(visible);
                adapter.notifyDataSetChanged();updateHeading();updateSelection();updateReview();
            });
        });
    }
    /** Opens the first unchecked photo (or the first photo) with ‹ › stepping through this filtered list. */
    void startReview(){ReviewQueue queue=a.reviewQueue;if(queue==null||queue.items.isEmpty())return;ReviewQueue.Item item=queue.items.get(queue.firstUnchecked(checkedPhotos));remember();a.reviewStepped=true;a.assignTargets(item.group,item.view);a.reviewStepped=false;}
    void updateReview(){if(review==null)return;ReviewQueue queue=a.reviewQueue;int total=queue==null?0:queue.items.size(),open=queue==null?0:queue.unchecked(checkedPhotos);review.setVisibility(total==0?View.GONE:View.VISIBLE);
        review.setText(open>0?"Review "+open+" unchecked photo"+(open==1?"":"s")+"  ›":"Step through "+total+" photo"+(total==1?"":"s")+"  ›");review.setContentDescription(open>0?"Review unchecked photos one at a time":"Step through these photos one at a time");}
    void selectRows(int mode){boolean clear=mode==0&&a.bulkSelection.size()==visible.size();a.bulkSelection.clear();List<Store.Group> ordered=new ArrayList<>(visible);ordered.sort((x,y)->{int c=Long.compare(x.created,y.created);return c==0?x.id.compareTo(y.id):c;});if(!clear)for(int i=0;i<ordered.size();i++)if(mode==0||i%2==mode-1)a.bulkSelection.add(ordered.get(i).id);adapter.notifyDataSetChanged();updateSelection();}
    void toggle(Store.Group g){if(!a.bulkSelection.remove(g.id))a.bulkSelection.add(g.id);adapter.notifyDataSetChanged();updateSelection();}
    void updateSelection(){if(edit!=null){edit.setText("Label "+a.bulkSelection.size()+" selected rounds");edit.setEnabled(!a.bulkSelection.isEmpty());}if(a.bulkSelecting)count.setText(a.bulkSelection.size()+" / "+visible.size()+" selected");}
    void picture(Store.Group g,int kind,int reference){if(a.bulkSelecting)toggle(g);else a.showGroupImage(g,kind,reference);}
    boolean beginSelection(Store.Group g){if(a.bulkSelecting)toggle(g);else{remember();a.bulkSelecting=true;a.bulkSelection.add(g.id);a.home();}return true;}
    void column(LinearLayout row,View view,int height){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,height,1);p.setMargins(a.dp(2),0,a.dp(2),0);row.addView(view,p);}
    View card(Store.Group group){
        GroupPresentation s=summaries.get(group.id);PhotoReview review=new PhotoReview(group);
        LinearLayout row=a.row();row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(a.dp(8),a.dp(8),a.dp(10),a.dp(8));row.setBackground(ReviewControls.background(a,a.bulkSelection.contains(group.id)?0xFF385248:MainActivity.PANEL));row.setTag("saved-group:"+group.id);
        String measurementStatus=review.measurementStatus();
        int size=a.dp(104)+Math.round(Math.max(0,a.getResources().getConfiguration().fontScale-1)*a.dp(40)),reference=GroupImages.reference(group);row.setMinimumHeight(size+a.dp(16));
        Set<String> checked=checkedPhotos.get(group.id);int complete=checked==null?0:checked.size();
        String reviewState=s.photos==0?review.status():checked==null?"Checking review...":complete==s.photos?"Dots checked":complete>0?complete+" / "+s.photos+" photos checked":"Needs review";
        Runnable open=()->{if(a.bulkSelecting)toggle(group);else{remember();a.openGroup(group);}};
        View preview=TargetCanvas.mappedThumbnail(a,group,a.shooters.color(group.shooter));
        preview.setTag("group-photo-preview:"+group.id);preview.setContentDescription("2D arrow map. "+s.caption()+". "+review.counts()+". "+reviewState+". Open grouping and photos");preview.setOnClickListener(v->open.run());preview.setOnLongClickListener(v->beginSelection(group));
        FrameLayout picture=new FrameLayout(a);picture.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        if(a.bulkSelecting){CheckBox check=new CheckBox(a);check.setChecked(a.bulkSelection.contains(group.id));check.setTag("bulk-check:"+group.id);check.setContentDescription("Select "+s.caption());check.setOnClickListener(v->toggle(group));picture.addView(check,new FrameLayout.LayoutParams(a.dp(44),a.dp(44),Gravity.TOP|Gravity.LEFT));}
        row.addView(picture,new LinearLayout.LayoutParams(size,size));
        LinearLayout info=new LinearLayout(a);info.setOrientation(1);info.setGravity(Gravity.CENTER_VERTICAL);info.setPadding(a.dp(14),0,0,0);info.setTag("group-details:"+group.id);info.setFocusable(true);info.setClickable(true);info.setOnClickListener(v->open.run());info.setOnLongClickListener(v->beginSelection(group));
        LinearLayout title=a.row();title.setBaselineAligned(true);TextView person=line(a.shooters.label(group),16,a.shooters.color(group.shooter));person.setTypeface(null,1);title.addView(person,new LinearLayout.LayoutParams(-2,-2));
        title.addView(line("  ·  "+group.practice.label(),14,MainActivity.FG),new LinearLayout.LayoutParams(0,-2,1));info.addView(title);
        String photoLabel=s.photos==1?PhotoReviewState.photoLabel(group,group.views.get(reference).id):"";info.addView(line(s.date()+(photoLabel.isEmpty()?"":"  ·  "+photoLabel.replaceFirst("^Photo ","")),12,MainActivity.MUTED));
        // Unmeasured arrows are explained by the amber measurement line below, not repeated here.
        String arrows=review.marked+(review.marked==1?" arrow":" arrows")+(review.expected>review.marked?"  ·  "+review.expected+" shot":"");
        info.addView(line(arrows+(s.photos>1?"  ·  "+s.photos+" photos":""),12,MainActivity.MUTED));
        if(!measurementStatus.isEmpty()){TextView pending=a.text(measurementStatus,11,HomeUi.AMBER);pending.setPadding(0,a.dp(1),0,a.dp(1));pending.setMaxLines(2);info.addView(pending);}
        if(s.photos!=1||a.bulkSelecting){TextView state=line(reviewState,13,complete==s.photos&&s.photos>0?MainActivity.ACCENT:HomeUi.AMBER);state.setTag("review-state:"+group.id);info.addView(state);}
        info.setContentDescription(photoLabel+". "+a.shooters.label(group)+". "+s.date()+". "+reviewState+". "+review.counts()+". "+measurementStatus+". Open grouping");for(int i=0;i<info.getChildCount();i++)info.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if(s.photos==1&&!a.bulkSelecting){String photo=group.views.get(PhotoAssignments.photoIndices(group).get(0)).id;CheckBox check=QuickReviewUi.checkbox(a,group,photo,complete==1,checked!=null,a::home);info.addView(check,new LinearLayout.LayoutParams(-1,-2));}
        row.addView(info,new LinearLayout.LayoutParams(0,-2,1));return row;
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
        count.setVisibility(available||a.bulkSelecting?View.VISIBLE:View.GONE);
    }
    private TextView line(String text,int size,int color){TextView t=a.text(text,size,color);t.setPadding(0,a.dp(1),0,a.dp(1));t.setSingleLine(true);t.setEllipsize(TextUtils.TruncateAt.END);return t;}
    void remember(){if(grid!=null){a.groupGridState=grid.onSaveInstanceState();a.groupGridFilter=a.shooters.filter;}}
    int position(String id){for(int i=0;i<visible.size();i++)if(visible.get(i).id.equals(id))return i;return -1;}
}
