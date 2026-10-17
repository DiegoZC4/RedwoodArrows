package org.redwoodarrows;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.*;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

/** Ordinary offline Camera2 still capture. No ARCore, inference or pose required.
 * Camera/session work is serialized; a shutter snapshot owns the photo's labels. */
final class PhotoCamera extends LinearLayout implements TextureView.SurfaceTextureListener {
    interface Listener {void saved(Store.Group group);}
    final MainActivity a;final Store store;final Store.Group draft;final Listener listener;
    final CaptureLabels labels;final TextureView texture;final FrameLayout previewArea;
    final Button shutter,lightButton;final TextView status;final TorchPolicy light=new TorchPolicy();
    final HandlerThread thread=new HandlerThread("Grouping camera");final Handler camera;
    private CameraDevice device;private CameraCaptureSession session;private ImageReader reader;private Surface surface;
    private volatile SurfaceTexture retainedTexture;
    private CaptureRequest.Builder repeating;private CameraCharacteristics characteristics;private String cameraId;
    private Size photoSize,previewSize;private int afMode,sensorRotation;
    private volatile boolean closed,ready,saving,failed;private boolean opening,openPending,flashAvailable,torch,requestedStill,focusArmed;
    private volatile int iso;private volatile long exposure;private long lightChanged;
    volatile int actualFlashMode=-1;
    private Store.Group pending;private long shutterAt;
    private final Runnable focusTimeout=()->captureStill();
    private final Runnable captureTimeout=()->fail("The camera did not finish saving. Reopen it and try again.");
    PhotoCamera(MainActivity activity,Store store,Store.Group draft,Listener listener)throws Exception{
        super(activity);a=activity;this.store=store;this.draft=draft;this.listener=listener;
        setOrientation(VERTICAL);setBackgroundColor(MainActivity.BG);
        CameraManager manager=(CameraManager)a.getSystemService(Context.CAMERA_SERVICE);
        for(String id:manager.getCameraIdList()){CameraCharacteristics c=manager.getCameraCharacteristics(id);if(Objects.equals(c.get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_BACK)){cameraId=id;characteristics=c;break;}}
        if(cameraId==null)throw new IOException("No rear camera is available. Add photos still works.");
        StreamConfigurationMap map=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);if(map==null)throw new IOException("Camera outputs unavailable");
        // A high-quality ~4 MP camera JPEG, not a screen grab or re-encoded CPU
        // frame. Bounded enough for the existing 8 MB/photo private sync format.
        photoSize=chooseSize(map.getOutputSizes(ImageFormat.JPEG),4_200_000L,4./3.);
        previewSize=chooseSize(map.getOutputSizes(SurfaceTexture.class),1280L*960,photoSize.getWidth()/(double)photoSize.getHeight());
        sensorRotation=characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);flashAvailable=Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
        int[] modes=characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);afMode=CaptureRequest.CONTROL_AF_MODE_OFF;
        if(modes!=null){for(int mode:modes)if(mode==CaptureRequest.CONTROL_AF_MODE_AUTO)afMode=mode;for(int mode:modes)if(mode==CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)afMode=mode;}
        LinearLayout top=a.row();top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(a.dp(16),a.dp(4),a.dp(16),a.dp(4));
        Button back=a.button("Back",a::onBackPressed);top.addView(back,new LayoutParams(a.dp(68),a.dp(44)));
        TextView title=a.text(draft.hasData()?"Same grouping":"New grouping",18,MainActivity.FG);title.setGravity(Gravity.CENTER);top.addView(title,new LayoutParams(0,-2,1));
        lightButton=a.button("Light: Auto",this::chooseLight);lightButton.setTextSize(12);lightButton.setTag("photo-light");lightButton.setEnabled(flashAvailable);top.addView(lightButton,new LayoutParams(a.dp(104),a.dp(44)));addView(top);
        previewArea=new FrameLayout(a);previewArea.setBackgroundColor(0xFF000000);texture=new TextureView(a);texture.setSurfaceTextureListener(this);previewArea.addView(texture,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));addView(previewArea,new LayoutParams(-1,0,1));
        previewArea.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->fitPreview());
        LinearLayout panel=new LinearLayout(a);panel.setOrientation(VERTICAL);panel.setPadding(a.dp(16),a.dp(8),a.dp(16),a.dp(12));
        labels=new CaptureLabels(a,()->draft,!draft.hasData(),(person,practice)->{if(saving||closed)return false;if(!draft.shooter.id.equals(person.id))Store.confirmShooter(draft,person);draft.practice=Practice.from(practice.json());refresh();return true;});panel.addView(labels);
        status=a.text("Opening camera…",12,MainActivity.MUTED);status.setGravity(Gravity.CENTER);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);panel.addView(status);
        shutter=a.button("Take photo",this::takePhoto);shutter.setTag("camera-shutter");shutter.setTextSize(19);shutter.setBackground(a.rounded(MainActivity.ACCENT));shutter.setTextColor(MainActivity.BG);shutter.setContentDescription("Save original photo with the shooter and distance shown above");shutter.setEnabled(false);panel.addView(shutter,new LayoutParams(-1,a.dp(56)));
        TextView note=a.text("Photo + shooter + distance · saved offline",12,MainActivity.MUTED);note.setGravity(Gravity.CENTER);panel.addView(note);addView(panel);
        thread.start();camera=new Handler(thread.getLooper());postDelayed(meter,300);
    }
    static Size chooseSize(Size[] sizes,long budget,double ratio)throws IOException{
        if(sizes==null||sizes.length==0)throw new IOException("Camera has no compatible photo size");Size best=null,fallback=null;double score=Double.POSITIVE_INFINITY;
        for(Size size:sizes){long pixels=(long)size.getWidth()*size.getHeight();if(size.getWidth()>SyncFiles.PHOTO_MAX_DIMENSION||size.getHeight()>SyncFiles.PHOTO_MAX_DIMENSION||pixels>16_000_000L)continue;if(fallback==null||pixels<(long)fallback.getWidth()*fallback.getHeight())fallback=size;if(pixels>budget)continue;double candidate=Math.abs(Math.log((size.getWidth()/(double)size.getHeight())/ratio))*10+(1-pixels/(double)budget);if(candidate<score){score=candidate;best=size;}}
        if(best==null)best=fallback;if(best==null)throw new IOException("Camera photo sizes exceed the supported sync limit");return best;
    }
    boolean isReady(){return ready&&!closed&&!failed;}
    boolean isSaving(){return saving;}
    private boolean hasLabels(){return draft.shooter.assigned()&&Double.isFinite(draft.practice.meters)&&draft.practice.meters>0;}
    private void refresh(){if(closed||status==null)return;shutter.setEnabled(ready&&!saving&&!failed&&hasLabels());status.setText(saving?"Saving photo…":failed?"Camera interrupted · go back and try again":!ready?"Opening camera…":!draft.shooter.assigned()?"Choose a shooter above · names are in More → People":!hasLabels()?"Set the shooting distance above":"Keep the whole grouping in view");}
    private void chooseLight(){new AlertDialog.Builder(a).setTitle("Continuous flashlight").setSingleChoiceItems(new String[]{"Auto","On","Off"},light.mode().ordinal(),(d,which)->{setLight(TorchPolicy.Mode.values()[which]);d.dismiss();}).setNegativeButton("Cancel",null).show();}
    void setLight(TorchPolicy.Mode mode){if(closed||saving)return;light.mode(mode);updateLight();}
    private void updateLight(){boolean wanted=light.wantsOn();lightButton.setText(!flashAvailable?"No light":light.mode()==TorchPolicy.Mode.AUTO?(wanted?"Auto · on":"Light: Auto"):wanted?"Light: On":"Light: Off");if(flashAvailable&&wanted!=torch){torch=wanted;lightChanged=SystemClock.elapsedRealtime();camera.post(()->{try{repeat();}catch(Exception e){fail("Could not set the flashlight. Reopen the camera.");}});}}
    private final Runnable meter=new Runnable(){public void run(){
        if(closed)return;if(ready&&!saving&&flashAvailable&&light.mode()==TorchPolicy.Mode.AUTO&&!light.wantsOn()){
            Bitmap b=texture.getBitmap(64,48);if(b!=null)try{int[] rgb=new int[64*48];b.getPixels(rgb,0,64,0,0,64,48);ByteBuffer y=ByteBuffer.allocate(rgb.length);for(int c:rgb)y.put((byte)((77*Color.red(c)+150*Color.green(c)+29*Color.blue(c))>>8));y.flip();light.observe(TorchPolicy.measure(y,64,48,64,1),iso,exposure,SystemClock.elapsedRealtime());updateLight();}finally{b.recycle();}
        }postDelayed(this,300);
    }};
    private void settings(CaptureRequest.Builder request){request.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);request.set(CaptureRequest.CONTROL_AF_MODE,afMode);request.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);if(flashAvailable)request.set(CaptureRequest.FLASH_MODE,torch?CaptureRequest.FLASH_MODE_TORCH:CaptureRequest.FLASH_MODE_OFF);}
    private final CameraCaptureSession.CaptureCallback previewCallback=new CameraCaptureSession.CaptureCallback(){@Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult result){
        Integer sensitivity=result.get(CaptureResult.SENSOR_SENSITIVITY),flash=result.get(CaptureResult.FLASH_MODE);Long duration=result.get(CaptureResult.SENSOR_EXPOSURE_TIME);iso=sensitivity==null?0:sensitivity;exposure=duration==null?0:duration;actualFlashMode=flash==null?-1:flash;
        if(!ready&&!closed&&!failed){ready=true;post(()->refresh());}
        if(pending!=null&&focusArmed&&!requestedStill){Integer state=result.get(CaptureResult.CONTROL_AF_STATE);if(state==null||state==CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED||state==CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)captureStill();}
    }};
    private void repeat()throws Exception{if(session==null||repeating==null||closed||failed)return;settings(repeating);repeating.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE);session.setRepeatingRequest(repeating.build(),previewCallback,camera);}
    private void open(){if(closed||opening||!texture.isAvailable())return;opening=true;SurfaceTexture target=texture.getSurfaceTexture();
        camera.post(()->{try{
            if(closed)return;target.setDefaultBufferSize(previewSize.getWidth(),previewSize.getHeight());surface=new Surface(target);
            reader=ImageReader.newInstance(photoSize.getWidth(),photoSize.getHeight(),ImageFormat.JPEG,2);reader.setOnImageAvailableListener(this::receive,camera);
            openPending=true;((CameraManager)a.getSystemService(Context.CAMERA_SERVICE)).openCamera(cameraId,new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice opened){openPending=false;if(closed){opened.close();release();return;}device=opened;try{device.createCaptureSession(Arrays.asList(surface,reader.getSurface()),new CameraCaptureSession.StateCallback(){
                    @Override public void onConfigured(CameraCaptureSession configured){if(closed){configured.close();return;}session=configured;try{repeating=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);repeating.addTarget(surface);repeat();}catch(Exception e){fail(e.getMessage());}}
                    @Override public void onConfigureFailed(CameraCaptureSession s){fail("This camera could not start a photo session");}
                },camera);}catch(Exception e){fail(e.getMessage());}}
                @Override public void onDisconnected(CameraDevice d){openPending=false;d.close();fail("Camera disconnected");}
                @Override public void onError(CameraDevice d,int error){openPending=false;d.close();fail("Camera unavailable ("+error+"). Check camera access, then reopen it.");}
            },camera);
        }catch(Exception e){openPending=false;fail(e.getMessage());}});
    }
    private void takePhoto(){
        if(!isReady()||saving||!hasLabels())return;
        try{Store.Group snapshot=Store.copy(draft);saving=true;labels.setEnabled(false);lightButton.setEnabled(false);refresh();
            camera.post(()->{if(failed){post(()->{saving=false;refresh();});return;}pending=snapshot;shutterAt=System.currentTimeMillis();requestedStill=false;focusArmed=false;camera.postDelayed(captureTimeout,10000);
                // Settle a newly enabled continuous light, then lock autofocus
                // briefly. Fixed-focus cameras bypass the lock.
                camera.postDelayed(()->{if(pending==null)return;try{if(afMode==CaptureRequest.CONTROL_AF_MODE_OFF){captureStill();return;}focusArmed=true;repeating.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_START);session.capture(repeating.build(),previewCallback,camera);repeating.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE);camera.postDelayed(focusTimeout,1200);}catch(Exception e){fail(e.getMessage());}},Math.max(0,700-(SystemClock.elapsedRealtime()-lightChanged)));
            });
        }catch(Exception e){fail(e.getMessage());}
    }
    private void captureStill(){if(pending==null||requestedStill||failed)return;requestedStill=true;camera.removeCallbacks(focusTimeout);
        try{CaptureRequest.Builder still=device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);settings(still);still.addTarget(reader.getSurface());int display=a.getWindowManager().getDefaultDisplay().getRotation()*90;still.set(CaptureRequest.JPEG_ORIENTATION,(sensorRotation-display+360)%360);still.set(CaptureRequest.JPEG_QUALITY,(byte)95);
            // Intentionally no JPEG_GPS_LOCATION: no location permission or EXIF GPS.
            session.capture(still.build(),new CameraCaptureSession.CaptureCallback(){@Override public void onCaptureFailed(CameraCaptureSession s,CaptureRequest r,CaptureFailure failure){fail("Photo capture failed. Reopen the camera and try again.");}},camera);
        }catch(Exception e){fail(e.getMessage());}
    }
    private void receive(ImageReader images){
        try(Image image=images.acquireNextImage()){
            if(image==null||pending==null||failed)return;camera.removeCallbacks(captureTimeout);camera.removeCallbacks(focusTimeout);
            ByteBuffer data=image.getPlanes()[0].getBuffer();if(data.remaining()>SyncFiles.PHOTO_LIMIT)throw new IOException("Photo exceeds the 8 MB sync limit; it was not saved");byte[] jpeg=new byte[data.remaining()];data.get(jpeg);
            Store.Group snapshot=pending;pending=null;Store.Group saved=store.saveCameraPhoto(snapshot,jpeg,shutterAt,image.getTimestamp());
            if(snapshot.views.isEmpty())a.shooters.captured(saved);SyncRuntime.saved(a);saving=false;
            a.runOnUiThread(()->{if(!a.isDestroyed())listener.saved(saved);});
        }catch(Exception e){fail("Photo not saved: "+e.getMessage());}
        finally{if(closed||!saving)release();}
    }
    private void fail(String message){
        if(failed)return;failed=true;ready=false;saving=false;pending=null;camera.removeCallbacks(captureTimeout);camera.removeCallbacks(focusTimeout);
        android.util.Log.w("RedwoodArrows","Still camera: "+message);a.runOnUiThread(()->{if(!closed){labels.setEnabled(true);refresh();status.setText(message==null?"Camera unavailable · go back and retry":message);}});camera.post(this::release);
    }
    void close(){if(closed)return;closed=true;removeCallbacks(meter);camera.post(()->{if(!saving)release();});}
    /** A shutter already in flight may finish its local save after onPause. */
    private void release(){ready=false;if(openPending)return;if(session!=null){session.close();session=null;}if(device!=null){device.close();device=null;}if(reader!=null){reader.close();reader=null;}if(surface!=null){surface.release();surface=null;}if(retainedTexture!=null){retainedTexture.release();retainedTexture=null;}if(closed)thread.quitSafely();}
    private void fitPreview(){if(previewArea.getWidth()==0||previewArea.getHeight()==0)return;int rotation=a.getWindowManager().getDefaultDisplay().getRotation();boolean swap=((sensorRotation-rotation*90+360)%180)!=0;double ratio=swap?previewSize.getHeight()/(double)previewSize.getWidth():previewSize.getWidth()/(double)previewSize.getHeight();int w=previewArea.getWidth(),h=(int)Math.round(w/ratio);if(h>previewArea.getHeight()){h=previewArea.getHeight();w=(int)Math.round(h*ratio);}
        // This runs inside the parent's layout callback. Reapplying identical
        // parameters requests another layout indefinitely and starves the UI.
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)texture.getLayoutParams();if(p.width!=w||p.height!=h){p.width=w;p.height=h;p.gravity=Gravity.CENTER;texture.setLayoutParams(p);}
        Matrix matrix=new Matrix();if(rotation==Surface.ROTATION_90||rotation==Surface.ROTATION_270){RectF view=new RectF(0,0,w,h),buffer=new RectF(0,0,previewSize.getHeight(),previewSize.getWidth());buffer.offset(view.centerX()-buffer.centerX(),view.centerY()-buffer.centerY());matrix.setRectToRect(view,buffer,Matrix.ScaleToFit.FILL);float scale=Math.max(h/(float)previewSize.getHeight(),w/(float)previewSize.getWidth());matrix.postScale(scale,scale,view.centerX(),view.centerY());matrix.postRotate(90*(rotation-2),view.centerX(),view.centerY());}else if(rotation==Surface.ROTATION_180)matrix.postRotate(180,w/2f,h/2f);texture.setTransform(matrix);
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){fitPreview();open();}
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s){boolean retain=saving;if(retain)retainedTexture=s;close();return !retain;}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s){}
}
