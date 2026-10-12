package org.redwoodarrows;

import android.content.Context;
import android.hardware.camera2.*;
import android.media.Image;
import android.os.SystemClock;
import com.google.ar.core.*;
import com.google.ar.core.exceptions.NotYetAvailableException;

/** All camera operations run on the sweep's GL thread; UI only requests a mode. */
final class SweepLight {
    private final Session session;private final TorchPolicy policy=new TorchPolicy();
    final boolean available;
    private volatile TorchPolicy.Mode requested=TorchPolicy.Mode.AUTO;
    volatile boolean on;volatile String problem="";
    volatile int sampleCount,lastP80=-1,lastIso=-1,lastFlashMode=-1,lastFlashState=-1;
    volatile long lastExposureNs;
    private long lastMeter=-1,lastValidMeter=-1,started=-1,settleUntil,lastFrame=-1;private boolean failed;
    private boolean pending=true;
    SweepLight(Context context,Session session){
        this.session=session;boolean has=false;
        try{CameraManager manager=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);has=Boolean.TRUE.equals(manager.getCameraCharacteristics(session.getCameraConfig().getCameraId()).get(CameraCharacteristics.FLASH_INFO_AVAILABLE));}
        catch(Exception e){android.util.Log.w("RedwoodArrows","Could not query flashlight support",e);}
        available=has;
    }
    void request(TorchPolicy.Mode mode){if(mode==null)throw new IllegalArgumentException("Light mode required");requested=mode;}
    TorchPolicy.Mode mode(){return requested;}
    /** Every frame, before tracking/capture decisions. Dark scenes may not track yet. */
    void update(Frame frame){
        long now=SystemClock.elapsedRealtime();if(started<0)started=now;
        if(requested!=policy.mode()){policy.mode(requested);failed=false;problem="";}
        if(!available){pending=false;return;}
        if(frame.getTimestamp()>0&&frame.getTimestamp()!=lastFrame&&(lastMeter<0||now-lastMeter>=250)){
            lastMeter=now;lastFrame=frame.getTimestamp();
            lastIso=-1;lastExposureNs=0;lastFlashMode=lastFlashState=-1;
            try{
                ImageMetadata metadata=frame.getImageMetadata();
                try{lastIso=metadata.getInt(ImageMetadata.SENSOR_SENSITIVITY);}catch(Exception e){lastIso=-1;}
                try{lastExposureNs=metadata.getLong(ImageMetadata.SENSOR_EXPOSURE_TIME);}catch(Exception e){lastExposureNs=0;}
                lastFlashMode=enumValue(metadata,ImageMetadata.FLASH_MODE);lastFlashState=enumValue(metadata,ImageMetadata.FLASH_STATE);
            }catch(Exception ignored){}
            // No RGB conversion, saved image, model invocation or extra camera session.
            if(policy.mode()==TorchPolicy.Mode.AUTO&&!policy.wantsOn()&&!failed){
                try(Image image=frame.acquireCameraImage()){
                    Image.Plane y=image.getPlanes()[0];TorchPolicy.Brightness b=TorchPolicy.measure(y.getBuffer(),image.getWidth(),image.getHeight(),y.getRowStride(),y.getPixelStride());
                    sampleCount++;lastValidMeter=now;lastP80=b.p80;policy.observe(b,lastIso,lastExposureNs,image.getTimestamp()/1_000_000L);problem="";
                }catch(NotYetAvailableException ignored){}
                catch(Exception e){problem="Brightness unavailable · choose On if needed";android.util.Log.w("RedwoodArrows","Brightness sample unavailable",e);}
            }
        }
        apply(now);pending=policy.pending();
    }
    private static int enumValue(ImageMetadata metadata,int key){try{return metadata.getByte(key)&255;}catch(Exception e){try{return metadata.getInt(key);}catch(Exception ignored){return -1;}}}
    private void apply(long now){
        if(failed||policy.wantsOn()==on)return;
        try{Config config=session.getConfig();config.setFlashMode(policy.wantsOn()?Config.FlashMode.TORCH:Config.FlashMode.OFF);session.configure(config);on=policy.wantsOn();settleUntil=now+900;}
        catch(Exception e){failed=true;problem="Light unavailable · you can still review photos";android.util.Log.w("RedwoodArrows","Could not change continuous light",e);}
    }
    boolean ready(){long now=SystemClock.elapsedRealtime();long last=lastValidMeter<0?started:lastValidMeter;return !available||failed||(now>=settleUntil&&(!pending||(last>=0&&now-last>3000)));}
    String label(){
        if(!available)return "Light unavailable";
        if(!problem.isEmpty())return problem;
        return requested==TorchPolicy.Mode.AUTO?(on?"Light: Auto · on":"Light: Auto"):(requested==TorchPolicy.Mode.ON?"Light: On":"Light: Off");
    }
    /** Caller has stopped the GL thread; pause/close releases the LED even if this fails. */
    void close(){policy.stop();requested=TorchPolicy.Mode.OFF;try{Config config=session.getConfig();config.setFlashMode(Config.FlashMode.OFF);session.configure(config);}catch(Exception e){android.util.Log.w("RedwoodArrows","Light reset before camera close",e);}on=false;}
}
