package org.redwoodarrows;

import android.app.Activity;
import android.graphics.*;
import android.media.Image;
import android.opengl.*;
import android.widget.*;
import com.google.ar.core.*;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.examples.java.common.rendering.BackgroundRenderer;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Bounded, on-device visual-inertial capture. CPU image, pose and intrinsics
 * are recorded from the same tracked ARCore frame, relative to one anchor.
 */
public final class SweepView extends android.widget.FrameLayout implements GLSurfaceView.Renderer {
    public interface Listener {void captured(Store.Group snapshot);void finished();void error(String message);}
    private final Activity activity;private final Store store;private final Store.Group group;private final Listener listener;
    private final GLSurfaceView gl;private final TextView status;private final Button capture,done,auto,lightButton;
    final SweepLight light;
    private final BackgroundRenderer background=new BackgroundRenderer();private final AtomicBoolean requested=new AtomicBoolean(false);
    private final AtomicBoolean analysisPending=new AtomicBoolean(false);
    private Session session;private Anchor origin;private final String sessionId=UUID.randomUUID().toString();
    private volatile boolean closed=false;private boolean textureReady=false;private int width=1,height=1;private volatile int count=0;private long lastStatus=0;
    private volatile String lastCaptureError="";
    private volatile boolean automatic=true,finishing=false,finishRequested=false;
    private volatile String completionNotice="";
    private volatile SweepReadiness.Assessment assessment=SweepReadiness.starting();
    private final SweepPolicy keyframes=new SweepPolicy();
    public SweepView(Activity activity,Store store,Store.Group group,Listener listener)throws Exception {
        super(activity);this.activity=activity;this.store=store;this.group=group;this.listener=listener;
        if(ArCoreApk.getInstance().requestInstall(activity,true)==ArCoreApk.InstallStatus.INSTALL_REQUESTED)throw new IllegalStateException("Finish the AR service installation, then open the camera again");
        session=new Session(activity);
        Config config=new Config(session);config.setFocusMode(Config.FocusMode.AUTO);config.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);config.setLightEstimationMode(Config.LightEstimationMode.DISABLED);config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
        CameraConfigFilter filter=new CameraConfigFilter(session);filter.setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30));
        List<CameraConfig> configs=session.getSupportedCameraConfigs(filter);CameraConfig selected=null;
        for(CameraConfig c:configs)if(c.getImageSize().getWidth()<=1920&&(selected==null||c.getImageSize().getWidth()>selected.getImageSize().getWidth()))selected=c;
        if(selected!=null)session.setCameraConfig(selected);config.setFlashMode(Config.FlashMode.OFF);session.configure(config);light=new SweepLight(activity,session);
        gl=new GLSurfaceView(activity);gl.setEGLContextClientVersion(2);gl.setPreserveEGLContextOnPause(true);gl.setEGLConfigChooser(8,8,8,8,16,0);gl.setRenderer(this);addView(gl,new LayoutParams(-1,-1));
        int pad=Math.round(16*getResources().getDisplayMetrics().density),buttonHeight=Math.round(56*getResources().getDisplayMetrics().density);
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(pad,pad,pad,pad);panel.setBackgroundColor(0xDF101C19);
        LinearLayout info=new LinearLayout(activity);info.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView shooter=new TextView(activity);shooter.setText("Shooter: "+group.shooter.label());shooter.setTextColor(0xFFD8EB98);shooter.setTextSize(13);info.addView(shooter,new LinearLayout.LayoutParams(0,-2,1));
        lightButton=new Button(activity);lightButton.setAllCaps(false);lightButton.setTextSize(13);lightButton.setText(light.label());lightButton.setTag("sweep-light");lightButton.setEnabled(light.available);
        info.addView(lightButton,new LinearLayout.LayoutParams(-2,Math.round(48*getResources().getDisplayMetrics().density)));panel.addView(info);
        lightButton.setOnClickListener(v->{if(closed||finishing)return;new android.app.AlertDialog.Builder(activity).setTitle("Continuous flashlight")
            .setSingleChoiceItems(new String[]{"Auto · turn on when the image is too dark","On · keep the light on","Off"},light.mode().ordinal(),(dialog,which)->{light.request(TorchPolicy.Mode.values()[which]);lightButton.setText(light.label());dialog.dismiss();})
            .setNegativeButton("Cancel",null).show();});
        status=new TextView(activity);status.setTextColor(0xFFF2F6EA);status.setTextSize(16);status.setText("Automatic sweep\nKeep the complete face in view and move slowly sideways. I’ll tell you when there are enough angles.");status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);panel.addView(status);
        LinearLayout row=new LinearLayout(activity);capture=new Button(activity);capture.setText("Take photo");capture.setAllCaps(false);auto=new Button(activity);auto.setText("Pause");auto.setAllCaps(false);done=new Button(activity);done.setText("Review now");done.setAllCaps(false);row.addView(capture,new LinearLayout.LayoutParams(0,buttonHeight,1));row.addView(auto,new LinearLayout.LayoutParams(0,buttonHeight,1));row.addView(done,new LinearLayout.LayoutParams(0,buttonHeight,1));panel.addView(row);
        TextView optional=new TextView(activity);optional.setText("Stops automatically · Review now is optional");optional.setTextColor(0xFFB5C4B7);optional.setTextSize(12);panel.addView(optional);
        LayoutParams p=new LayoutParams(-1,-2,android.view.Gravity.BOTTOM);addView(panel,p);
        capture.setOnClickListener(v->{if(finishing||finishRequested||analysisPending.get()||count>=SweepPolicy.LIMIT)return;lastCaptureError="";capture.setEnabled(false);requested.set(true);});
        auto.setOnClickListener(v->{if(finishing||finishRequested||count>=SweepPolicy.LIMIT)return;automatic=!automatic;lastCaptureError="";auto.setText(automatic?"Pause":"Resume");});
        done.setOnClickListener(v->{if(finishing)return;automatic=false;requested.set(false);finishRequested=true;completionNotice="Stopped early · positions have not passed the stability check";auto.setEnabled(false);capture.setEnabled(false);done.setEnabled(false);gl.queueEvent(()->activity.runOnUiThread(()->{if(!closed&&!analysisPending.get())finish(false);}));});
    }
    /** UI thread. At most one captured photo is being analyzed at any time. */
    public void analyzed(SweepAnalysis.Update update){
        if(closed)return;assessment=update.readiness;lastCaptureError="";
        if(assessment.ready){completionNotice="Enough angles · positions stable";finish(true);return;}
        if(count>=SweepPolicy.LIMIT){completionNotice="Capture limit reached · positions are still uncertain";finish(false);return;}
        if(finishRequested){finish(false);return;}
        capture.setEnabled(true);auto.setText(automatic?"Pause":"Resume");analysisPending.set(false);
    }
    public void analysisFailed(String message){if(closed)return;automatic=false;lastCaptureError="Analysis paused: "+message;auto.setText("Resume");capture.setEnabled(true);analysisPending.set(false);if(finishRequested)finish(false);}
    public String completionNotice(){return completionNotice;}
    private void finish(boolean ready){
        if(closed||finishing)return;finishing=true;automatic=false;requested.set(false);capture.setEnabled(false);auto.setEnabled(false);done.setEnabled(false);
        status.setText(completionNotice+"\n"+(ready?"Opening your grouping for one quick check.":"Opening the available photos. Uncertain positions stay flagged."));
        if(ready){status.setTextColor(0xFFD8EB98);performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);announceForAccessibility(completionNotice);}
        postDelayed(()->{if(!closed)gl.queueEvent(()->activity.runOnUiThread(()->{if(!closed)listener.finished();}));},ready?1200:400);
    }
    public void resume()throws Exception {if(session!=null&&!closed){session.resume();gl.onResume();}}
    public void close(){if(closed)return;closed=true;automatic=false;requested.set(false);gl.onPause();if(session!=null){light.close();try{session.pause();}finally{if(origin!=null)origin.detach();session.close();session=null;}}}
    @Override public void onSurfaceCreated(GL10 ignored,EGLConfig config){try{background.createOnGlThread(activity);textureReady=false;}catch(Exception e){error(e);}}
    @Override public void onSurfaceChanged(GL10 ignored,int width,int height){this.width=width;this.height=height;GLES20.glViewport(0,0,width,height);}
    @Override public void onDrawFrame(GL10 ignored){
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);if(closed||session==null)return;
        try{
            if(!textureReady){session.setCameraTextureName(background.getTextureId());textureReady=true;}
            session.setDisplayGeometry(activity.getWindowManager().getDefaultDisplay().getRotation(),width,height);
            Frame frame=session.update();background.draw(frame);light.update(frame);boolean lightReady=light.ready();com.google.ar.core.Camera camera=frame.getCamera();boolean tracked=camera.getTrackingState()==TrackingState.TRACKING;
            if(origin!=null)tracked&=origin.getTrackingState()==TrackingState.TRACKING;
            float[] relative=origin==null?new float[]{0,0,0}:origin.getPose().inverse().compose(camera.getPose()).getTranslation();
            double[] translation={relative[0],relative[1],relative[2]};
            float[] rotationPose=camera.getPose().getRotationQuaternion(),worldPosition=camera.getPose().getTranslation();
            boolean settled=false;
            if(tracked)settled=keyframes.settled(new double[]{worldPosition[0],worldPosition[1],worldPosition[2]},new double[]{rotationPose[0],rotationPose[1],rotationPose[2],rotationPose[3]},frame.getTimestamp());else keyframes.lostTracking();
            long now=System.currentTimeMillis();if(now-lastStatus>500&&!finishing){lastStatus=now;SweepReadiness.Assessment a=assessment;
                String title=finishRequested?"Checking the last photo…":!tracked?"Waiting for motion tracking":analysisPending.get()?"Checking this angle…":!automatic?"Sweep paused":a.title;
                String advice=finishRequested?"The review will open automatically.":!lastCaptureError.isEmpty()?lastCaptureError:!tracked?"Keep the target in view and move gently. Take photo also works without tracking.":analysisPending.get()?"Keep the arrows still. You can move slowly toward the next angle.":a.guidance;
                if(automatic&&!finishRequested&&tracked&&!analysisPending.get()&&!settled&&keyframes.ready(translation,frame.getTimestamp())){title="Hold steady briefly";advice="Let the phone settle at this angle. I’ll take the photo automatically.";}
                if(!lightReady&&!finishRequested){title=light.on?"Letting the light settle":"Checking the light";advice="The flashlight turns on automatically if needed. It stays steady while you move.";}
                String counts=String.format(Locale.US,"%d photos · %d useful angles · %.0f° span",count,a.angles,a.spanDegrees)+(a.arrows>0?" · "+a.stableArrows+" / "+a.arrows+" stable entries":"");
                final String text=title+"\n"+advice+"\n"+counts,lightText=light.label();activity.runOnUiThread(()->{if(!closed&&!finishing){if(!text.contentEquals(status.getText()))status.setText(text);if(!lightText.contentEquals(lightButton.getText()))lightButton.setText(lightText);}});}
            boolean autoFrame=automatic&&!finishing&&!finishRequested&&lightReady&&tracked&&settled&&count<SweepPolicy.LIMIT&&keyframes.ready(translation,frame.getTimestamp());
            if(!finishing&&!finishRequested&&!analysisPending.get()&&(requested.getAndSet(false)||autoFrame)&&analysisPending.compareAndSet(false,true)){
                // A photo remains useful without VIO. Never attach a stale or
                // untracked pose; those views must use texture registration.
                try{if(tracked&&origin==null)origin=session.createAnchor(camera.getPose());}catch(Exception e){analysisPending.set(false);throw e;}
                try(Image image=frame.acquireCameraImage()){
                    // ARCore's CPU Image uses the Frame timestamp. The Android
                    // Camera2 timestamp has a different convention on this S21
                    // (observed ~25 ms offset), so it must not be compared here.
                    if(Math.abs(image.getTimestamp()-frame.getTimestamp())>2_000_000L)throw new IllegalStateException("Camera timing mismatch; please capture again");
                    Store.View view=new Store.View();view.rawWidth=image.getWidth();view.rawHeight=image.getHeight();view.timestamp=frame.getTimestamp();view.session=sessionId;
                    view.cameraTimestamp=frame.getAndroidCameraTimestamp();
                    CameraIntrinsics intrinsics=camera.getImageIntrinsics();float[] f=intrinsics.getFocalLength(),c=intrinsics.getPrincipalPoint();int[] dims=intrinsics.getImageDimensions();
                    if(dims[0]!=image.getWidth()||dims[1]!=image.getHeight())throw new IllegalStateException("Camera calibration dimensions changed; capture again");
                    view.k=new double[]{f[0],0,c[0],0,f[1],c[1],0,0,1};
                    if(tracked){float[] column=new float[16];origin.getPose().inverse().compose(camera.getPose()).toMatrix(column,0);view.pose=new double[16];for(int i=0;i<4;i++)for(int j=0;j<4;j++)view.pose[4*i+j]=column[4*j+i];}
                    float[] source={0,0,image.getWidth(),0},dest=new float[4];frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS,source,Coordinates2d.VIEW,dest);
                    int rotation=(int)Math.round(Math.toDegrees(Math.atan2(dest[3]-dest[1],dest[2]-dest[0]))/90)*90;view.rotation=(rotation+360)%360;
                    Bitmap raw=bitmap(image),rotated=raw;
                    if(view.rotation!=0){android.graphics.Matrix m=new android.graphics.Matrix();m.postRotate(view.rotation);rotated=Bitmap.createBitmap(raw,0,0,raw.getWidth(),raw.getHeight(),m,true);}
                    Store.Group snapshot;
                    try{store.saveBitmap(group,view,rotated);group.views.add(view);store.save(group);count++;if(tracked&&keyframes.ready(translation,frame.getTimestamp()))keyframes.captured(translation,frame.getTimestamp());snapshot=Store.Group.from(group.json());}finally{if(rotated!=raw)rotated.recycle();raw.recycle();}
                    final boolean hadTracking=tracked;
                    activity.runOnUiThread(()->{if(!closed){capture.setEnabled(false);listener.captured(snapshot);if(!hadTracking)Toast.makeText(activity,"Photo saved without motion tracking. It cannot supply measured sweep angles.",Toast.LENGTH_LONG).show();}});
                }catch(NotYetAvailableException e){analysisPending.set(false);throw new IllegalStateException("Camera frame is not ready; try once more");}
                catch(Exception e){analysisPending.set(false);throw e;}
            }
        }catch(Exception e){error(e);}
    }
    private void error(Exception e){lastCaptureError=e.getMessage()==null?e.toString():e.getMessage();android.util.Log.e("RedwoodArrows","Camera operation failed",e);automatic=false;requested.set(false);activity.runOnUiThread(()->{if(!closed){if(!analysisPending.get())capture.setEnabled(true);done.setEnabled(true);auto.setText("Resume");listener.error(lastCaptureError);if(finishRequested&&!analysisPending.get())finish(false);}});}
    static Bitmap bitmap(Image image){int w=image.getWidth(),h=image.getHeight();int[] pixels=new int[w*h];Image.Plane[] p=image.getPlanes();ByteBuffer y=p[0].getBuffer(),u=p[1].getBuffer(),v=p[2].getBuffer();int yo=y.position(),uo=u.position(),vo=v.position();
        for(int j=0;j<h;j++)for(int i=0;i<w;i++){int yy=(y.get(yo+j*p[0].getRowStride()+i*p[0].getPixelStride())&255)-16;int uu=(u.get(uo+(j/2)*p[1].getRowStride()+(i/2)*p[1].getPixelStride())&255)-128;int vv=(v.get(vo+(j/2)*p[2].getRowStride()+(i/2)*p[2].getPixelStride())&255)-128;int r=(298*yy+409*vv+128)>>8,g=(298*yy-100*uu-208*vv+128)>>8,b=(298*yy+516*uu+128)>>8;pixels[j*w+i]=0xFF000000|(clamp(r)<<16)|(clamp(g)<<8)|clamp(b);}
        return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
    }
    static int clamp(int v){return Math.max(0,Math.min(255,v));}
}
