package org.redwoodarrows;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public final class MainActivity extends Activity {
    static final int BG=0xFF101C19,PANEL=0xFF21352E,FG=0xFFF1F4E7,MUTED=0xFFB5C4B7,ACCENT=0xFFD8EB98;
    Store store;Store.Group group;HybridVision hybrid;ImpactVision impact;SweepAnalysis analysis;int index=0,arrow=1;String mode="hit",exportKind="csv";
    TargetPreset selected=TargetCatalog.defaultPreset();
    TargetGallery gallery;
    ShootersUi shooters;
    Store.ShooterEdit shooterUndo;
    ProgressUi progress;boolean returnToProgress;
    TextView syncStatus;long gallerySyncedGeneration;ForegroundSync foregroundSync;AppUpdates updates;Button updateButton;PhotoImport photoImport;boolean photoOnly;
    final Runnable updateRefresh=this::refreshUpdate;
    GroupImages groupImages;GroupImageViewer imageViewer;TargetPicker targetPicker;PhotoAssignmentUi photoAssignment;
    GroupingGallery groupingGallery;android.os.Parcelable groupGridState;String groupGridFilter="";boolean catalogForCapture;
    LinearLayout root,controls;TextView heading,hint,detail;Button phaseButton;PhotoCanvas canvas;Bitmap bitmap;SweepView sweep;
    final ExecutorService worker=Executors.newSingleThreadExecutor();final ArrayDeque<String> undo=new ArrayDeque<>();boolean busy=false;String page="home";long lastCameraError;
    volatile long sweepGeneration;Runnable sweepCleanup;String sweepNotice="";
    interface Job {void run()throws Exception;}
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);store=new Store(this);shooters=new ShootersUi(this);groupImages=new GroupImages(this);photoImport=new PhotoImport(this);TargetPreset remembered=TargetCatalog.find(getPreferences(0).getString("target_preset",TargetCatalog.DEFAULT_ID));if(remembered!=null)selected=remembered;home();photoImport.restore(state);if(debugQaAllowed()&&getIntent().getBooleanExtra("qa",false))runQa();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);photoImport.save(state);}
    private boolean debugQaAllowed(){return (getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0;}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(debugQaAllowed()&&intent.getBooleanExtra("qa",false))runQa();}
    @Override protected void onPause(){if(foregroundSync!=null)foregroundSync.stop();if(updates!=null)updates.stop(updateRefresh);super.onPause();if(sweep!=null){closeCamera();analysis=null;if(group!=null&&!group.views.isEmpty()){if(photoOnly)results();else editor();}else home();}}
    @Override protected void onResume(){super.onResume();if(updates==null)updates=AppUpdates.get(this);updates.start(updateRefresh);SyncRuntime.uiChanged=()->runOnUiThread(this::refreshSync);if(new SyncSession(this).present())SyncJobs.schedule(this);if(foregroundSync==null)foregroundSync=new ForegroundSync(this);foregroundSync.start();}
    @Override protected void onDestroy(){if(updates!=null)updates.stop(updateRefresh);SyncRuntime.uiChanged=null;Store.editingGroupId=null;if(imageViewer!=null)imageViewer.dismiss();if(targetPicker!=null)targetPicker.closeWithActivity();if(photoAssignment!=null)photoAssignment.closeWithActivity();closeCamera();worker.shutdown();super.onDestroy();}
    void refreshUpdate(){if(isDestroyed()||!page.equals("home")||updateButton==null)return;ReleaseUpdate.Release release=updates==null?null:updates.available();updateButton.setVisibility(release==null?View.GONE:View.VISIBLE);if(release!=null){String label="Update to "+release.version()+" · "+Math.round(release.bytes/1_000_000.)+" MB. Opens GitHub; install over this app to keep your sign-in.";updateButton.setContentDescription(label);updateButton.setTooltipText(label);}}
    void refreshSync(){if(isDestroyed())return;if(syncStatus!=null){syncStatus.setText(SyncRuntime.status+"  ·  ☁");syncStatus.setContentDescription("Shared library. "+SyncRuntime.status+". Tap to manage sign-in and sync.");}if(!busy&&!SyncRuntime.running&&page.equals("home")&&gallerySyncedGeneration!=SyncRuntime.libraryGeneration.get()){shooters=new ShootersUi(this);groupImages.thumbs.evictAll();rememberGroups();home();}}
    int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    TextView text(String s,float size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(5));return t;}
    GradientDrawable rounded(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(14));return d;}
    Button button(String label,Runnable click){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(FG);b.setBackground(rounded(PANEL));b.setMinHeight(dp(44));b.setMinimumHeight(dp(44));b.setPadding(dp(10),dp(8),dp(10),dp(8));b.setOnClickListener(v->{if(!busy)click.run();});return b;}
    void add(LinearLayout layout,android.view.View view){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(5),0,dp(5));layout.addView(view,p);}
    LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);return r;}
    void cell(LinearLayout row,android.view.View child){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(2),dp(3),dp(2),dp(3));row.addView(child,p);}
    LinearLayout shell(String name){root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);root.setPadding(dp(16),dp(6),dp(16),dp(10));setContentView(root);heading=text(name,25,FG);heading.setTypeface(null,1);add(root,heading);return root;}
    void home(){boolean wasEditing=Store.editingGroupId!=null;Store.editingGroupId=null;page="home";returnToProgress=false;gallery=null;canvas=null;if(bitmap!=null){bitmap.recycle();bitmap=null;}shell("Groupings");
        if(wasEditing&&new SyncSession(this).present())SyncRuntime.request(this,null);
        try{shooterUndo=store.lastShooterEdit();}catch(Exception e){shooterUndo=null;android.util.Log.w("RedwoodArrows","Shooter undo unavailable; journal preserved",e);}
        LinearLayout header=row();header.setGravity(Gravity.CENTER_VERTICAL);root.removeView(heading);header.addView(heading,new LinearLayout.LayoutParams(0,dp(48),1));Button menu=button("More",this::homeMenu);menu.setTag("home-menu");header.addView(menu,new LinearLayout.LayoutParams(dp(64),dp(44)));add(root,header);
        Button trends=button("Progress",this::showProgress);trends.setTag("show-progress");header.addView(trends,1,new LinearLayout.LayoutParams(dp(84),dp(44)));
        LinearLayout statusRow=row();statusRow.setGravity(Gravity.CENTER_VERTICAL);updateButton=button("Update",()->{ReleaseUpdate.Release release=updates==null?null:updates.available();if(release!=null)openUpdateUrl(release.url);});updateButton.setTag("app-update");updateButton.setBackground(rounded(ACCENT));updateButton.setTextColor(BG);statusRow.addView(updateButton,new LinearLayout.LayoutParams(dp(88),dp(44)));updateButton.setVisibility(View.GONE);
        syncStatus=text(SyncRuntime.status+"  ·  ☁",12,MUTED);syncStatus.setTag("shared-library");syncStatus.setMinHeight(dp(36));syncStatus.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);syncStatus.setOnClickListener(v->new SyncUi(this).show());syncStatus.setContentDescription("Shared library. "+SyncRuntime.status+". Tap to manage sign-in and sync.");statusRow.addView(syncStatus,new LinearLayout.LayoutParams(0,-2,1));add(root,statusRow);refreshUpdate();
        gallerySyncedGeneration=SyncRuntime.libraryGeneration.get();groupingGallery=new GroupingGallery(this,store.list());groupingGallery.show();
        QuickCapture.show(this);
    }
    void quickCapture(){if(busy||!page.equals("home"))return;photoOnly=false;rememberGroups();if(newGroup()){page="capture-start";camera();}}
    Store.Group draftGroup()throws Exception {Store.Group next=Store.Group.start(selected,shooters.active());next.keepPhotos=keepTestPhotos();next.practice=PracticeUi.next(this);return next;}
    void takePhoto(boolean existing){if(busy)return;try{if(!existing){rememberGroups();group=draftGroup();group.keepPhotos=true;Store.editingGroupId=group.id;index=0;undo.clear();}else if(group==null||group.coordinatesOnly()||!group.keepPhotos){message("Start a new photo grouping to keep and sync photos; this end uses 2D-only storage.");return;}if(group.views.size()>=64){message("This grouping already has 64 views.");return;}photoOnly=true;analysis=null;page="capture-start";camera();}catch(Exception e){message("Could not prepare this photo: "+e.getMessage());}}
    void addPhotos(boolean existing){if(existing&&group!=null&&!group.keepPhotos){message("This grouping uses 2D-only storage. Start a new photo grouping to keep and sync the photos.");return;}photoImport.start(existing);}
    void showProgress(){boolean wasEditing=Store.editingGroupId!=null;Store.editingGroupId=null;if(wasEditing&&new SyncSession(this).present())SyncRuntime.request(this,null);if(progress==null)progress=new ProgressUi(this);progress.show();}
    void backFromGroup(){if(returnToProgress)showProgress();else home();}
    void undoShooter(){if(busy||shooterUndo==null)return;try{Store.Group restored=store.undoShooterEdit(shooterUndo);if(group!=null&&group.id.equals(restored.id)){group=restored;undo.clear();analysis=null;}rememberGroups();shooterUndo=null;home();}catch(Exception e){message(e.getMessage());}}
    void rememberGroups(){if(page.equals("home")&&groupingGallery!=null)groupingGallery.remember();}
    void showGroupImage(Store.Group saved,int kind,int view){if(busy)return;rememberGroups();if(imageViewer!=null)imageViewer.dismiss();imageViewer=new GroupImageViewer(this,saved,kind,view);imageViewer.show();}
    void chooseTarget(Store.Group saved,int photo){if(busy||saved.views.isEmpty())return;openGroup(saved);if(group==null||!group.id.equals(saved.id)||!group.id.equals(Store.editingGroupId))return;String id=saved.views.get(Math.max(0,Math.min(photo,saved.views.size()-1))).id;int at=-1;for(int i=0;i<group.views.size();i++)if(group.views.get(i).id.equals(id))at=i;if(at<0){message("Photo changed; reopen the grouping");return;}if(targetPicker!=null)targetPicker.dismiss();targetPicker=new TargetPicker(this,group,at);targetPicker.show();}
    void assignTargets(Store.Group saved,int photo){assignTargets(saved,photo,false);}
    void assignTargets(Store.Group saved,int photo,boolean autoSuggest){if(busy||saved.views.isEmpty())return;try{String id=saved.views.get(Math.max(0,Math.min(photo,saved.views.size()-1))).id;openGroup(saved);if(group==null||!group.id.equals(saved.id)||!group.id.equals(Store.editingGroupId))return;int at=-1;for(int i=0;i<group.views.size();i++)if(group.views.get(i).id.equals(id))at=i;if(at<0)throw new IllegalArgumentException("Photo changed; reopen the grouping");if(photoAssignment!=null)photoAssignment.dismiss();photoAssignment=new PhotoAssignmentUi(this,group,at,autoSuggest);photoAssignment.show();}catch(Exception e){message("Could not open target assignment: "+e.getMessage());}}
    void homeMenu(){rememberGroups();new AlertDialog.Builder(this).setTitle("Redwood Arrows").setItems(new String[]{"Target presets","People","Storage","Shared library","Help","About","Download app update","New grouping options"},(d,which)->{if(which==0)showCatalog();else if(which==1)shooters.chooseActive();else if(which==2)storageSettings();else if(which==3)new SyncUi(this).show();else if(which==4)help();else if(which==5)about();else if(which==6)openUpdates();else captureSetup();}).setNegativeButton("Close",null).show();}
    void openUpdates(){openUpdateUrl(ReleaseUpdate.REPOSITORY+"/releases/latest/download/RedwoodArrows.apk");}
    void openUpdateUrl(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));Toast.makeText(this,"Install over this app. Do not uninstall; your sign-in stays saved.",Toast.LENGTH_LONG).show();}catch(ActivityNotFoundException e){message("Open github.com/DiegoZC4/RedwoodArrows/releases/latest in your browser to download the update.");}}
    void captureSetup(){rememberGroups();page="capture-setup";canvas=null;shell("New grouping");add(root,button("Back to gallery",this::home));
        ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(1);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        shooters.addHomeControls(body);add(body,text("TARGET",12,MUTED));LinearLayout preset=row();preset.setGravity(Gravity.CENTER_VERTICAL);TargetThumbnail thumb=new TargetThumbnail(this,selected);thumb.setOnClickListener(v->showCatalog());preset.addView(thumb,new LinearLayout.LayoutParams(dp(84),dp(88)));Button target=button(selected.name+"\nChange target",this::showCatalog);preset.addView(target,new LinearLayout.LayoutParams(0,-2,1));add(body,preset);
        Button range=button(PracticeUi.nextLabel(this)+" · setup",()->PracticeUi.nextDialog(this));range.setTag("setup-distance");add(body,range);
        Button capture=button("Take photo",()->takePhoto(false));capture.setBackground(rounded(ACCENT));capture.setTextColor(BG);add(body,capture);add(body,button("Add photos",()->addPhotos(false)));add(body,button("Optional camera sweep",()->{photoOnly=false;if(newGroup())camera();}));
        Button counts=button("Log an end without photos",()->{if(newGroup()){results();PracticeUi.groupDialog(this,group);}});counts.setTag("log-counts-only");add(body,counts);
    }
    void showCatalog(){rememberGroups();catalogForCapture=page.equals("capture-setup");gallery=new TargetGallery(this);gallery.open();}
    void closeCatalog(){if(catalogForCapture){catalogForCapture=false;captureSetup();}else home();}
    void selectPreset(TargetPreset p){selected=p;getPreferences(0).edit().putString("target_preset",p.id).apply();closeCatalog();}
    boolean keepTestPhotos(){return getPreferences(0).getBoolean("keep_test_photos",true);}
    void storageSettings(){
        LinearLayout body=new LinearLayout(this);body.setOrientation(1);body.setBackgroundColor(BG);body.setPadding(dp(20),dp(8),dp(20),dp(8));
        TextView explanation=text("",14,MUTED);
        Runnable describe=()->explanation.setText(keepTestPhotos()?"Testing mode: keep every view with its grouping so we can inspect and improve the pipeline.":"For new groupings: use photos while analyzing, then keep only the accepted 2D positions, target, shooter, date and warnings. Unresolved drafts keep their photos. Photo editing is unavailable after acceptance.");
        TwoShooterSwitch control=new TwoShooterSwitch(this,"Keep photos","2D only",keepTestPhotos()?0:1,"New grouping storage:",choice->{
            if(!getPreferences(0).edit().putBoolean("keep_test_photos",choice==0).commit()){message("Could not save the storage setting");return false;}describe.run();return true;
        });control.setTag("storage-switch");add(body,control);add(body,explanation);describe.run();
        add(body,text("Only future groupings use this setting. Today's test images and all older groupings are unchanged. Camera-roll originals are never removed.",13,MUTED));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Grouping storage").setView(body).setPositiveButton("Done",null).create();dialog.setOnDismissListener(d->{if(page.equals("home"))home();});dialog.show();
    }
    boolean newGroup(){try{Store.Group next=Store.Group.start(selected,shooters.active());next.keepPhotos=keepTestPhotos();next.practice=PracticeUi.next(this);store.save(next);analysis=null;sweepNotice="";group=next;Store.editingGroupId=next.id;index=0;arrow=1;undo.clear();return true;}catch(Exception e){message("Could not start a grouping on this phone: "+e.getMessage());return false;}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==PhotoImport.REQUEST){photoImport.result(result,data);return;}if(result!=RESULT_OK||data==null)return;
        if(request==11){final Uri uri=data.getData();final Store.Group target;try{target=Store.exportSnapshot(group,shooters.roster);}catch(Exception e){message("Reopen the grouping and export again. Its saved data is unchanged.");return;}final String kind=exportKind;work("Exporting your measurements…",()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Could not open export destination");if(kind.equals("zip"))store.writeArchive(target,out);else out.write(Store.csv(target).getBytes(StandardCharsets.UTF_8));}},()->message("Export saved with the shooter. The grouping remains in the app."));}
    }
    void camera(){
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},20);return;}
        final long generation=++sweepGeneration;final SweepAnalysis.Incremental[] live={null};analysis=null;sweepNotice="";
        sweepCleanup=()->worker.execute(()->{if(live[0]!=null){live[0].close();live[0]=null;}});
        try{page="camera";sweep=new SweepView(this,store,group,photoOnly,new SweepView.Listener(){
            public void captured(Store.Group snapshot){
                if(generation!=sweepGeneration||sweep==null)return;
                if(photoOnly){closeCamera();analysis=null;index=group.views.size()-1;results();SyncRuntime.saved(MainActivity.this);assignTargets(group,index,true);Toast.makeText(MainActivity.this,"Photo saved · finding arrow dots",Toast.LENGTH_SHORT).show();return;}
                worker.execute(()->{
                    if(generation!=sweepGeneration)return;
                    try{
                        if(impact==null)impact=ImpactVision.fromAssets(MainActivity.this);
                        if(live[0]==null)live[0]=new SweepAnalysis.Incremental(store,impact);
                        SweepAnalysis.Update update=live[0].update(snapshot);
                        runOnUiThread(()->{if(generation==sweepGeneration&&sweep!=null&&!isDestroyed()){analysis=update.analysis;sweep.analyzed(update);}});
                    }catch(Exception e){
                        if(live[0]!=null){live[0].close();live[0]=null;}
                        android.util.Log.e("RedwoodArrows","Incremental sweep analysis failed",e);
                        runOnUiThread(()->{if(generation==sweepGeneration&&sweep!=null){analysis=null;sweep.analysisFailed(e.getMessage()==null?e.toString():e.getMessage());}});
                    }
                });
            }
            public void finished(){
                if(generation!=sweepGeneration||sweep==null)return;sweepNotice=sweep.completionNotice();closeCamera();
                if(group.views.isEmpty())home();else if(analysis!=null)sweepPreview(0);else{index=0;editor();message(sweepNotice.isEmpty()?"Photos saved. The positions are not yet reliable; use the editor to check calibration.":sweepNotice);}
            }
            public void error(String s){if(generation==sweepGeneration&&System.currentTimeMillis()-lastCameraError>3000){lastCameraError=System.currentTimeMillis();Toast.makeText(MainActivity.this,s,Toast.LENGTH_LONG).show();}}
        });setContentView(sweep);sweep.resume();}
        catch(Exception e){android.util.Log.e("RedwoodArrows","Camera startup failed",e);closeCamera();home();message("Camera unavailable: "+e.getMessage()+". You can still import photos and use multi-view alignment.");}
    }
    void closeCamera(){++sweepGeneration;SweepView current=sweep;sweep=null;if(current!=null)current.close();Runnable cleanup=sweepCleanup;sweepCleanup=null;if(cleanup!=null)cleanup.run();}
    @Override public void onRequestPermissionsResult(int r,String[] permissions,int[] grants){super.onRequestPermissionsResult(r,permissions,grants);if(r==20){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)camera();else{home();message("Camera access was not granted. Add photos still works without camera permission.");}}}
    Store.View view(){return group.views.get(index);}
    void openGroup(Store.Group saved){rememberGroups();synchronized(Store.FILE_LOCK){Store.editingGroupId=saved.id;try{group=store.read(new File(store.directory(saved),"group.json"));}catch(Exception e){Store.editingGroupId=null;message("Reopen the gallery; this grouping could not be loaded.");return;}}index=0;undo.clear();if(group.coordinatesOnly())try{group=store.retryPhotoCleanup(group);}catch(Exception e){android.util.Log.w("RedwoodArrows","Photo cleanup kept for retry",e);}results();}
    void editor(){if(group!=null&&group.coordinatesOnly()){results();return;}if(group==null||group.views.isEmpty()){home();return;}page="editor";index=Math.min(index,group.views.size()-1);Store.View v=view();if(bitmap!=null)bitmap.recycle();bitmap=store.bitmap(group,v);if(bitmap==null){message("The saved photo is missing");home();return;}
        shell(group.targetLabel());shooterHeading(group.targetLabel());
        LinearLayout nav=row();cell(nav,button("Groups",this::home));cell(nav,button("‹ View "+(index+1)+" / "+group.views.size()+" ›",()->{index=(index+1)%group.views.size();mode="hit";editor();}));cell(nav,button("Results",this::results));add(root,nav);
        canvas=new PhotoCanvas(this,new PhotoCanvas.Listener(){public void tap(double x,double y){onPhotoTap(x,y);}public void proposal(double[] l){snapshot();addObservation(new Store.Observation(arrow,new double[]{l[0],l[1]},new double[]{l[2],l[3]}));canvas.proposals.remove(l);hint.setText("Arrow "+arrow+" shaft added. Drag both markers onto its centerline, then use the same ID in the next view.");}public void beforeMove(){snapshot();}public void moved(){save();}});
        canvas.photo(bitmap,v);canvas.preset=group.preset();canvas.outer=v.rimMarks;canvas.inner=v.innerMarks;canvas.mode=mode;canvas.arrow=arrow;root.addView(canvas,new LinearLayout.LayoutParams(-1,0,1));
        hint=text("",13,ACCENT);hint.setMaxLines(3);add(root,hint);
        controls=new LinearLayout(this);controls.setOrientation(1);root.addView(controls);
        LinearLayout modes=row();cell(modes,button(group.preset().circular()?"Rings":"Paper corners",this::manualRings));cell(modes,button("Mark hit",()->setMode("hit")));cell(modes,button("Mark shaft",()->setMode("shaft")));add(controls,modes);
        LinearLayout tools=row();cell(tools,button("Choose target",()->chooseTarget(group,index)));cell(tools,button("Find arrows",this::autoShafts));cell(tools,button("Align view",this::align));add(controls,tools);
        LinearLayout arrows=row();cell(arrows,button("−",()->{arrow=Math.max(1,arrow-1);updateEditor();}));cell(arrows,button("Arrow "+arrow,()->arrowPicker()));cell(arrows,button("+",()->{arrow++;updateEditor();}));cell(arrows,button("Undo",this::undo));add(controls,arrows);
        phaseButton=button("",this::advance);phaseButton.setBackground(rounded(ACCENT));phaseButton.setTextColor(BG);add(controls,phaseButton);
        detail=text("",11,MUTED);detail.setMaxLines(2);add(root,detail);updateEditor();
    }
    void updateEditor(){if(canvas==null)return;canvas.arrow=arrow;canvas.mode=mode;Store.View v=view();canvas.outer=v.rimMarks;canvas.inner=v.innerMarks;
        LinearLayout arrows=(LinearLayout)controls.getChildAt(2);((Button)arrows.getChildAt(1)).setText("Arrow "+arrow);
        if(mode.equals("corners")){String[] names={"top LEFT","top RIGHT","bottom RIGHT","bottom LEFT"};hint.setText(v.rimMarks.size()<4?"Tap the "+names[v.rimMarks.size()]+" corner of the UNTRIMMED paper. "+group.preset().dimensions():"Four corners marked. Drag to refine; ensure they follow TL → TR → BR → BL.");phaseButton.setText("Fit paper plane ("+v.rimMarks.size()+" / 4 corners)");}
        else if(mode.equals("outer")){hint.setText("Outer rim of ONE "+TargetPreset.number(group.preset().diameterMm/10)+" cm circle. First mark defines up; add 8–12 points around all sides. "+v.rimMarks.size()+" marked.");phaseButton.setText("Next: inner boundary");}
        else if(mode.equals("inner")){hint.setText(group.preset().innerHint+" At least 6 points around all sides.");phaseButton.setText("Fit both circles ("+v.innerMarks.size()+" inner marks)");}
        else {hint.setText(mode.equals("shaft")?"Arrow "+arrow+": tap two points along its SHAFT CENTERLINE, far apart. Use the same arrow ID in another view.":"Arrow "+arrow+": tap where the shaft ENTERS the paper. Pinch to zoom; drag markers to refine. Not the nock or an old hole.");phaseButton.setText(v.calibration==null?(index==0?"Calibrate this reference photo":"Align to the reference photo"):v.calibration.reviewed?"Alignment reviewed ✓":"Confirm the calibration overlay");}
        detail.setText((v.calibration==null?"No metric calibration yet · marks are saved as photo pixels":(v.calibration.reviewed?"Reviewed · ":"Needs review · ")+v.calibration.source+(Double.isFinite(v.calibration.rms)?String.format(Locale.US," · ring-fit RMS %.1f mm (not accuracy)",v.calibration.rms*group.radiusMm()):""))+(v.timestamp>0?(v.pose==null?" · photo-only capture":" · motion recorded"):""));canvas.invalidate();
    }
    void setMode(String m){mode=m;canvas.pending=null;updateEditor();}
    void manualRings(){if(index!=0){message("Calibrate the first photo, then Align view here. Independent fits do not share a reliable coordinate frame.");return;}boolean circular=group.preset().circular();AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle(circular?"Mark the target rings":"Mark the paper corners").setMessage((circular?"Mark clean points on the outer circular rim, then the selected inner boundary.":"Use the flat, untrimmed sheet at its published size: "+group.preset().dimensions()+". Mark TL, TR, BR, BL in that order. Do not use a cut-out animal or trimmed sheet.")+" Refitting invalidates later alignments; arrow marks stay saved. Undo can restore calibration.").setPositiveButton("Start over",(d,w)->{snapshot();view().rimMarks.clear();view().innerMarks.clear();invalidateCalibration();mode=circular?"outer":"corners";save();updateEditor();}).setNegativeButton("Cancel",null);if(!view().rimMarks.isEmpty())dialog.setNeutralButton("Continue marks",(d,w)->{mode=circular?(view().rimMarks.size()>=6?"inner":"outer"):"corners";updateEditor();});dialog.show();}
    void invalidateCalibration(){for(Store.View v:group.views)v.calibration=null;}
    void advance(){Store.View v=view();if(mode.equals("outer")){if(v.rimMarks.size()<6){message("Add at least six well-spaced outer-rim points");return;}mode="inner";updateEditor();}
        else if(mode.equals("inner")||mode.equals("corners")){try{snapshot();TargetPreset p=group.preset();Geometry.Calibration c=mode.equals("corners")?Geometry.calibratePaper(v.rimMarks,p.paperWidthMm,p.paperHeightMm,v.width,v.height):Geometry.calibrate(v.rimMarks,v.innerMarks,p.innerRatio,v.width,v.height);invalidateCalibration();v.calibration=c;mode="hit";save();updateEditor();}catch(Exception e){message(e.getMessage());}}
        else if(v.calibration==null){if(index==0)manualRings();else align();}else if(!v.calibration.reviewed){snapshot();v.calibration.reviewed=true;save();updateEditor();}else message("The overlay is marked reviewed. Check that all boundaries agree with the target, especially away from the center. Use Rings or Paper corners to redo calibration.");}
    void onPhotoTap(double x,double y){Store.View v=view();if(mode.equals("corners")&&v.rimMarks.size()>=4){message("Four corners are already marked. Drag to refine, or Undo to replace the last one.");return;}snapshot();if(mode.equals("outer")||mode.equals("corners"))v.rimMarks.add(new double[]{x,y});else if(mode.equals("inner"))v.innerMarks.add(new double[]{x,y});else if(mode.equals("shaft")){if(canvas.pending==null){canvas.pending=new double[]{x,y};hint.setText("Now tap a second point farther along this same shaft’s centerline.");return;}double[] a=canvas.pending;if(Math.hypot(a[0]-x,a[1]-y)<20){message("Choose shaft points farther apart for a stable line");return;}addObservation(new Store.Observation(arrow,a,new double[]{x,y}));canvas.pending=null;}else addObservation(new Store.Observation(arrow,new double[]{x,y},null));save();updateEditor();}
    void addObservation(Store.Observation o){Iterator<Store.Observation> it=view().observations.iterator();while(it.hasNext()){Store.Observation existing=it.next();if(existing.id==o.id&&existing.line==o.line)it.remove();}view().observations.add(o);save();canvas.invalidate();}
    void arrowPicker(){EditText input=new EditText(this);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setText(""+arrow);new AlertDialog.Builder(this).setTitle("Physical arrow ID").setMessage("Keep the same ID for the same arrow across views. A marked impact takes precedence over shaft-only triangulation.").setView(input).setPositiveButton("Use",(d,w)->{try{arrow=Math.max(1,Math.min(999,Integer.parseInt(input.getText().toString())));updateEditor();}catch(Exception ignored){message("Enter an arrow number");}}).setNeutralButton("Remove ID here",(d,w)->{snapshot();view().observations.removeIf(o->o.id==arrow);save();updateEditor();}).setNegativeButton("Cancel",null).show();}
    void autoRings(){if(!group.preset().family.equals("Field")){message(group.preset().circular()?"Automatic ring finding is currently tested only on NFAA Field faces. This preset uses its own correct ring geometry: tap Rings to calibrate.":"This target uses the untrimmed paper, not circular rings. Tap Paper corners to calibrate.");return;}if(index!=0){message("Find rings on the first view, then align other views to it");return;}final Bitmap b=bitmap;final Vision.Rings[] found=new Vision.Rings[1];work("Finding the two circular boundaries…",()->found[0]=Vision.rings(b,"Field"),()->{snapshot();invalidateCalibration();Store.View v=view();v.calibration=found[0].calibration;v.rimMarks.clear();v.innerMarks.clear();canvas.outer=new ArrayList<>();canvas.inner=new ArrayList<>();mode="hit";save();updateEditor();hint.setText("Automatic proposal: inspect ALL ring overlays before confirming. Old holes and torn paper can distort the fit.");});}
    void autoShafts(){new AlertDialog.Builder(this).setTitle("Find arrows · experimental").setItems(new String[]{"Refine entire sweep · entry centers", "Legacy hybrid shaft helper", "OpenCV shaft edges only"},(dialog,which)->{if(which==0)analyzeSweep();else findShafts(which==1);}).setNegativeButton("Cancel",null).show();}
    void analyzeSweep(){
        sweepNotice="";
        final Store.Group source=group;final SweepAnalysis[] found=new SweepAnalysis[1];
        work("Finding entries across views, aligning the paper, and refining the grouping…",()->{
            if(impact==null)impact=ImpactVision.fromAssets(this);
            found[0]=SweepAnalysis.run(store,source,impact);
        },()->{analysis=found[0];sweepPreview(0);});
    }
    void sweepPreview(int photoIndex){
        if(analysis==null){editor();return;}page="sweep-preview";canvas=null;
        if(bitmap!=null)bitmap.recycle();Store.View v=analysis.preview.views.get(photoIndex);bitmap=store.bitmap(analysis.preview,v);
        shell(analysis.readiness.ready?"Positions stable":"Suggested grouping");heading.setTextSize(21);
        if(!sweepNotice.isEmpty())add(root,text(sweepNotice,13,ACCENT));
        add(root,text(shooters.label(group)+" · not saved · check the entry dots and ring alignment",13,ACCENT));
        LinearLayout nav=row();cell(nav,button("‹",()->sweepPreview((photoIndex+analysis.preview.views.size()-1)%analysis.preview.views.size())));
        cell(nav,text("View "+(photoIndex+1)+" / "+analysis.preview.views.size(),14,FG));
        cell(nav,button("›",()->sweepPreview((photoIndex+1)%analysis.preview.views.size())));add(root,nav);
        PhotoCanvas evidence=new PhotoCanvas(this,new PhotoCanvas.Listener(){public void tap(double x,double y){}public void proposal(double[] p){}public void beforeMove(){}public void moved(){}});
        evidence.photo(bitmap,v);evidence.preset=group.preset();evidence.readOnly=true;evidence.unconfirmed=true;evidence.setContentDescription("Suggested entry centers on the original photo. Amber rings are not yet confirmed. Pinch to inspect; two fingers pan.");root.addView(evidence,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(new TargetCanvas(this,analysis.preview),new LinearLayout.LayoutParams(-1,dp(185)));
        int supported=0;for(Store.Result result:Store.results(analysis.preview))if(result.views>1&&result.warning.isEmpty())supported++;
        add(root,text(supported+" cross-view consistent · "+(analysis.fusion.tracks.size()-supported)+" need checking (hollow)\n"+analysis.fusion.views+" / "+analysis.preview.views.size()+" aligned views. "+(analysis.fusion.ambiguousObservations>0?analysis.fusion.ambiguousObservations+" detections left unassociated. ":"")+"Pinch to inspect. Hidden entries are not invented.",12,MUTED));
        LinearLayout actions=row();cell(actions,button("Back",()->{analysis=null;editor();}));
        cell(actions,button("Details",()->{StringBuilder s=new StringBuilder();for(Store.Result result:Store.results(analysis.preview))s.append("Arrow ").append(result.id).append(": ").append(result.views).append(" views").append(result.warning.isEmpty()?"":" · "+result.warning).append("\n");for(String warning:analysis.warnings)s.append("\n").append(warning);s.append("\nDetector scores and cross-view agreement are not physical accuracy guarantees. All suggestions remain editable after acceptance.");message(s.toString());}));
        cell(actions,button("Use reviewed estimates",this::acceptSweep));add(root,actions);
    }
    void acceptSweep(){
        if(analysis==null)return;
        for(Store.View v:group.views)for(Store.Observation o:v.observations)if(!o.source.startsWith("impact-model:")){
            message("Your manual marks are preserved. This model comparison has not replaced them. Start a new group to save an independent automatic result.");return;
        }
        new AlertDialog.Builder(this).setTitle("Use this reviewed grouping?").setMessage("Confirm that the dots are arrow entry centers and the ring overlay matches the same target in every included view. Single-view and ambiguous estimates keep their warnings. "+(group.keepPhotos?"All views stay together in this grouping. You can edit marks afterward; Undo restores the previous state.":"2D-only mode: after saving the positions, this grouping's app photo copies are removed. Camera-roll originals stay untouched. Unresolved drafts keep their photos."))
            .setPositiveButton("Use estimates",(dialog,which)->{try{snapshot();Store.Group accepted=analysis.preview;store.save(accepted);group=accepted;analysis=null;index=0;if(!group.keepPhotos)commitCoordinates();else results();}catch(Exception e){message("Could not save estimates: "+e.getMessage());}}).setNegativeButton("Keep reviewing",null).show();
    }
    void commitCoordinates(){
        try{group=store.finishCoordinates(group);undo.clear();if(bitmap!=null){bitmap.recycle();bitmap=null;}results();if(!group.pendingPhotoCleanup.isEmpty())message("The 2D positions are safely saved. Some temporary photos could not be removed; reopening this grouping will retry.");}
        catch(Exception e){results();message(e.getMessage());}
    }
    void confirmCoordinates(){new AlertDialog.Builder(this).setTitle("Save this 2D grouping?").setMessage("Keep the positions, target, shooter and date. Remove only this grouping's app photo copies after verifying the saved coordinates. You will no longer be able to edit its photos. Camera-roll originals stay untouched.").setPositiveButton("Save 2D grouping",(d,w)->commitCoordinates()).setNegativeButton("Keep reviewing",null).show();}
    void findShafts(boolean learned){final Bitmap b=bitmap;final Geometry.Calibration c=view().calibration;final List<double[]>[] found=new List[1];work(learned?"Running the small local recognizer + shaft geometry…":"Looking for pairs of long shaft edges…",()->{if(learned){if(hybrid==null)hybrid=HybridVision.fromAssets(this);found[0]=hybrid.shafts(b);}else found[0]=Vision.shafts(b,c);},()->{canvas.proposals=found[0];mode="shaft";updateEditor();hint.setText((learned?"Experimental hybrid: ":"")+found[0].size()+" unconfirmed lines. Misses and false proposals remain. Choose an arrow ID, tap its orange line, then refine both endpoints.");});}
    void align(){if(index==0){message("This is the reference photo. Calibrate it first.");return;}Store.View reference=group.views.get(0);if(reference.calibration==null||!reference.calibration.reviewed){message("Review and confirm the first photo’s calibration first");return;}Store.View current=view();final Geometry.Calibration[] found=new Geometry.Calibration[1];final Bitmap b=bitmap;work("Aligning target-paper texture across views…",()->{Bitmap ref=store.bitmap(group,reference);try{double[] prior=null;try{prior=Vision.motionPrior(group,reference,current);}catch(IllegalArgumentException ignored){}found[0]=Vision.align(ref,reference.calibration,b,prior,group.preset());}finally{ref.recycle();}},()->{snapshot();current.calibration=found[0];save();updateEditor();hint.setText("Alignment proposed. Confirm that the overlay sits on the SAME spot or sheet before marking this view’s arrows.");});}
    void snapshot(){try{undo.addLast(group.json().toString());while(undo.size()>25)undo.removeFirst();}catch(Exception e){message(e.getMessage());}}
    void undo(){if(canvas!=null&&canvas.pending!=null){canvas.pending=null;updateEditor();return;}if(undo.isEmpty()){message("Nothing to undo in this session");return;}try{String digest=group.diskDigest;group=Store.Group.from(new JSONObject(undo.removeLast()));group.diskDigest=digest;save();editor();}catch(Exception e){message(e.getMessage());}}
    void save(){if(group!=null)try{store.save(group);}catch(Exception e){message("Could not save: "+e.getMessage());}}
    void assignShooter(ShooterRoster.Person person){
        if(group==null||busy||group.shooter.id.equals(person.id)&&!new GroupPresentation(group).provisionalOwner())return;
        try{Store.Group changed=store.assignShooter(group,person);snapshot();Store.confirmShooter(group,changed.shooter);group.diskDigest=changed.diskDigest;analysis=null;groupImages.thumbs.evictAll();refreshShooterLabels();}
        catch(Exception e){message("Could not save this grouping's shooter: "+e.getMessage());}
    }
    void shooterHeading(String title){
        String label="Shooter: "+shooters.label(group)+" · change";
        android.text.SpannableString value=new android.text.SpannableString(title+"\n"+label);
        value.setSpan(new android.text.style.AbsoluteSizeSpan(13,true),title.length()+1,value.length(),0);
        value.setSpan(new android.text.style.ForegroundColorSpan(ACCENT),title.length()+1,value.length(),0);
        heading.setText(value);heading.setTextSize(19);heading.setMinimumHeight(dp(48));heading.setTag("group-shooter");
        heading.setContentDescription(title+". "+label+" shooter");heading.setOnClickListener(v->{if(!busy)shooters.chooseForGroup();});
    }
    void refreshShooterLabels(){if(page.equals("home"))home();else if(page.equals("capture-setup"))captureSetup();else if(page.equals("results"))results();else if(page.equals("progress"))showProgress();else if(page.equals("editor"))shooterHeading(group.targetLabel());}
    void results(){page="results";canvas=null;if(bitmap!=null){bitmap.recycle();bitmap=null;}new GroupDetails(this).show();}
    void rename(){EditText in=new EditText(this);in.setText(group.name);new AlertDialog.Builder(this).setTitle("Group name").setView(in).setPositiveButton("Save",(d,w)->{group.name=in.getText().toString().trim();save();results();}).setNegativeButton("Cancel",null).show();}
    void export(String kind){exportKind=kind;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(kind.equals("zip")?"application/zip":"text/csv");String person=shooters.label(group).replaceAll("[^\\p{L}\\p{N}._-]+","-");i.putExtra(Intent.EXTRA_TITLE,"redwood-"+person+"-"+group.id.substring(0,Math.min(8,group.id.length()))+"."+kind);startActivityForResult(i,11);}
    void work(String status,Job job,Runnable success){if(busy)return;busy=true;ProgressDialog progress=new ProgressDialog(this);progress.setMessage(status);progress.setCancelable(false);progress.show();worker.execute(()->{Exception failure=null;try{job.run();}catch(Exception e){failure=e;}final Exception error=failure;runOnUiThread(()->{busy=false;if(isFinishing()||isDestroyed())return;progress.dismiss();if(error!=null)message(error.getMessage()==null?error.toString():error.getMessage());else success.run();});});}
    void message(String s){if(!isFinishing())new AlertDialog.Builder(this).setMessage(s==null?"Unknown error":s).setPositiveButton("OK",null).show();}
    void help(){message("Take photo saves immediately, without waiting for detection or motion tracking. Arrow dots are then suggested automatically, on the phone. The photo stays saved even if you close the annotation screen.\n\nArrows → targets: Correct the suggested dots; use Add for missed entry centers and Remove for false positives. Select dots (All selects every arrow in that photo), choose Target, then tap the bullseye those arrows were aimed at. Repeat for other subsets if you switched targets during the round. Mark rings or paper corners if no automatic outline matches. Drag dots in Add mode to refine, and Undo to reverse edits. Save updates the 2D positions and syncs when connected. Retry dots is optional; reopening saved corrections never reruns detection automatically.\n\nA target assignment never chooses the nearest bullseye for you. Marks in another photo are retained but excluded for the reassigned arrows until that view's target is checked. Optional Sweep remains available for occlusion and multiple angles. Detector scores and ring-fit residuals are not measurement accuracy. Use the correct printed target preset and size; scaled or wrinkled paper can introduce error.\n\nCapture, marking and target assignment work offline. Photos and estimates remain editable in testing mode.");}
    void about(){message("Built with OpenCV 4.12, Apache Commons Math, Apache-licensed Google ARCore camera samples, and target-plane geometry adapted from htkseason/Archery-Scoring. License notices and model provenance are bundled.\n\nThe experimental entry-center network adapts torchvision MobileNetV3 using local visual labels, synthetic renders, and Haozhe Ma’s public FITA point dataset. The detector can miss arrows or mistake old holes for entries; review your plots. Detector scores and cross-view agreement are not physical measurement uncertainty.\n\nARCore provides camera-and-inertial tracking, not accelerometer-only position. Its runtime is proprietary Google software. No Cloud Anchors, advertising or geospatial services are used.\n\nCapture and analysis work offline. Optional sign-in syncs saved groupings and retained photos over HTTPS to your private library on diegozc.com. Everyone invited to that library can view and edit them. The hosting administrator can access stored data; this is not end-to-end encryption. Signing out retains local copies.\n\nThe beta includes training-data attribution under CC BY-NC-SA 4.0. Review upstream model/data terms before public or commercial distribution. Do not share signing keys, invitation codes or private photos publicly.");}
    @Override public void onBackPressed(){if(busy)return;if(sweep!=null){closeCamera();analysis=null;if(group.views.isEmpty())home();else if(photoOnly)results();else editor();}else if(page.equals("sweep-preview")){analysis=null;editor();}else if(page.equals("editor"))results();else if(page.equals("results"))backFromGroup();else if(page.startsWith("gallery")&&gallery!=null)gallery.back();else if(!page.equals("home"))home();else super.onBackPressed();}
    void runQa(){work("Checking native vision against private archery photos…",()->{Qa.run(this,store);},()->{List<Store.Group> list=store.list();for(Store.Group g:list)if(g.name.equals("Oct 7 multi-view trial")){group=g;index=0;editor();return;}home();});}
}
