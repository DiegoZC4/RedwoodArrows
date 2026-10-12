package org.redwoodarrows;

import java.nio.ByteBuffer;

/** Small live-image meter and debounced continuous-light policy, no ML or I/O. */
public final class TorchPolicy {
    public enum Mode {AUTO,ON,OFF}
    public static final long WARMUP_MS=750,DARK_HOLD_MS=750,MAX_SAMPLE_GAP_MS=750;
    public static final class Brightness {
        public final int p80,p95,samples;public final double mean;
        Brightness(int p80,int p95,int samples,double mean){this.p80=p80;this.p95=p95;this.samples=samples;this.mean=mean;}
        /** Camera Y levels, not estimated lux or a promise of usable exposure. */
        public boolean dark(int iso,long exposureNs){return p80<76||(p80<140&&iso>=800&&exposureNs>=16_000_000L);}
    }
    /** At most 32×24 pixels, central 70% of the image, honoring row/pixel stride. */
    public static Brightness measure(ByteBuffer y,int width,int height,int rowStride,int pixelStride){
        if(y==null||width<2||height<2||rowStride<(long)(width-1)*pixelStride+1||pixelStride<1)throw new IllegalArgumentException("Invalid image plane");
        int offset=y.position();long end=offset+(long)(height-1)*rowStride+(long)(width-1)*pixelStride;
        if(end>=y.limit())throw new IllegalArgumentException("Short image plane");
        int[] histogram=new int[256];int sum=0,nx=Math.min(32,width),ny=Math.min(24,height),n=nx*ny;
        for(int j=0;j<ny;j++)for(int i=0;i<nx;i++){
            int x=Math.min(width-1,(int)(width*(.15+.70*(i+.5)/nx))),v=Math.min(height-1,(int)(height*(.15+.70*(j+.5)/ny)));
            int value=y.get(offset+v*rowStride+x*pixelStride)&255;histogram[value]++;sum+=value;
        }
        int p80=-1,p95=0,total=0;for(int i=0;i<256;i++){total+=histogram[i];if(total>=Math.ceil(.8*n)&&p80<0)p80=i;if(total>=Math.ceil(.95*n)){p95=i;break;}}
        return new Brightness(p80,p95,n,(double)sum/n);
    }
    private Mode mode=Mode.AUTO;private boolean on,checked,pending;
    private long first=-1,last=-1,darkSince=-1;private int darkSamples;
    public Mode mode(){return mode;}
    public boolean wantsOn(){return on;}
    public boolean pending(){return mode==Mode.AUTO&&!on&&(!checked||pending);}
    public void mode(Mode next){
        if(next==null)throw new IllegalArgumentException("Choose Auto, On or Off");if(next==mode)return;
        mode=next;first=last=darkSince=-1;darkSamples=0;checked=pending=false;
        // Switching On to Auto keeps the light: lit frames cannot reveal ambient darkness.
        if(next!=Mode.AUTO)on=next==Mode.ON;
    }
    public void observe(Brightness brightness,int iso,long exposureNs,long timestampMs){
        if(mode!=Mode.AUTO||on||brightness==null||timestampMs<0||timestampMs<=last)return;
        if(first<0)first=timestampMs;
        if(last>=0&&timestampMs-last>MAX_SAMPLE_GAP_MS){darkSince=-1;darkSamples=0;}
        last=timestampMs;pending=brightness.dark(iso,exposureNs);
        if(timestampMs-first<WARMUP_MS)return;checked=true;
        if(!pending){darkSince=-1;darkSamples=0;return;}
        if(darkSince<0)darkSince=timestampMs;darkSamples++;
        if(darkSamples>=3&&timestampMs-darkSince>=DARK_HOLD_MS){on=true;pending=false;}
    }
    public void stop(){mode=Mode.OFF;on=false;pending=false;checked=true;}
}
