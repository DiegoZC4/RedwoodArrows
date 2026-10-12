package org.redwoodarrows;

import android.content.Context;
import android.graphics.*;
import android.util.LruCache;
import android.view.View;
import java.io.InputStream;

/** Offline artwork for identification only. Never used for target-plane measurements. */
public final class TargetThumbnail extends View {
    // Explicit small cache; no private photos or heavyweight model allocations.
    private static final LruCache<String,Bitmap> CACHE=new LruCache<String,Bitmap>(2*1024*1024){
        @Override protected int sizeOf(String key,Bitmap b){return b.getByteCount();}
    };
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final TargetPreset preset;
    private final Bitmap artwork;
    public TargetThumbnail(Context context,TargetPreset preset){
        super(context);this.preset=preset;String path=TargetDesigns.asset(preset);Bitmap loaded=CACHE.get(path);
        if(loaded==null)try(InputStream in=context.getAssets().open(path)){
            loaded=BitmapFactory.decodeStream(in);if(loaded!=null)CACHE.put(path,loaded);
        }catch(Exception ignored){}
        artwork=loaded;setContentDescription(preset.name+(loaded==null?(preset.circular()?" · schematic preview of one spot":" · illustration not bundled"):" · maker's target preview"));
    }
    public boolean hasArtwork(){return artwork!=null;}
    public String presetId(){return preset.id;}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);float padding=8*getResources().getDisplayMetrics().density;
        paint.setColor(0xFFF9F8F3);c.drawRoundRect(0,0,getWidth(),getHeight(),padding,padding,paint);
        if(artwork==null){
            if(preset.circular()){
                float radius=Math.max(1,Math.min(getWidth(),getHeight())/2f-padding);float x=getWidth()/2f,y=getHeight()/2f;
                for(int i=0;i<preset.fillRadii.length;i++){paint.setStyle(Paint.Style.FILL);paint.setColor(preset.fillColors[i]);c.drawCircle(x,y,(float)(radius*preset.fillRadii[i]),paint);}
                paint.setColor(0xFF777777);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);
                for(double r:preset.rings)c.drawCircle(x,y,(float)(radius*r),paint);paint.setStyle(Paint.Style.FILL);
            }else{paint.setColor(0xFF53635C);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(12*getResources().getDisplayMetrics().scaledDensity);c.drawText("Preview unavailable",getWidth()/2f,getHeight()/2f,paint);}
            return;
        }
        float scale=Math.min((getWidth()-2*padding)/artwork.getWidth(),(getHeight()-2*padding)/artwork.getHeight());
        float w=artwork.getWidth()*scale,h=artwork.getHeight()*scale;
        c.drawBitmap(artwork,null,new RectF((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2),paint);
    }
}
