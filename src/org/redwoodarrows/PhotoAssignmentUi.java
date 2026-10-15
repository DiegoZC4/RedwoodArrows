package org.redwoodarrows;

import android.app.*;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Capture first; target assignment never waits for a neural detection. */
final class PhotoAssignmentUi extends Dialog {
    final MainActivity a;final PhotoAssignments model;final PhotoCanvas photo;
    final TextView status;final Button save,suggest,manual;final Button[] modes=new Button[3];
    final Set<Integer> selected=new TreeSet<>();final ArrayDeque<String> undo=new ArrayDeque<>();
    final List<Geometry.Calibration> candidates=new ArrayList<>();
    final List<double[]> outer=new ArrayList<>(),inner=new ArrayList<>();
    Bitmap bitmap;String mode="select";boolean loaded,closed,saving,finding;int generation;
    PhotoAssignmentUi(MainActivity activity,Store.Group group,int index)throws Exception {
        super(activity,android.R.style.Theme_Material_NoActionBar_Fullscreen);a=activity;model=new PhotoAssignments(group,group.views.get(index).id);
        LinearLayout root=new LinearLayout(a);root.setOrientation(1);root.setBackgroundColor(MainActivity.BG);root.setPadding(a.dp(10),a.dp(6),a.dp(10),a.dp(8));
        LinearLayout header=a.row();Button back=a.button("Back",this::onBackPressed);back.setTag("assign-back");header.addView(back,new LinearLayout.LayoutParams(a.dp(64),a.dp(44)));
        TextView title=a.text("Arrows → targets",20,MainActivity.FG);title.setTypeface(null,1);title.setGravity(Gravity.CENTER);header.addView(title,new LinearLayout.LayoutParams(0,a.dp(48),1));
        save=a.button("Save",this::commit);save.setTag("assign-save");save.setBackground(a.rounded(MainActivity.ACCENT));save.setTextColor(MainActivity.BG);save.setEnabled(false);header.addView(save,new LinearLayout.LayoutParams(a.dp(64),a.dp(44)));root.addView(header);
        status=a.text("Loading saved photo…",13,MainActivity.MUTED);status.setTag("assign-status");status.setMinHeight(a.dp(40));status.setMaxLines(2);root.addView(status);
        photo=new PhotoCanvas(a,new PhotoCanvas.Listener(){
            public void tap(double x,double y){tapAt(x,y);}public void proposal(double[] line){}
            public void select(int id){if(saving)return;if(!selected.add(id))selected.remove(id);refresh();}
            public void beforeMove(){snapshot();}public void moved(){model.revision++;model.draft.practice.checked="";refresh();}
        });photo.setTag("assign-photo");photo.selectedIds=selected;photo.setContentDescription("Photo editor. Select arrow dots, then Target to assign their bullseye. Add marks missed entries; drag a mark to refine. Pinch to zoom, drag empty space to pan.");root.addView(photo,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=a.row();String[] labels={"Select","Add","Target"},values={"select","add","target"};
        for(int i=0;i<3;i++){final String value=values[i];modes[i]=a.button(labels[i],()->{mode=value;refresh();});modes[i].setTag("assign-mode:"+value);a.cell(actions,modes[i]);}root.addView(actions);
        LinearLayout selection=a.row();Button all=a.button("All",()->{selected.addAll(model.ids());mode=selected.isEmpty()?"add":"target";refresh();});all.setTag("assign-all");a.cell(selection,all);
        Button none=a.button("None",()->{selected.clear();mode="select";refresh();});none.setTag("assign-none");a.cell(selection,none);
        Button remove=a.button("Remove",()->{if(saving||selected.isEmpty())return;snapshot();model.remove(selected);selected.clear();refresh();});remove.setTag("assign-remove");a.cell(selection,remove);
        Button revert=a.button("Undo",this::undo);revert.setTag("assign-undo");a.cell(selection,revert);root.addView(selection);
        LinearLayout helpers=a.row();suggest=a.button("Suggest dots",this::suggest);suggest.setTag("assign-suggest");a.cell(helpers,suggest);
        manual=a.button("Mark rings",this::manual);manual.setTag("assign-manual");a.cell(helpers,manual);root.addView(helpers);
        TextView preset=a.text(group.preset().name+" · "+group.preset().dimensions(),11,MainActivity.MUTED);preset.setMaxLines(1);root.addView(preset);
        setContentView(root);setOnDismissListener(d->{closed=true;generation++;photo.bitmap=null;if(bitmap!=null){bitmap.recycle();bitmap=null;}if(a.photoAssignment==this)a.photoAssignment=null;});
    }
    @Override public void show(){super.show();getWindow().setLayout(-1,-1);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        a.worker.execute(()->{Bitmap loaded=null;try{loaded=a.store.bitmap(model.original,model.reference());if(loaded==null)throw new IllegalArgumentException("Photo unavailable");}catch(Exception ignored){}
            final Bitmap image=loaded;a.runOnUiThread(()->{if(closed||a.isDestroyed()){if(image!=null)image.recycle();return;}if(image==null){status.setText("Saved photo unavailable. Your grouping is unchanged.");return;}
                bitmap=image;this.loaded=true;photo.photo(image,model.reference());selected.addAll(model.ids());mode=selected.isEmpty()?"add":"select";candidates.addAll(model.targets());refresh();findTargets();});});
    }
    void refresh(){if(!loaded||closed)return;try{
        // Ring letters and arrow labels must agree after assigning a candidate
        // that appeared later in the detector's ranking, and after Undo.
        List<Geometry.Calibration> ordered=new ArrayList<>(model.targets());
        for(Geometry.Calibration candidate:candidates){boolean known=false;for(Geometry.Calibration existing:ordered)known|=PhotoAssignments.same(existing,candidate);if(!known)ordered.add(candidate);}
        candidates.clear();candidates.addAll(ordered);
        photo.frame=model.overlay();photo.preset=model.draft.preset();photo.mode=mode;photo.outer=outer;photo.inner=inner;photo.targetLabels=model.labels();photo.targetOutlines=candidates;photo.selectedIds=selected;photo.readOnly=saving;photo.invalidate();
        for(int i=0;i<3;i++){boolean active=mode.equals(new String[]{"select","add","target"}[i]);modes[i].setBackground(a.rounded(active?MainActivity.ACCENT:MainActivity.PANEL));modes[i].setTextColor(active?MainActivity.BG:MainActivity.FG);modes[i].setSelected(active);modes[i].setEnabled(!saving&&(i!=2||!selected.isEmpty()));}
        save.setEnabled(!saving);suggest.setEnabled(!saving&&!finding);manual.setEnabled(!saving&&(!selected.isEmpty()||mode.equals("outer")||mode.equals("inner")||mode.equals("corners")));
        manual.setText(mode.equals("outer")?"Next: inner ring":mode.equals("inner")?"Fit & assign":mode.equals("corners")?"Fit & assign":model.draft.preset().circular()?"Mark rings":"Mark corners");
        if(mode.equals("outer"))status.setText(outer.size()+" outer marks · first defines up · add 8+ around the rim");
        else if(mode.equals("inner"))status.setText(inner.size()+" inner marks · "+model.draft.preset().innerHint);
        else if(mode.equals("corners"))status.setText(outer.size()+" / 4 paper corners · top left → top right → bottom right → bottom left");
        else if(mode.equals("target"))status.setText(selected.size()+" selected · tap their intended bullseye");
        else if(mode.equals("add"))status.setText("Tap shaft-entry centers · drag dots to refine · then Target");
        else status.setText(selected.size()+" / "+model.ids().size()+" selected · tap dots, or All → target");
    }catch(Exception e){status.setText("Could not display marks: "+e.getMessage());}}
    void snapshot(){try{undo.addLast(model.snapshot());while(undo.size()>25)undo.removeFirst();}catch(Exception e){status.setText(e.getMessage());}}
    void undo(){if(saving)return;try{if(mode.equals("outer")||mode.equals("corners")){if(!outer.isEmpty())outer.remove(outer.size()-1);}else if(mode.equals("inner")){if(!inner.isEmpty())inner.remove(inner.size()-1);}else if(!undo.isEmpty()){model.restore(undo.removeLast());selected.retainAll(model.ids());}refresh();}catch(Exception e){status.setText(e.getMessage());}}
    void tapAt(double x,double y){if(!loaded||saving)return;try{
        if(mode.equals("add")){snapshot();selected.add(model.add(x,y));refresh();}
        else if(mode.equals("target")){int chosen=TargetSelection.nearest(candidates,x,y);if(chosen<0){status.setText("No outline here. Use Mark rings, then assign the selected arrows.");return;}assign(candidates.get(chosen));}
        else if(mode.equals("outer")) {outer.add(new double[]{x,y});refresh();}
        else if(mode.equals("inner")) {inner.add(new double[]{x,y});refresh();}
        else if(mode.equals("corners")&&outer.size()<4){outer.add(new double[]{x,y});refresh();}
    }catch(Exception e){status.setText(e.getMessage());}}
    void assign(Geometry.Calibration fit)throws Exception {snapshot();int count=selected.size();model.assign(new TreeSet<>(selected),fit);mode="select";refresh();status.setText(count+" assigned · select another subset, or Save");}
    void findTargets(){final int request=++generation;a.worker.execute(()->{List<Geometry.Calibration> found=new ArrayList<>();Bitmap image=null;try{image=a.store.bitmap(model.original,model.reference());if(image!=null&&(model.draft.type.equals("Field")||model.draft.type.equals("Hunter")))for(Vision.Rings r:Vision.ringCandidates(image,model.draft.type))found.add(r.calibration);}catch(Exception ignored){}finally{if(image!=null)image.recycle();}
        a.runOnUiThread(()->{if(closed||request!=generation)return;for(Geometry.Calibration candidate:found){double[] center=Geometry.project(Geometry.inverse(candidate.h),0,0);if(TargetSelection.nearest(candidates,center[0],center[1])<0)candidates.add(candidate);}photo.invalidate();});});}
    void suggest(){if(!loaded||finding||saving)return;finding=true;final int revision=model.revision;refresh();status.setText("Finding optional suggestions · you can keep editing");
        a.worker.execute(()->{Bitmap image=null;List<double[]> points=new ArrayList<>();String error="";try{image=a.store.bitmap(model.original,model.reference());if(image==null)throw new IllegalArgumentException("Photo missing");if(a.impact==null)a.impact=ImpactVision.fromAssets(a);points=a.impact.detect(image).points;}catch(Exception e){error="Suggestions unavailable; add entry dots manually.";}finally{if(image!=null)image.recycle();}
            final List<double[]> hits=points;final String problem=error;a.runOnUiThread(()->{finding=false;if(closed||saving)return;refresh();if(!problem.isEmpty()){status.setText(problem);return;}if(model.revision!=revision){status.setText("Your edits were kept; stale suggestions were ignored.");return;}
                snapshot();int count=0;try{for(double[] hit:hits){boolean near=false;for(Store.View v:model.family())for(Store.Observation o:v.observations)if(!o.line&&Math.hypot(o.a[0]-hit[0],o.a[1]-hit[1])<model.reference().width*.012)near=true;if(near)continue;
                    int id=model.add(hit[0],hit[1]);selected.add(id);for(Store.View v:model.family())for(Store.Observation o:v.observations)if(o.id==id){o.source="impact-model:photo-suggestion";o.confidence=hit[2];o.associationWarning="Suggested entry; check the dot and assign its intended bullseye.";}count++;}
                    mode="select";refresh();status.setText(count+" suggested dots · check entries, then select their target");
                }catch(Exception e){status.setText("Existing marks kept. "+e.getMessage());}});});
    }
    void manual(){if(selected.isEmpty())return;try{
        if(mode.equals("outer")){if(outer.size()<6){status.setText("Add at least 6 well-spaced outer-rim points.");return;}mode="inner";refresh();}
        else if(mode.equals("inner")||mode.equals("corners")){
            TargetPreset preset=model.draft.preset();Geometry.Calibration fit=mode.equals("corners")?Geometry.calibratePaper(outer,preset.paperWidthMm,preset.paperHeightMm,model.reference().width,model.reference().height):Geometry.calibrate(outer,inner,preset.innerRatio,model.reference().width,model.reference().height);
            fit.reviewed=true;candidates.add(fit);assign(fit);outer.clear();inner.clear();
        }else{outer.clear();inner.clear();mode=model.draft.preset().circular()?"outer":"corners";refresh();}
    }catch(Exception e){status.setText("Not assigned: "+e.getMessage());}}
    void commit(){if(!loaded||saving)return;saving=true;a.busy=true;setCancelable(false);refresh();status.setText("Saving on this phone…");
        a.worker.execute(()->{Store.Group saved=null;String error="";try{saved=model.commit(a.store);}catch(Exception e){error=e.getMessage();}final Store.Group result=saved;final String problem=error;
            a.runOnUiThread(()->{a.busy=false;saving=false;if(closed||a.isDestroyed())return;if(result==null){setCancelable(true);refresh();status.setText("Not saved: "+problem);return;}a.snapshot();a.group=result;a.analysis=null;a.groupImages.thumbs.evictAll();dismiss();a.results();SyncRuntime.saved(a);});});
    }
    void closeWithActivity(){closed=true;super.dismiss();}
    @Override public void onBackPressed(){if(saving)return;if(model.revision==0){dismiss();return;}new AlertDialog.Builder(a).setMessage("Discard annotation changes? The saved photo is kept.").setPositiveButton("Discard",(d,w)->dismiss()).setNegativeButton("Keep editing",null).show();}
    @Override public void dismiss(){if(!saving)super.dismiss();}
}
