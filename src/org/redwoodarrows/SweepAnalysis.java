package org.redwoodarrows;

import android.graphics.Bitmap;
import java.util.*;

/** Staged, non-destructive proposals. Live capture uses a single-worker cache:
 * each new photo is registered and detected once, never on the camera thread. */
public final class SweepAnalysis {
    public final Store.Group preview;
    public final MultiViewFusion.Result fusion;
    public final List<String> warnings;
    public final Map<String,Geometry.Calibration> calibrations;
    public final String model;
    public final SweepReadiness.Assessment readiness;
    private SweepAnalysis(Store.Group p,MultiViewFusion.Result f,List<String> w,Map<String,Geometry.Calibration> c,String m,SweepReadiness.Assessment r){preview=p;fusion=f;warnings=w;calibrations=c;model=m;readiness=r;}
    public static SweepAnalysis run(Store store,Store.Group source,ImpactVision detector)throws Exception {
        try(Incremental session=new Incremental(store,detector)){
            Update update=session.update(source);
            if(update.analysis==null)throw new IllegalArgumentException(update.readiness.guidance);
            return update.analysis;
        }
    }
    public static final class Update {
        public final SweepAnalysis analysis;
        public final SweepReadiness.Assessment readiness;
        public final int processed;
        Update(SweepAnalysis a,SweepReadiness.Assessment r,int n){analysis=a;readiness=r;processed=n;}
    }
    /** Worker-thread confined. close() must be queued after outstanding work. */
    public static final class Incremental implements AutoCloseable {
        private final Store store;private final ImpactVision detector;
        private final SweepReadiness policy=new SweepReadiness();
        private final LinkedHashMap<String,String> signatures=new LinkedHashMap<>();
        private final List<MultiViewFusion.View> detections=new ArrayList<>();
        private final Map<String,Geometry.Calibration> calibrations=new LinkedHashMap<>();
        private final Map<String,Integer> depths=new HashMap<>();
        private final List<String> warnings=new ArrayList<>();
        private Bitmap reference;private Store.View ref;private String groupId,presetSignature;
        private double gate=.06;private double[] plane;private boolean closed;
        private Update latest;
        public Incremental(Store store,ImpactVision detector){this.store=store;this.detector=detector;}
        public Update update(Store.Group source)throws Exception {
            if(closed)throw new IllegalStateException("Sweep analysis has closed");
            if(source.views.isEmpty())throw new IllegalArgumentException("Capture or import views of one unchanged grouping first");
            Store.Group copy=Store.copy(source);String preset=Store.presetJson(copy.preset()).toString();
            if(groupId==null){groupId=copy.id;presetSignature=preset;}
            if(!groupId.equals(copy.id)||!presetSignature.equals(preset))throw new IllegalArgumentException("Start a fresh analysis after changing target or grouping");
            if(copy.views.size()<signatures.size())throw new IllegalArgumentException("Sweep photos changed during analysis");
            int i=0;Set<String> ids=new HashSet<>();
            for(Map.Entry<String,String> old:signatures.entrySet()){
                Store.View view=copy.views.get(i++);
                if(!old.getKey().equals(view.id)||!old.getValue().equals(view.json().toString()))throw new IllegalArgumentException("A captured photo changed during analysis");
            }
            for(Store.View v:copy.views)if(!ids.add(v.id))throw new IllegalArgumentException("Duplicate sweep photo");
            for(Store.View v:copy.views){
                if(signatures.containsKey(v.id))continue;
                signatures.put(v.id,v.json().toString());
                Bitmap image=store.bitmap(copy,v);String problem="";
                try{
                    if(image==null)throw new IllegalArgumentException("Photo is missing");
                    if(ref==null){
                        if(v.calibration==null){
                            if(!copy.preset().family.equals("Field"))throw new IllegalArgumentException("This preset needs a first-photo ring or paper-corner calibration. Use Review now to set it once.");
                            v.calibration=Vision.rings(image,"Field").calibration;
                        }
                        ref=v;reference=image;depths.put(v.id,0);
                        gate=Geometry.associationGate(v.calibration,v.width,v.height);
                        if(!v.calibration.reviewed)warnings.add("Automatic reference rings: check the overlay before accepting");
                        if(ref.pose!=null&&ref.k!=null&&!ref.session.isEmpty())try{
                            plane=Geometry.targetPose(ref.calibration.h,ref.k,ref.rotationMatrix(),ref.pose,copy.radiusMm()/1000);
                        }catch(IllegalArgumentException ignored){}
                    }else if(!v.id.equals(ref.id)){
                        double[] prior=null;try{prior=Vision.motionPrior(copy,ref,v);}catch(IllegalArgumentException ignored){}
                        try{v.calibration=Vision.align(reference,ref.calibration,image,prior,copy.preset());depths.put(v.id,1);}
                        catch(IllegalArgumentException direct){
                            boolean bridged=false;int tried=0;
                            for(int j=copy.views.indexOf(v)-1;j>=0&&tried<2;j--){
                                Store.View bridge=copy.views.get(j);
                                if(bridge.id.equals(ref.id)||!calibrations.containsKey(bridge.id)||depths.getOrDefault(bridge.id,0)>=3)continue;
                                bridge.calibration=calibrations.get(bridge.id);tried++;Bitmap neighbor=store.bitmap(copy,bridge);
                                if(neighbor==null)continue;
                                try{
                                    double[] bridgePrior=null;try{bridgePrior=Vision.motionPrior(copy,bridge,v);}catch(IllegalArgumentException ignored){}
                                    v.calibration=Vision.align(neighbor,bridge.calibration,image,bridgePrior,copy.preset());
                                    int depth=depths.get(bridge.id)+1;depths.put(v.id,depth);
                                    v.calibration.source+=" · via view "+(j+1)+" · chain depth "+depth;
                                    warnings.add("View "+(copy.views.indexOf(v)+1)+" aligned through view "+(j+1)+"; chained registration may accumulate drift");
                                    bridged=true;break;
                                }catch(IllegalArgumentException ignored){}finally{neighbor.recycle();}
                            }
                            if(!bridged)throw direct;
                        }
                    }
                    ImpactVision.Result found=detector.detect(image);
                    detections.add(new MultiViewFusion.View(v.id,v.calibration.h,found.points));
                    calibrations.put(v.id,v.calibration);
                }catch(IllegalArgumentException e){
                    problem=e.getMessage();v.calibration=null;
                    if(v==ref){ref=null;plane=null;if(reference!=null){reference.recycle();reference=null;image=null;}}
                    warnings.add("View "+(copy.views.indexOf(v)+1)+" excluded: "+problem);
                }finally{if(image!=null&&image!=reference)image.recycle();}
                MultiViewFusion.Result fusion=MultiViewFusion.fuse(detections,gate);
                SweepReadiness.View evidence=readinessView(copy,v,problem);
                SweepReadiness.Assessment readiness=policy.update(evidence,fusion,gate);
                latest=new Update(detections.isEmpty()?null:proposal(copy,fusion,readiness),readiness,signatures.size());
            }
            return latest;
        }
        private SweepReadiness.View readinessView(Store.Group group,Store.View view,String problem){
            double[] camera=null;boolean quality=calibrations.containsKey(view.id);
            if(!quality){if(problem==null||problem.isEmpty())problem="Keep the entire face in view and hold briefly so the paper can align.";}
            else if(plane==null||view.pose==null||view.k==null||view.session.isEmpty()||!view.session.equals(ref.session)){
                quality=false;problem="Waiting for reliable camera motion. Keep the target in view and move slowly sideways.";
            }else try{
                double radius=group.radiusMm()/1000;
                double[] relative=Geometry.mul4(Geometry.inverseRigid(plane),view.pose);
                camera=new double[]{relative[3]/radius,relative[7]/radius,relative[11]/radius};
                if(!SweepReadiness.validCamera(camera))throw new IllegalArgumentException("Camera angle is not reliable yet. Try a clearer, nearby view.");
                if(depths.get(view.id)>2)throw new IllegalArgumentException("Move back toward an earlier angle so the target can align without a long chain.");
                if(!SweepEvidence.faceVisible(view.calibration.h,group.preset().outline(.98),view.width,view.height))throw new IllegalArgumentException("Keep the entire printed face in view, including its outer edge.");
                double[] prior=Geometry.motionHomography(plane,view.pose,view.k,view.rotationMatrix(),radius);
                if(!SweepEvidence.motionAgrees(view.calibration.h,prior,gate*1.5))throw new IllegalArgumentException("Motion and target alignment disagree. Hold briefly, then try a nearby angle.");
            }catch(IllegalArgumentException e){quality=false;problem=e.getMessage();}
            return new SweepReadiness.View(view.id,camera,quality,problem);
        }
        private SweepAnalysis proposal(Store.Group source,MultiViewFusion.Result fused,SweepReadiness.Assessment readiness)throws Exception {
            Store.Group copy=Store.copy(source);Map<String,Store.View> byId=new HashMap<>();
            Map<String,Geometry.Calibration> proposalCals=new LinkedHashMap<>();
            for(Store.View v:copy.views){
                v.observations.clear();v.calibration=null;byId.put(v.id,v);
                Geometry.Calibration cached=calibrations.get(v.id);
                if(cached!=null){v.calibration=new Geometry.Calibration(cached.h.clone(),cached.rms,cached.source);v.calibration.reviewed=true;proposalCals.put(v.id,v.calibration);}
            }
            // An initially blurred/unusable photo need not poison the whole
            // sweep. The first successfully calibrated photo defines all axes.
            Store.View referenceView=byId.get(ref.id);copy.views.remove(referenceView);copy.views.add(0,referenceView);
            List<String> notes=new ArrayList<>(warnings);
            boolean trackedCapture=false;for(Store.View v:copy.views)trackedCapture|=v.pose!=null&&!v.session.isEmpty();
            if(fused.ambiguousObservations>0)notes.add(fused.ambiguousObservations+" detections were too close to associate safely; they were not silently assigned to an arrow");
            int id=0;for(MultiViewFusion.Track track:fused.tracks){id++;for(MultiViewFusion.Evidence e:track.evidence){
                Store.Observation o=new Store.Observation(id,new double[]{e.imageX,e.imageY},null);
                o.source="impact-model:"+detector.modelSha256;o.confidence=e.score;
                o.associationWarning=track.ambiguous?"Close-arrow association ambiguous":track.evidence.size()<2?"Only one view supports this entry":"";
                if(trackedCapture&&readiness.trackIssues.containsKey(id))o.associationWarning=readiness.trackIssues.get(id);
                byId.get(e.view).observations.add(o);
            }}
            return new SweepAnalysis(copy,fused,notes,proposalCals,detector.modelSha256,readiness);
        }
        @Override public void close(){if(closed)return;closed=true;if(reference!=null){reference.recycle();reference=null;}}
    }
}
