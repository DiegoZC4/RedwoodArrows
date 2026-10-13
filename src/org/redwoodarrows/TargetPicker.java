package org.redwoodarrows;

import android.app.Dialog;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import java.util.*;

/** One photo, one tap near the aimed-at bullseye, one explicit save. All work
 * stays in a draft until Use this target. No arrow detection is overwritten. */
final class TargetPicker extends Dialog {
    final MainActivity a;final Store.Group source;final Store.View view;
    final PhotoCanvas photo;final TextView status;final Button use,cancel,manual;
    final List<Geometry.Calibration> candidates=new ArrayList<>();
    Bitmap bitmap;Store.View preview;Geometry.Calibration selected;TargetPreset preset;boolean closed,saving,loaded;
    TargetPicker(MainActivity activity,Store.Group source,int index){
        super(activity,android.R.style.Theme_Material_NoActionBar_Fullscreen);a=activity;this.source=source;view=source.views.get(index);preset=source.preset();
        LinearLayout root=new LinearLayout(a);root.setOrientation(1);root.setBackgroundColor(MainActivity.BG);root.setPadding(a.dp(12),a.dp(8),a.dp(12),a.dp(8));
        TextView title=a.text("Which target did you aim at?",22,MainActivity.FG);title.setTypeface(null,1);a.add(root,title);
        Button size=a.button(preset.name,()->{});size.setTag("target-size");size.setOnClickListener(v->chooseSize(size));if(!(preset.family.equals("Field")||preset.family.equals("Hunter")))size.setEnabled(false);a.add(root,size);
        status=a.text("Finding targets…",14,MainActivity.MUTED);status.setMinHeight(a.dp(56));status.setTag("target-status");a.add(root,status);
        photo=new PhotoCanvas(a,new PhotoCanvas.Listener(){public void tap(double x,double y){choose(x,y);}public void proposal(double[] l){}public void beforeMove(){}public void moved(){}});
        photo.mode="target";photo.preset=source.preset();photo.unconfirmed=true;photo.setTag("target-photo");photo.setContentDescription("Tap the center of the target you aimed at. Pinch to zoom; drag to pan. Arrow marks cannot be moved here.");root.addView(photo,new LinearLayout.LayoutParams(-1,0,1));
        manual=a.button(source.preset().circular()?"Mark rings manually":"Mark paper corners",()->{if(saving)return;dismiss();a.index=0;a.mode="hit";a.editor();a.manualRings();});manual.setTag("target-manual");a.add(root,manual);
        LinearLayout actions=a.row();cancel=a.button("Cancel",this::dismiss);cancel.setTag("target-cancel");use=a.button("Use this target",this::commit);use.setTag("target-use");use.setEnabled(false);use.setBackground(a.rounded(MainActivity.ACCENT));use.setTextColor(MainActivity.BG);a.cell(actions,cancel);a.cell(actions,use);a.add(root,actions);setContentView(root);
        setOnDismissListener(d->{closed=true;if(bitmap!=null){bitmap.recycle();bitmap=null;}if(a.targetPicker==this)a.targetPicker=null;});
    }
    void chooseSize(Button size){if(saving)return;List<TargetPreset> choices=new ArrayList<>();for(TargetPreset p:TargetCatalog.all())if(p.family.equals(preset.family))choices.add(p);String[] names=new String[choices.size()];for(int i=0;i<names.length;i++)names[i]=choices.get(i).name;new AlertDialog.Builder(a).setTitle("Printed target size").setItems(names,(d,w)->{preset=choices.get(w);size.setText(preset.name);photo.preset=preset;photo.invalidate();}).setNegativeButton("Cancel",null).show();}
    void closeWithActivity(){saving=false;dismiss();}
    @Override public void show(){super.show();getWindow().setLayout(-1,-1);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        a.worker.execute(()->{Bitmap image=null;List<Vision.Rings> rings=new ArrayList<>();String failure="";
            try{image=a.store.bitmap(source,view);if(image==null)throw new IllegalArgumentException("Saved photo is missing");if(source.preset().family.equals("Field")||source.preset().family.equals("Hunter"))rings=Vision.ringCandidates(image,source.preset().family);else failure="For this design, mark the rings or paper corners on the reference photo.";}
            catch(Exception e){failure="No reliable automatic outlines here. Use manual marks on the reference photo.";}
            final Bitmap ready=image;final List<Vision.Rings> found=rings;final String problem=failure;
            a.runOnUiThread(()->{if(closed||a.isDestroyed()){if(ready!=null)ready.recycle();return;}bitmap=ready;loaded=true;
                try{preview=Store.View.from(view.json());preview.calibration=null;photo.photo(bitmap,preview);photo.mode="target";for(Vision.Rings r:found)candidates.add(r.calibration);
                    // Include a hand-fitted existing face if automatic fitting missed it.
                    if(view.calibration!=null)candidates.add(TargetSelection.copy(view.calibration));
                    status.setText(problem.isEmpty()?"Tap near your bullseye. Check the highlighted rings, then save.":problem);photo.invalidate();
                }catch(Exception e){status.setText("Could not load this photo. Close and reopen the grouping.");}
            });
        });
    }
    void choose(double x,double y){if(!loaded||saving||preview==null)return;int choice=TargetSelection.nearest(candidates,x,y);selected=choice<0?null:TargetSelection.copy(candidates.get(choice));preview.calibration=selected;use.setEnabled(selected!=null);photo.invalidate();
        status.setText(selected==null?"No outline at that point. Tap closer to the bullseye, or mark the rings manually.":"Do these rings match your target? Arrow dots stay in their original photo positions.");
    }
    void commit(){if(selected==null||saving||a.busy)return;saving=true;a.busy=true;use.setEnabled(false);cancel.setEnabled(false);manual.setEnabled(false);setCancelable(false);status.setText("Updating coordinates and aligning the other photos…");
        final Geometry.Calibration chosen=TargetSelection.copy(selected);
        a.worker.execute(()->{Store.Group changed=null;String failure="";int aligned=0,missing=0;
            try{changed=TargetSelection.draft(source,view.id,chosen);changed.target=preset;changed.type=preset.family;changed.diameterCm=(int)Math.round(preset.diameterMm/10);Store.View ref=changed.views.get(0);Bitmap reference=a.store.bitmap(changed,ref);
                if(reference==null)throw new IllegalArgumentException("Reference photo is missing");
                try{for(int i=1;i<changed.views.size();i++){Store.View next=changed.views.get(i);Bitmap image=a.store.bitmap(changed,next);try{
                    if(image==null)throw new IllegalArgumentException("Missing photo");double[] prior=null;try{prior=Vision.motionPrior(changed,ref,next);}catch(IllegalArgumentException ignored){}
                    next.calibration=Vision.align(reference,ref.calibration,image,prior,changed.preset());aligned++;
                }catch(IllegalArgumentException e){next.calibration=null;missing++;}finally{if(image!=null)image.recycle();}}}finally{reference.recycle();}
            }catch(Exception e){failure=e.getMessage()==null?e.toString():e.getMessage();}
            final Store.Group result=changed;final String error=failure;final int matched=aligned,excluded=missing;
            a.runOnUiThread(()->{a.busy=false;if(closed||a.isDestroyed())return;
                try{if(!error.isEmpty())throw new IllegalArgumentException(error);a.store.save(result);a.snapshot();a.group=result;a.analysis=null;a.index=0;a.mode="hit";saving=false;dismiss();a.results();
                    if(matched+excluded>0)a.message("Target saved. "+matched+" other photo(s) aligned and need their overlays confirmed; "+excluded+" could not align. Until confirmed, positions use this photo only. Arrow marks and photos are preserved.");
                }catch(Exception e){saving=false;use.setEnabled(true);cancel.setEnabled(true);manual.setEnabled(true);setCancelable(true);status.setText("Not saved: "+e.getMessage());}
            });
        });
    }
    @Override public void dismiss(){if(!saving)super.dismiss();}
}
