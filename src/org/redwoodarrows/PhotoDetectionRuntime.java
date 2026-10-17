package org.redwoodarrows;

import android.content.Context;
import android.graphics.Bitmap;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** A single process-wide inference lane, independent of camera, UI and sync. */
final class PhotoDetectionRuntime {
    static final ExecutorService worker=Executors.newSingleThreadExecutor();
    static final AtomicLong generation=new AtomicLong();
    static volatile Runnable uiChanged;
    static boolean running,again;static final List<Runnable> callbacks=new ArrayList<>();
    static ImpactVision detector;
    // Package-private injection for bounded, offline concurrency regression tests.
    interface Detector {Output detect(Context context,Store store,PhotoDetection.Task task)throws Exception;}
    static final class Output {
        final List<double[]> points;final Geometry.Calibration calibration;
        Output(List<double[]> p,Geometry.Calibration c){points=p;calibration=c;}
    }
    static volatile Detector testDetector;
    static Output detect(Context context,Store store,PhotoDetection.Task task)throws Exception {
        Bitmap image=store.bitmap(task.group,task.view);if(image==null)throw new IllegalArgumentException("Photo unavailable");
        try{if(detector==null)detector=ImpactVision.fromAssets(context);List<double[]> points=detector.detect(image).points;List<Geometry.Calibration> candidates=new ArrayList<>();
            if(!points.isEmpty())try{for(Vision.Rings r:Vision.ringCandidates(image,task.group.type))candidates.add(r.calibration);}catch(IllegalArgumentException ignored){}
            return new Output(points,PhotoDetection.target(candidates,points));
        }finally{image.recycle();}
    }
    /** Best-effort wakeup only. The pending record was committed with the photo. */
    static void saved(Context context){try{PhotoDetectionJobs.schedule(context);request(context,null);}catch(Exception e){android.util.Log.w("RedwoodArrows","Photo saved; detection will resume on reopening",e);}}
    static synchronized void request(Context context,Runnable done){
        Context c=context.getApplicationContext();if(done!=null)callbacks.add(done);again=true;if(running)return;running=true;
        worker.execute(()->{
            try{do{synchronized(PhotoDetectionRuntime.class){again=false;}
                Store store=new Store(c);for(PhotoDetection.Task task:PhotoDetection.pending(store)){
                    if(task.group.id.equals(Store.editingGroupId))continue;
                    Output output=null;boolean failed=false;try{Detector fake=testDetector;output=fake==null?detect(c,store,task):fake.detect(c,store,task);}catch(Exception e){failed=true;android.util.Log.w("RedwoodArrows","Detection unavailable; original photo and labels kept",e);}
                    try{if(PhotoDetection.complete(store,task,output==null?Collections.emptyList():output.points,output==null?null:output.calibration,failed)){
                        generation.incrementAndGet();Runnable listener=uiChanged;if(listener!=null)listener.run();SyncRuntime.saved(c);
                    }}catch(Exception e){android.util.Log.w("RedwoodArrows","Photo detection commit deferred",e);}
                }
                synchronized(PhotoDetectionRuntime.class){if(!again)break;}
            }while(true);}catch(Exception e){android.util.Log.w("RedwoodArrows","Photo queue will retry on next opening",e);}
            finally{List<Runnable> finished;boolean rerun;synchronized(PhotoDetectionRuntime.class){running=false;rerun=again;finished=new ArrayList<>(callbacks);callbacks.clear();}for(Runnable callback:finished)callback.run();if(rerun)request(c,null);}
        });
    }
}
