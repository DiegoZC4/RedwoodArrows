package org.redwoodarrows;

import android.app.*;
import android.graphics.Bitmap;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Guided review with debounced local saves and explicit training certification. */
final class PhotoAssignmentUi extends Dialog {
    final MainActivity a;final PhotoAssignments model;final PhotoCanvas photo;final boolean autoSuggest;
    final TextView status,selectionStatus;final Button next,manual,presetButton,unassigned;final ImageButton pick,move,add,remove,revert,selectAll,clearSelection,retryTargets;
    final RadioButton[] steps=new RadioButton[2];final LinearLayout tools,targetStrip;final FrameLayout imageArea;
    final PhotoReviewState choices=new PhotoReviewState();final Set<Integer> selected=choices.selected;final ArrayDeque<String> undo=new ArrayDeque<>();
    final List<Geometry.Calibration> candidates=choices.targets;final List<double[]> outer=new ArrayList<>(),inner=new ArrayList<>();
    final LinearLayout arrowRow,targetChoiceRow;final Spinner targetPicker;final Map<Integer,CheckBox> arrowChecks=new LinkedHashMap<>();
    Geometry.Calibration defaultTarget;Bitmap bitmap;
    String mode="pick",moveSnapshot,shownPreset="",alignmentError="",actionError="",detectionError="";boolean loaded,closed,saving,finding,findingTargets,targetsSearched,bindingSelection,bindingTargets;int generation,step=1;
    final CheckBox reviewed;final TextView saveStatus;final android.os.Handler saveHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    final Runnable autosave=this::autoSave;
    TrainingReviews.Session training;boolean bindingReview,closeAfterSave;int savedRevision,attemptRevision;boolean attemptComplete;String saveError="",trainingError="";
    // Stepping through the Photos list: navigation reuses closeReview, so saves flush and pending previews still prompt.
    final ReviewQueue queue;final int position;final boolean returnToList;Runnable afterClose;

    PhotoAssignmentUi(MainActivity activity,Store.Group group,int index,boolean suggest)throws Exception {
        super(activity,android.R.style.Theme_Material_NoActionBar_Fullscreen);a=activity;model=new PhotoAssignments(group,group.views.get(index).id);
        autoSuggest=suggest||model.reference().detectionState.equals("pending");
        LinearLayout root=new LinearLayout(a);root.setOrientation(1);root.setBackgroundColor(MainActivity.BG);root.setPadding(a.dp(10),a.dp(6),a.dp(10),a.dp(8));
        LinearLayout beforePhoto=new LinearLayout(a),afterPhoto=new LinearLayout(a);beforePhoto.setOrientation(1);afterPhoto.setOrientation(1);
        LinearLayout header=a.row();header.setGravity(Gravity.CENTER_VERTICAL);
        returnToList=a.reviewStepped;a.reviewStepped=false;queue=a.returnToProgress?null:a.reviewQueue;
        Set<String> family=new HashSet<>();for(Store.View v:model.family())family.add(v.id);position=queue==null?-1:queue.indexOf(group.id,family);
        ImageButton back=ReviewControls.icon(a,"x","Close review",this::closeReview);back.setBackgroundColor(android.graphics.Color.TRANSPARENT);back.setTag("assign-back");header.addView(back,new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));
        ImageButton previous=ReviewControls.icon(a,"chevron-right","Previous photo",()->go(-1));previous.setScaleX(-1);previous.setBackgroundColor(android.graphics.Color.TRANSPARENT);previous.setTag("review-previous");
        ImageButton following=ReviewControls.icon(a,"chevron-right","Next photo",()->go(1));following.setBackgroundColor(android.graphics.Color.TRANSPARENT);following.setTag("review-next");
        if(position>=0){LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(a.dp(44),a.dp(44));pp.setMargins(a.dp(4),0,0,0);header.addView(previous,pp);ReviewControls.enabled(previous,position>0);ReviewControls.enabled(following,position<queue.items.size()-1);}
        LinearLayout identity=new LinearLayout(a);identity.setOrientation(1);identity.setGravity(Gravity.CENTER);
        TextView title=a.text(PhotoReviewState.photoLabel(group,model.referenceId),16,MainActivity.FG);title.setTypeface(null,1);title.setGravity(Gravity.CENTER);title.setPadding(0,0,0,0);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);identity.addView(title);
        TextView subtitle=a.text(a.shooters.label(group)+" · "+new GroupPresentation(group).date()+(position>=0?"  ·  "+(position+1)+" of "+queue.items.size():""),11,MainActivity.MUTED);subtitle.setGravity(Gravity.CENTER);subtitle.setPadding(0,0,0,0);subtitle.setSingleLine(true);subtitle.setTag("review-position");identity.addView(subtitle);
        header.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
        if(position>=0){LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(a.dp(44),a.dp(44));fp.setMargins(0,0,a.dp(4),0);header.addView(following,fp);}
        ImageButton more=ReviewControls.icon(a,"ellipsis-vertical","Review options",this::options);more.setBackgroundColor(android.graphics.Color.TRANSPARENT);more.setTag("assign-options");header.addView(more,new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));root.addView(header);
        RadioGroup stages=new RadioGroup(a);stages.setOrientation(LinearLayout.HORIZONTAL);String[] names={"Targets","Dots"};
        for(int i:new int[]{1,0}){
            final int at=i;RadioButton button=new RadioButton(a);steps[i]=button;button.setText(names[i]);button.setTextSize(13);button.setGravity(Gravity.CENTER);button.setButtonDrawable(null);
            button.setPadding(a.dp(4),0,a.dp(4),0);button.setTag("review-step:"+i);button.setOnClickListener(v->goStep(at));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,a.dp(44),1);lp.setMargins(a.dp(2),a.dp(4),a.dp(2),a.dp(4));stages.addView(button,lp);
        }root.addView(stages);
        targetStrip=a.row();targetStrip.setGravity(Gravity.CENTER_VERTICAL);presetButton=a.button("",this::choosePreset);presetButton.setTag("review-preset");presetButton.setTextSize(13);presetButton.setMaxLines(2);presetButton.setBackground(ReviewControls.background(a,MainActivity.PANEL));beforePhoto.addView(targetStrip);
        status=a.text("Loading photo...",12,MainActivity.MUTED);status.setTag("assign-status");status.setMaxLines(3);beforePhoto.addView(status);
        LinearLayout selection=a.row();selection.setGravity(Gravity.CENTER_VERTICAL);selectionStatus=a.text("No arrows selected",12,MainActivity.FG);selectionStatus.setTag("review-selection-status");selectionStatus.setMaxLines(2);selection.addView(selectionStatus,new LinearLayout.LayoutParams(0,-2,1));
        unassigned=a.button("Unassigned",()->{cancelInteraction();choices.selectUnassigned(model);if(step==1)mode="pick";refresh();});unassigned.setTextSize(11);unassigned.setTag("review-select-unassigned");selection.addView(unassigned,new LinearLayout.LayoutParams(a.dp(112),a.dp(44)));
        // Toggles: selects every arrow, or clears the selection when every arrow is already selected. Selection is never saved.
        selectAll=ReviewControls.icon(a,"list-checks","Select all arrows",()->{cancelInteraction();if(allSelected())selected.clear();else choices.selectAll(model);if(step==1)mode="pick";refresh();});selectAll.setTag("review-select-all");selection.addView(selectAll,new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));
        clearSelection=ReviewControls.icon(a,"x","Clear arrow selection",()->{cancelInteraction();selected.clear();if(step==1)mode="pick";refresh();});clearSelection.setTag("review-select-none");selection.addView(clearSelection,new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));beforePhoto.addView(selection);
        HorizontalScrollView arrows=new HorizontalScrollView(a);arrows.setHorizontalScrollBarEnabled(true);arrows.setTag("review-arrow-list");arrowRow=a.row();arrows.addView(arrowRow);beforePhoto.addView(arrows,new LinearLayout.LayoutParams(-1,a.dp(46)));
        targetChoiceRow=a.row();targetChoiceRow.setGravity(Gravity.CENTER_VERTICAL);targetPicker=new Spinner(a);targetPicker.setTag("review-target-picker");targetPicker.setContentDescription("Target preview");targetPicker.setBackgroundTintList(android.content.res.ColorStateList.valueOf(MainActivity.MUTED));targetChoiceRow.addView(targetPicker,new LinearLayout.LayoutParams(0,a.dp(44),1));
        targetPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> parent,View view,int at,long id){if(bindingTargets||saving||choices.preview==at-1)return;actionError="";choices.choose(at-1);refresh();}});
        retryTargets=ReviewControls.icon(a,"rotate-cw","Retry automatic target alignment",this::findTargets);retryTargets.setTag("review-retry-targets");targetChoiceRow.addView(retryTargets,new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));beforePhoto.addView(targetChoiceRow);
        photo=new PhotoCanvas(a,new PhotoCanvas.Listener(){
            public void tap(double x,double y){tapAt(x,y);}public void proposal(double[] line){}
            public void select(int id){if(saving)return;actionError="";if(mode.equals("move")){selected.clear();selected.add(id);}else if(mode.equals("add")){selected.clear();selected.add(id);mode="pick";}else choices.toggle(id,model);refresh();}
            public void beforeMove(){try{moveSnapshot=manualMode()?null:model.snapshot();reviewed.setEnabled(false);}catch(Exception e){error(e);}}public void cancelMove(){moveSnapshot=null;refresh();}
            public void moved(){if(!manualMode()){if(moveSnapshot!=null)remember(moveSnapshot);model.revision++;model.draft.practice.checked="";}moveSnapshot=null;refresh();}
        });photo.setTag("assign-photo");photo.selectedIds=selected;
        imageArea=new FrameLayout(a);imageArea.addView(photo,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout zoom=a.row();zoom.setBackground(ReviewControls.background(a,MainActivity.PANEL));
        String[] resources={"minus","maximize","plus"},zoomNames={"Zoom out","Fit photo","Zoom in"};
        for(int i=0;i<3;i++){final int action=i;ImageButton button=ReviewControls.icon(a,resources[i],zoomNames[i],()->{if(saving)return;if(action==1)photo.fitPhoto();else zoom(action==0?.5f:2f);});button.setTag("assign-zoom:"+i);zoom.addView(button,new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));}
        // Hold to see the bare photo under the markers; release to bring them back. Display only: no mark, mode or selection changes.
        ImageButton peek=ReviewControls.icon(a,"eye","Hold to hide markers",()->{photo.hideMarks=!photo.hideMarks;photo.invalidate();});peek.setTag("assign-peek");
        peek.setOnTouchListener((v,e)->{int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN){v.setPressed(true);photo.hideMarks=true;photo.invalidate();}else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){v.setPressed(false);photo.hideMarks=false;photo.invalidate();}return true;});
        zoom.addView(peek,0,new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));
        FrameLayout.LayoutParams zp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT);zp.setMargins(a.dp(8),a.dp(8),a.dp(8),a.dp(8));imageArea.addView(zoom,zp);
        tools=a.row();pick=tool("mouse-pointer-2","Select arrows without moving dots","assign-mode:pick",()->changeMode("pick"));move=tool("move","Move the selected arrow","assign-mode:move",()->changeMode("move"));add=tool("plus","Add one missing dot","assign-mode:add",()->changeMode("add"));remove=tool("trash-2","Delete selected dots","assign-remove",this::remove);revert=tool("undo-2","Undo last change","assign-undo",this::undo);afterPhoto.addView(tools);
        manual=a.button("Align target manually",this::manual);manual.setTag("assign-manual");manual.setBackground(ReviewControls.background(a,MainActivity.PANEL));afterPhoto.addView(manual);
        reviewed=new CheckBox(a);reviewed.setText("Dots checked");reviewed.setTextSize(14);reviewed.setTextColor(MainActivity.FG);reviewed.setMinHeight(a.dp(44));reviewed.setMaxLines(2);reviewed.setTag("review-training-complete");reviewed.setButtonTintList(android.content.res.ColorStateList.valueOf(MainActivity.ACCENT));reviewed.setTooltipText("Confirms image-space dots for local training, not target alignment");afterPhoto.addView(reviewed,new LinearLayout.LayoutParams(-1,-2));
        reviewed.setOnCheckedChangeListener((button,checked)->{if(bindingReview||training==null)return;try{training.setChecked(model,checked);refresh();}catch(Exception e){error(e);}});
        saveStatus=a.text("",11,MainActivity.MUTED);saveStatus.setMinHeight(a.dp(22));saveStatus.setGravity(Gravity.CENTER);saveStatus.setTag("review-save-status");saveStatus.setOnClickListener(v->{if(!saveError.isEmpty()){saveError="";commit(false);}});afterPhoto.addView(saveStatus);
        next=a.button("Assign target",this::applyTarget);next.setTag("assign-save");next.setMinHeight(a.dp(48));next.setBackground(ReviewControls.background(a,MainActivity.ACCENT));next.setTextColor(MainActivity.BG);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,a.dp(48));np.topMargin=a.dp(6);afterPhoto.addView(next,np);
        if(a.getResources().getConfiguration().screenWidthDp>=640&&a.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE){LinearLayout workspace=a.row();workspace.addView(imageArea,new LinearLayout.LayoutParams(0,-1,1));ScrollView scroll=new ScrollView(a);LinearLayout panel=new LinearLayout(a);panel.setOrientation(1);panel.addView(beforePhoto);panel.addView(afterPhoto);scroll.addView(panel);workspace.addView(scroll,new LinearLayout.LayoutParams(a.dp(300),-1));root.addView(workspace,new LinearLayout.LayoutParams(-1,0,1));}
        else{root.addView(beforePhoto);root.addView(imageArea,new LinearLayout.LayoutParams(-1,0,1));root.addView(afterPhoto);}
        updatePreset();setContentView(root);next.setEnabled(false);
        setOnDismissListener(d->{closed=true;generation++;saveHandler.removeCallbacks(autosave);photo.bitmap=null;if(bitmap!=null){bitmap.recycle();bitmap=null;}if(a.photoAssignment==this)a.photoAssignment=null;});
    }
    ImageButton tool(String icon,String name,String tag,Runnable click){ImageButton button=ReviewControls.icon(a,icon,name,click);button.setTag(tag);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,a.dp(48),1);lp.setMargins(a.dp(3),a.dp(6),a.dp(3),a.dp(6));tools.addView(button,lp);return button;}
    @Override public void show(){super.show();getWindow().setLayout(-1,-1);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        a.worker.execute(()->{Bitmap loaded=null;TrainingReviews.Session review=null;String problem="";try{loaded=a.store.bitmap(model.original,model.reference());review=TrainingReviews.open(a.store,model);}catch(Exception e){problem=e.getMessage();}final Bitmap image=loaded;final TrainingReviews.Session session=review;final String unavailable=problem;a.runOnUiThread(()->{
            if(closed||a.isDestroyed()){if(image!=null)image.recycle();return;}if(image==null){status.setText("Photo unavailable. Saved grouping unchanged.");return;}
            bitmap=image;training=session;trainingError=unavailable;this.loaded=true;photo.photo(image,model.reference());photo.post(()->{if(!closed)photo.frame(points(null));});choices.sync(model);defaultTarget=model.singleConfirmedTarget();refresh();if(autoSuggest&&model.ids().isEmpty())suggest();
        });});
    }
    void updatePreset(){TargetPreset preset=model.draft.preset();presetButton.setText(preset.name);if(!shownPreset.equals(preset.id)){
        targetStrip.removeAllViews();TargetThumbnail preview=new TargetThumbnail(a,preset);preview.setOnClickListener(v->choosePreset());targetStrip.addView(preview,new LinearLayout.LayoutParams(a.dp(48),a.dp(48)));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,a.dp(48),1);lp.leftMargin=a.dp(8);targetStrip.addView(presetButton,lp);shownPreset=preset.id;
    }}
    void refresh(){if(!loaded||closed)return;try{
        choices.sync(model);defaultTarget=model.singleConfirmedTarget();if(mode.equals("move")&&!choices.canMove())mode="pick";
        boolean calibrating=manualMode();int waiting=choices.unassigned(model).size();
        photo.frame=model.overlay();if(step==0&&choices.pending()!=null)photo.frame.calibration=choices.pending();photo.preset=model.draft.preset();photo.mode=mode;photo.outer=outer;photo.inner=inner;photo.targetLabels=step==0?choices.labels(model):Collections.emptyMap();photo.targetOutlines=candidates;photo.previewTarget=choices.preview;photo.selectedIds=selected;photo.readOnly=saving;photo.invalidate();updatePreset();
        photo.setContentDescription(PhotoReviewState.photoLabel(model.draft,model.referenceId)+". "+model.ids().size()+" numbered arrows. Selected arrows: "+(selected.isEmpty()?"none":selected.toString())+". Mode: "+mode+". "+(choices.preview<0?"No target preview.":"Target "+choices.targetName(choices.preview)+" preview; not applied."));
        steps[1].setText("Dots ("+model.ids().size()+")");steps[0].setText("Targets"+(waiting>0?" ("+waiting+")":""));
        for(int i=0;i<steps.length;i++){steps[i].setChecked(i==step);steps[i].setTextColor(i==step?MainActivity.BG:MainActivity.FG);steps[i].setBackground(ReviewControls.background(a,i==step?MainActivity.ACCENT:MainActivity.PANEL));ReviewControls.enabled(steps[i],!saving);}
        targetStrip.setVisibility(step==0?View.VISIBLE:View.GONE);targetChoiceRow.setVisibility(step==0&&!calibrating?View.VISIBLE:View.GONE);tools.setVisibility(step==1||calibrating?View.VISIBLE:View.GONE);
        refreshSelection(calibrating,waiting);refreshTargets(calibrating);
        boolean complete=training!=null&&training.checked(model);bindingReview=true;reviewed.setChecked(complete);bindingReview=false;reviewed.setVisibility(step==1?View.VISIBLE:View.GONE);reviewed.setEnabled(training!=null&&!saving&&!finding&&!photo.editingMark());
        modeStyle(pick,"pick");modeStyle(move,"move");modeStyle(add,"add");
        ReviewControls.enabled(pick,!saving&&step==1);ReviewControls.enabled(move,!saving&&step==1&&choices.canMove());ReviewControls.enabled(add,!saving&&step==1);ReviewControls.enabled(remove,!saving&&step==1&&!selected.isEmpty());ReviewControls.enabled(revert,!saving&&(!undo.isEmpty()||!outer.isEmpty()||!inner.isEmpty()||choices.pending()!=null));
        move.setTooltipText(choices.canMove()?"Move arrow "+selected.iterator().next():"Select one arrow to move");
        manual.setVisibility(calibrating||step==0&&!findingTargets&&(candidates.isEmpty()||!alignmentError.isEmpty())?View.VISIBLE:View.GONE);manual.setText(mode.equals("outer")?"Next boundary":mode.equals("inner")||mode.equals("corners")?"Preview alignment":candidates.isEmpty()?"Align target manually":"Align another target");
        ReviewControls.enabled(manual,!saving);ReviewControls.enabled(next,!saving&&!calibrating&&choices.canAssign(model));ReviewControls.enabled(presetButton,!saving);next.setVisibility(step==0&&!calibrating?View.VISIBLE:View.GONE);
        next.setText(choices.pending()==null||!model.ids().isEmpty()&&selected.isEmpty()?"Assign target":model.ids().isEmpty()?"Use target "+choices.targetName(choices.preview):selected.size()==1?"Assign "+choices.targetName(choices.preview)+" to arrow "+selected.iterator().next():"Assign "+choices.targetName(choices.preview)+" to "+selected.size()+" arrows");
        PhotoReview summary=new PhotoReview(model.draft);
        if(mode.equals("outer"))status.setText("Outer boundary: "+outer.size()+" points (6 minimum)");else if(mode.equals("inner"))status.setText("Inner boundary: "+inner.size()+" points\n"+model.draft.preset().innerHint);else if(mode.equals("corners"))status.setText("Paper corners: "+outer.size()+" / 4");
        else if(!actionError.isEmpty())status.setText(actionError);
        else if(step==0){String alignment=findingTargets?"Auto alignment: searching...":!alignmentError.isEmpty()?(candidates.isEmpty()?alignmentError:"No additional automatic outlines"):candidates.isEmpty()?"Auto alignment: no usable outline":candidates.size()+" target outline"+(candidates.size()==1?"":"s")+" available";
            status.setText(!choices.knownSize(model)?"Target size not set":(waiting>0?waiting+" arrows awaiting your target choice":"All marked arrows have target assignments")+"\n"+alignment);}
        else status.setText(finding?"Auto detection: finding missing dots...":!detectionError.isEmpty()?detectionError:summary.counts()+"  ·  "+(mode.equals("add")?"Adding one dot":mode.equals("move")?"Moving arrow "+selected.iterator().next():complete?"Dots checked":"Dots not checked yet"));
        if(!saveError.isEmpty()&&(model.revision!=attemptRevision||complete!=attemptComplete))saveError="";
        saveStatus.setText(saving?"Saving...":!saveError.isEmpty()?saveError+" (tap to retry)":dirty()?"Unsaved changes":choices.pending()!=null?"Target preview - not applied":training==null?"Training labels unavailable":"Saved on this phone");
        saveStatus.setMaxLines(3);saveStatus.setEnabled(!saving);saveStatus.setContentDescription(training==null?"Training labels unavailable: "+trainingError:saveStatus.getText());
        if(!saving&&dirty()&&saveError.isEmpty())scheduleSave();
    }catch(Exception e){error(e);}}
    boolean manualMode(){return mode.equals("outer")||mode.equals("inner")||mode.equals("corners");}
    void cancelInteraction(){photo.cancelGesture();actionError="";}
    void changeMode(String value){if(saving||step!=1||value.equals("move")&&!choices.canMove())return;cancelInteraction();mode=value;if(value.equals("add"))selected.clear();refresh();}
    void modeStyle(ImageButton button,String value){button.setSelected(mode.equals(value));button.setBackground(ReviewControls.background(a,mode.equals(value)?MainActivity.ACCENT:MainActivity.PANEL));button.setColorFilter(mode.equals(value)?MainActivity.BG:MainActivity.FG);}
    boolean allSelected(){return !model.ids().isEmpty()&&selected.containsAll(model.ids());}
    void refreshSelection(boolean calibrating,int waiting){
        selectionStatus.setText(selected.isEmpty()?"No arrows selected":selected.size()==model.ids().size()?"All "+selected.size()+" selected":selected.size()+" selected");
        // Lit like an active mode while everything is selected, so it reads as "tap again to deselect".
        boolean all=allSelected();selectAll.setBackground(ReviewControls.background(a,all?MainActivity.ACCENT:MainActivity.PANEL));selectAll.setColorFilter(all?MainActivity.BG:MainActivity.FG);selectAll.setContentDescription(all?"Deselect all arrows":"Select all arrows");selectAll.setTooltipText(all?"Deselect all arrows":"Select all arrows");
        unassigned.setText("Unassigned ("+waiting+")");unassigned.setVisibility(step==0?View.VISIBLE:View.GONE);unassigned.setContentDescription("Select "+waiting+" arrows without an assigned target");
        ReviewControls.enabled(unassigned,!saving&&!calibrating&&waiting>0);ReviewControls.enabled(selectAll,!saving&&!calibrating&&!model.ids().isEmpty());ReviewControls.enabled(clearSelection,!saving&&!calibrating&&!selected.isEmpty());
        if(!arrowChecks.keySet().equals(model.ids())){arrowRow.removeAllViews();arrowChecks.clear();for(int id:model.ids()){
            CheckBox check=new CheckBox(a);check.setText(""+id);check.setTextSize(12);check.setTag("review-arrow:"+id);check.setMinHeight(a.dp(36));ReviewControls.chip(a,check,PhotoCanvas.SELECTION,0xFF07110F);
            check.setOnCheckedChangeListener((view,on)->{if(bindingSelection||saving)return;cancelInteraction();if(on)selected.add(id);else selected.remove(id);if(step==1)mode="pick";refresh();});arrowChecks.put(id,check);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(a.dp(48),a.dp(36));cp.setMargins(0,a.dp(4),a.dp(6),a.dp(4));arrowRow.addView(check,cp);
        }}
        Map<Integer,String> labels=choices.labels(model);bindingSelection=true;for(Map.Entry<Integer,CheckBox> entry:arrowChecks.entrySet()){int id=entry.getKey();CheckBox check=entry.getValue();check.setChecked(selected.contains(id));check.setTypeface(null,selected.contains(id)?1:0);check.setContentDescription("Arrow "+id+(selected.contains(id)?", selected":", not selected")+", target "+labels.get(id));check.setEnabled(!saving&&!calibrating);}bindingSelection=false;
        // Picking several arrows by number matters for assigning targets; on Dots, tapping the markers is enough.
        ((View)arrowRow.getParent()).setVisibility(step==0?View.VISIBLE:View.GONE);
    }
    void refreshTargets(boolean calibrating){
        List<String> labels=new ArrayList<>();labels.add(candidates.isEmpty()?"No target outline":"Choose target preview");for(int i=0;i<candidates.size();i++)labels.add("Target "+choices.targetName(i)+" · "+(choices.saved(model,i)?"saved":"suggested"));
        boolean changed=targetPicker.getAdapter()==null||targetPicker.getAdapter().getCount()!=labels.size();if(!changed)for(int i=0;i<labels.size();i++)changed|=!labels.get(i).equals(targetPicker.getAdapter().getItem(i));
        bindingTargets=true;if(changed){ArrayAdapter<String> adapter=new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,labels){public View getView(int at,View recycled,ViewGroup parent){TextView view=(TextView)super.getView(at,recycled,parent);view.setTextColor(MainActivity.FG);view.setTextSize(13);view.setSingleLine();view.setEllipsize(TextUtils.TruncateAt.END);return view;}public View getDropDownView(int at,View recycled,ViewGroup parent){TextView view=(TextView)super.getDropDownView(at,recycled,parent);view.setTextColor(MainActivity.FG);view.setBackgroundColor(MainActivity.PANEL);view.setMinHeight(a.dp(48));return view;}};targetPicker.setAdapter(adapter);}targetPicker.setSelection(choices.preview+1);bindingTargets=false;
        ReviewControls.enabled(targetPicker,!saving&&!calibrating&&!candidates.isEmpty());ReviewControls.enabled(retryTargets,!saving&&!findingTargets&&!calibrating);
    }
    void switchStep(int at){cancelInteraction();step=at;mode=step==0?"target":"pick";outer.clear();inner.clear();refresh();if(step==0&&!targetsSearched&&!findingTargets)findTargets();}
    void goStep(int at){if(!loaded||saving||at<0||at>=steps.length)return;if(manualMode()&&(!outer.isEmpty()||!inner.isEmpty()))new AlertDialog.Builder(a).setTitle("Discard alignment marks?").setNegativeButton("Keep editing",null).setPositiveButton("Discard",(d,w)->switchStep(at)).show();else switchStep(at);}
    void applyTarget(){if(saving||!choices.canAssign(model))return;try{cancelInteraction();String before=model.snapshot();choices.apply(model);remember(before);defaultTarget=model.singleConfirmedTarget();mode="target";refresh();}catch(Exception e){error(e);}}
    boolean dirty(){return model.revision!=savedRevision||training!=null&&training.dirty(model);}
    void autoSave(){if(closed||!loaded||saving)return;if(photo.editingMark()){scheduleSave();return;}commit(false);}
    void scheduleSave(){saveHandler.removeCallbacks(autosave);saveHandler.postDelayed(autosave,600);}
    void flush(){saveHandler.removeCallbacks(autosave);if(loaded&&!closed&&!saving&&!photo.editingMark()&&dirty())commit(false);}
    void remember(String value){undo.addLast(value);while(undo.size()>25)undo.removeFirst();}
    void snapshot(){try{remember(model.snapshot());}catch(Exception e){error(e);}}
    void remove(){if(saving||selected.isEmpty())return;Set<Integer> removing=new TreeSet<>(selected);Runnable apply=()->{cancelInteraction();snapshot();model.remove(removing);selected.clear();mode="pick";refresh();};if(removing.size()>1)new AlertDialog.Builder(a).setTitle("Delete "+removing.size()+" selected dots?").setMessage("Arrows "+removing.toString()).setNegativeButton("Cancel",null).setPositiveButton("Delete dots",(d,w)->apply.run()).show();else apply.run();}
    void undo(){if(saving)return;try{actionError="";
        if(mode.equals("outer")||mode.equals("corners")){if(!outer.isEmpty())outer.remove(outer.size()-1);}else if(mode.equals("inner")){if(!inner.isEmpty())inner.remove(inner.size()-1);}
        else if(choices.pending()!=null)choices.choose(-1);
        else if(!undo.isEmpty()){String preset=model.draft.preset().id;ShooterRoster.Person person=model.draft.shooter;Practice practice=model.draft.practice;boolean confirmed=CaptureDefaults.confirmed(model.draft);model.restore(undo.removeLast());if(confirmed)Store.confirmShooter(model.draft,person);else model.draft.shooter=person;model.draft.practice=practice;model.draft.practice.checked="";selected.retainAll(model.ids());if(!preset.equals(model.draft.preset().id)){choices.resetTargets();targetsSearched=false;}choices.sync(model);defaultTarget=model.singleConfirmedTarget();}refresh();
    }catch(Exception e){error(e);}}
    void tapAt(double x,double y){if(!loaded||saving)return;try{
        actionError="";
        if(mode.equals("add")){snapshot();int id=model.add(x,y);if(defaultTarget!=null&&choices.pending()==null)model.assign(new TreeSet<>(Arrays.asList(id)),defaultTarget);selected.clear();selected.add(id);mode="pick";refresh();}
        else if(mode.equals("target")){int chosen=TargetSelection.nearest(candidates,x,y);if(chosen<0){status.setText("No target outline at that position");return;}choices.choose(chosen);refresh();}
        else if(mode.equals("outer")){outer.add(new double[]{x,y});refresh();}else if(mode.equals("inner")){inner.add(new double[]{x,y});refresh();}else if(mode.equals("corners")&&outer.size()<4){outer.add(new double[]{x,y});refresh();}
    }catch(Exception e){error(e);}}
    void findTargets(){if(!loaded||closed||saving||findingTargets)return;final int request=++generation;final String family=model.draft.preset().family;findingTargets=true;targetsSearched=true;alignmentError="";actionError="";refresh();a.worker.execute(()->{
        List<Geometry.Calibration> found=new ArrayList<>();Bitmap image=null;String error="";try{if(!family.equals("Field")&&!family.equals("Hunter"))error="Auto alignment unavailable for this target";else{image=a.store.bitmap(model.original,model.reference());if(image==null)error="Auto alignment: photo unavailable";else for(Vision.Rings rings:Vision.ringCandidates(image,family))found.add(rings.calibration);}}catch(Exception failed){error="Auto alignment could not fit this photo";}finally{if(image!=null)image.recycle();}
        final String failure=error;a.runOnUiThread(()->{if(closed||request!=generation)return;findingTargets=false;alignmentError=failure;for(Geometry.Calibration candidate:found){double[] center=Geometry.project(Geometry.inverse(candidate.h),0,0);if(TargetSelection.nearest(candidates,center[0],center[1])<0)choices.addTarget(candidate);}refresh();});
    });}
    void suggest(){if(!loaded||closed||finding||saving)return;finding=true;detectionError="";actionError="";final int revision=model.revision;refresh();a.worker.execute(()->{
        if(closed||a.isDestroyed())return;Bitmap image=null;List<double[]> points=new ArrayList<>();String error="";try{image=a.store.bitmap(model.original,model.reference());if(image==null)throw new IllegalArgumentException("Photo missing");if(a.impact==null)a.impact=ImpactVision.fromAssets(a);points=a.impact.detect(image).points;}catch(Exception e){error="Arrow detection unavailable. Manual review is still available.";}finally{if(image!=null)image.recycle();}
        final List<double[]> hits=points;final String problem=error;final String modelHash=a.impact==null?"":a.impact.modelSha256;final double threshold=a.impact==null?Double.NaN:a.impact.threshold;a.runOnUiThread(()->finishSuggestions(revision,hits,problem,modelHash,threshold));
    });}
    void finishSuggestions(int revision,List<double[]> hits,String problem,String modelHash,double threshold){finding=false;if(closed||saving||a.isDestroyed())return;refresh();if(!problem.isEmpty()){detectionError="Auto detection could not find dots. Existing dots kept.";refresh();return;}if(model.revision!=revision||photo.editingMark()){detectionError="Existing edits kept; late suggestions ignored";refresh();return;}
        snapshot();try{if(training!=null)training.detectorBaseline(hits,modelHash,threshold);for(double[] hit:hits){boolean near=false;for(Store.View view:model.family())for(Store.Observation mark:view.observations)if(!mark.line&&PhotoDetection.sameEntry(mark.a,hit,model.reference().width))near=true;if(near)continue;
            int id=model.add(hit[0],hit[1]);for(Store.View view:model.family())for(Store.Observation mark:view.observations)if(mark.id==id){mark.source="impact-model:photo-suggestion";mark.confidence=hit[2];mark.associationWarning="Suggested entry; check the dot and assign its intended bullseye.";}if(defaultTarget!=null&&choices.pending()==null)model.assign(new TreeSet<>(Arrays.asList(id)),defaultTarget);
        }refresh();}catch(Exception e){error(e);}
    }
    void manual(){if(saving)return;try{actionError="";
        if(mode.equals("outer")){if(outer.size()<6){status.setText("At least 6 outer-boundary points required");return;}mode="inner";refresh();}
        else if(mode.equals("inner")||mode.equals("corners")){TargetPreset preset=model.draft.preset();Geometry.Calibration fit=mode.equals("corners")?Geometry.calibratePaper(outer,preset.paperWidthMm,preset.paperHeightMm,model.reference().width,model.reference().height):Geometry.calibrate(outer,inner,preset.innerRatio,model.reference().width,model.reference().height);fit.reviewed=false;choices.choose(choices.addTarget(fit));alignmentError="";mode="target";outer.clear();inner.clear();refresh();}
        else{cancelInteraction();step=0;choices.choose(-1);outer.clear();inner.clear();mode=model.draft.preset().circular()?"outer":"corners";refresh();}
    }catch(Exception e){error(e);}}
    void choosePreset(){if(!loaded||saving)return;LinearLayout body=new LinearLayout(a);body.setOrientation(1);body.setPadding(a.dp(16),0,a.dp(16),0);EditText query=new EditText(a);query.setSingleLine(true);query.setHint("Search target name or size");query.setContentDescription("Search target presets");body.addView(query);
        List<TargetPreset> choices=new ArrayList<>(TargetCatalog.all());ListView list=new ListView(a);body.addView(list,new LinearLayout.LayoutParams(-1,a.dp(320)));ArrayAdapter<String> adapter=new ArrayAdapter<>(a,android.R.layout.simple_list_item_1,new ArrayList<>());list.setAdapter(adapter);
        Runnable filter=()->{choices.clear();choices.addAll(TargetCatalog.search(query.getText().toString()));adapter.clear();for(TargetPreset preset:choices)adapter.add(preset.name);adapter.notifyDataSetChanged();};filter.run();query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){filter.run();}public void afterTextChanged(Editable e){}});
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Target preset").setView(body).setNegativeButton("Cancel",null).create();list.setOnItemClickListener((parent,view,position,id)->{TargetPreset preset=choices.get(position);dialog.dismiss();if(preset.id.equals(model.draft.preset().id))return;
            new AlertDialog.Builder(a).setTitle("Use "+preset.name+"?").setMessage("Entry dots and photos stay intact. Target alignment for this grouping's photos will need to be confirmed again.").setPositiveButton("Change target",(d,w)->{snapshot();model.changePreset(preset);this.choices.resetTargets();defaultTarget=null;outer.clear();inner.clear();step=0;mode="target";targetsSearched=false;alignmentError="";refresh();findTargets();}).setNegativeButton("Cancel",null).show();
        });dialog.show();
    }
    void options(){if(!loaded||saving)return;new AlertDialog.Builder(a).setTitle("Review options").setItems(new String[]{"Find missing dots","Retry target alignment","Align target manually","Target type & size","Undo last change"},(d,which)->{
        if(which==0)suggest();else if(which==1){switchStep(0);if(!findingTargets)findTargets();}else if(which==2)manual();else if(which==3)choosePreset();else undo();
    }).setNegativeButton("Close",null).show();}
    void commit(boolean close){closeAfterSave|=close;if(!loaded||closed||saving)return;saveHandler.removeCallbacks(autosave);if(!dirty()){if(closeAfterSave)finishClose();return;}if(photo.editingMark()){scheduleSave();return;}
        saving=true;a.busy=true;attemptRevision=model.revision;attemptComplete=training!=null&&training.checked(model);setCancelable(false);refresh();a.worker.execute(()->{
        Store.Group saved=null;String error="";try{saved=training==null?PhotoReview.save(model,a.store):training.save(a.store,model);}catch(Exception e){error=e.getMessage()==null?"Could not save changes":e.getMessage();}final Store.Group result=saved;final String problem=error;
        a.runOnUiThread(()->{a.busy=false;saving=false;if(result!=null){savedRevision=attemptRevision;a.snapshot();a.group=result;a.analysis=null;a.groupImages.thumbs.evictAll();SyncRuntime.saved(a);}if(closed||a.isDestroyed())return;setCancelable(true);if(result==null){closeAfterSave=false;saveError=problem;refresh();return;}saveError="";if(closeAfterSave)finishClose();else refresh();});
    });}
    void error(Exception e){actionError=e.getMessage()==null?"Review could not be completed":e.getMessage();status.setText(actionError);}
    void finishClose(){dismiss();Runnable then=afterClose;afterClose=null;if(then!=null)then.run();else if(returnToList){a.scrollToGroup=model.draft.id;a.groupGridState=null;a.home();}else a.results();}
    void closeReview(){closeReview(null);}
    /** then runs after any pending save finishes; null returns to the list (when stepping) or the grouping. */
    void closeReview(Runnable then){if(!loaded){afterClose=then;finishClose();return;}if(choices.pending()!=null||manualMode()&&(!outer.isEmpty()||!inner.isEmpty())){new AlertDialog.Builder(a).setTitle("Discard unapplied target preview?").setNegativeButton("Keep editing",null).setPositiveButton("Discard preview",(d,w)->{choices.choose(-1);outer.clear();inner.clear();afterClose=then;commit(true);}).show();return;}afterClose=then;commit(true);}
    void go(int delta){if(saving||queue==null||position<0)return;int at=position+delta;if(at<0||at>=queue.items.size())return;ReviewQueue.Item item=queue.items.get(at);closeReview(()->{a.reviewStepped=true;a.assignTargets(item.group,item.view);a.reviewStepped=false;});}
    List<double[]> points(Set<Integer> only){List<double[]> result=new ArrayList<>();for(Store.Observation o:model.reference().observations)if(only==null||only.contains(o.id)){result.add(o.a);if(o.line&&o.b!=null)result.add(o.b);}return result;}
    /** Zoom buttons keep the selected arrows centered; with nothing selected they zoom around the middle as before. */
    void zoom(float factor){List<double[]> focus=points(selected);if(focus.isEmpty()){photo.zoomBy(factor);return;}double x=0,y=0;for(double[] q:focus){x+=q[0];y+=q[1];}photo.zoomAround(factor,x/focus.size(),y/focus.size());}
    void closeWithActivity(){flush();closed=true;saveHandler.removeCallbacks(autosave);super.dismiss();}
    @Override public void onBackPressed(){if(!saving)closeReview();}
    @Override public void dismiss(){if(!saving){closed=true;super.dismiss();}}
}
