package org.redwoodarrows;

import android.content.Context;
import android.graphics.Bitmap;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;

/** Pixel-only entry-center network plus OpenCV tiling and subpixel geometry.
 * It never reads annotations, arrow counts, target presets or saved groups. */
public final class ImpactVision {
    static {System.loadLibrary("opencv_java4");}
    private final Net net;
    public final int size;
    public final double threshold;
    public final String modelSha256;
    public static final int REVIEW_WIDTH=600;

    public static final class Result {
        public final int width,height;
        /** Two channels, NCHW, in the 600-pixel-wide review coordinate system. */
        public final float[] probabilities;
        /** x, y in original upright bitmap pixels; confidence. Not arrow IDs. */
        public final List<double[]> points;
        Result(int w,int h,float[] p,List<double[]> hits){width=w;height=h;probabilities=p;points=hits;}
    }
    private static byte[] read(InputStream input,int limit)throws Exception {
        try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1){if(out.size()+n>limit)throw new IOException("Oversized impact asset");out.write(buffer,0,n);}
            return out.toByteArray();
        }
    }
    public static ImpactVision fromAssets(Context context)throws Exception {
        return new ImpactVision(read(context.getAssets().open("impact/impact-center.onnx"),70_000_000),
            new JSONObject(new String(read(context.getAssets().open("impact/model-card.json"),256*1024),StandardCharsets.UTF_8)));
    }
    public static ImpactVision fromDirectory(File directory)throws Exception {
        return new ImpactVision(read(new FileInputStream(new File(directory,"impact-center.onnx")),70_000_000),
            new JSONObject(new String(read(new FileInputStream(new File(directory,"model-card.json")),256*1024),StandardCharsets.UTF_8)));
    }
    private ImpactVision(byte[] model,JSONObject card)throws Exception {
        Core.setNumThreads(2);
        size=card.getInt("input_size");threshold=card.getDouble("operating_threshold");
        String architecture=card.getString("architecture");
        if(card.getInt("schema")!=2||!card.getString("status").equals("trained_experimental")||
            !(architecture.equals("redwood-mobilenetv3-impact-shaft-v2")||architecture.equals("redwood-mobilenetv3-impact-shaft-v2-mean2"))||
            !card.getString("onnx_file").equals("impact-center.onnx")||card.getInt("review_width")!=REVIEW_WIDTH||
            card.getInt("tile_overlap")!=64||card.getInt("peak_radius")!=3||
            card.getJSONArray("channels").length()!=2||
            !card.getJSONArray("channels").getString(0).equals("impact_center")||
            !card.getJSONArray("channels").getString(1).equals("shaft")||
            !(size==256||size==320||size==384||size==512)||!(threshold>0&&threshold<1))
            throw new IOException("Unsupported impact model or operating point");
        StringBuilder hex=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(model))hex.append(String.format(Locale.US,"%02x",b&255));
        modelSha256=hex.toString();
        if(!modelSha256.equals(card.getString("onnx_sha256")))throw new IOException("Impact model checksum mismatch");
        MatOfByte data=new MatOfByte(model);
        try{net=Dnn.readNetFromONNX(data);}finally{data.release();}
        if(net.empty())throw new IOException("Impact model could not be loaded");
        net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);net.setPreferableTarget(Dnn.DNN_TARGET_CPU);
    }
    private static Mat owned(List<Mat> mats,Mat mat){mats.add(mat);return mat;}
    static int[] positions(int length,int size){
        if(length<=size)return new int[]{0};
        int n=(int)Math.ceil((length-size)/(double)(size-64))+1;
        int[] result=new int[n];
        for(int i=0;i<n;i++)result[i]=(int)Math.rint(i*(length-size)/(double)(n-1));
        return result;
    }
    /** The bitmap must already be EXIF-upright, as Store's imported images are. */
    public synchronized Result detect(Bitmap bitmap){
        int w=REVIEW_WIDTH,h=(int)Math.rint(bitmap.getHeight()*w/(double)bitmap.getWidth());
        if(h<60||h>4800)throw new IllegalArgumentException("Unsupported photograph aspect ratio");
        int cw=Math.max(w,size),ch=Math.max(h,size),plane=cw*ch;
        List<Mat> mats=new ArrayList<>();
        try{
            Mat rgba=owned(mats,new Mat()),bgr=owned(mats,new Mat()),small=owned(mats,new Mat());
            Utils.bitmapToMat(bitmap,rgba);Imgproc.cvtColor(rgba,bgr,Imgproc.COLOR_RGBA2BGR);
            Imgproc.resize(bgr,small,new Size(w,h),0,0,Imgproc.INTER_AREA);
            Mat canvas=owned(mats,new Mat(ch,cw,CvType.CV_8UC3,new Scalar(127,127,127)));
            Mat region=owned(mats,canvas.submat(new Rect(0,0,w,h)));small.copyTo(region);
            float[] sum=new float[2*plane],weights=new float[plane],window=new float[size*size],ramp=new float[size];
            for(int i=0;i<size;i++)ramp[i]=(float)Math.max(.1,.5-.5*Math.cos(2*Math.PI*i/(size-1)));
            for(int y=0;y<size;y++)for(int x=0;x<size;x++)window[y*size+x]=ramp[y]*ramp[x];
            for(int top:positions(ch,size))for(int left:positions(cw,size)){
                Mat tile=canvas.submat(new Rect(left,top,size,size)),blob=null,output=null;
                try{
                    blob=Dnn.blobFromImage(tile,1/255.0,new Size(size,size),new Scalar(0,0,0),true,false);
                    net.setInput(blob);output=net.forward();
                    if(output.dims()!=4||output.size(0)!=1||output.size(1)!=2||output.size(2)!=size||output.size(3)!=size)
                        throw new IllegalArgumentException("Unexpected impact network output");
                    float[] p=new float[2*size*size];output.get(new int[]{0,0,0,0},p);
                    for(float value:p)if(!Float.isFinite(value)||value<-.001||value>1.001)throw new IllegalArgumentException("Invalid impact probability");
                    for(int y=0;y<size;y++)for(int x=0;x<size;x++){
                        int src=y*size+x,dst=(top+y)*cw+left+x;float weight=window[src];
                        weights[dst]+=weight;sum[dst]+=p[src]*weight;sum[plane+dst]+=p[size*size+src]*weight;
                    }
                }finally{if(output!=null)output.release();if(blob!=null)blob.release();tile.release();}
            }
            float[] result=new float[2*w*h];
            for(int c=0;c<2;c++)for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                int pos=y*cw+x;result[c*w*h+y*w+x]=sum[c*plane+pos]/Math.max(weights[pos],1e-8f);
            }
            List<double[]> hits=peaks(result,w,h,threshold);
            for(double[] hit:hits){hit[0]*=bitmap.getWidth()/(double)w;hit[1]*=bitmap.getHeight()/(double)h;}
            return new Result(w,h,result,hits);
        }finally{for(int i=mats.size()-1;i>=0;i--)mats.get(i).release();}
    }
    static List<double[]> peaks(float[] heat,int w,int h,double threshold){
        Mat src=new Mat(h,w,CvType.CV_32F),maxima=new Mat(),kernel=Mat.ones(7,7,CvType.CV_8U);
        try{
            src.put(0,0,Arrays.copyOf(heat,w*h));Imgproc.dilate(src,maxima,kernel);
            float[] maximum=new float[w*h];maxima.get(0,0,maximum);
            List<Integer> indices=new ArrayList<>();
            for(int i=0;i<w*h;i++)if(heat[i]>=maximum[i]-1e-7f&&heat[i]>=threshold)indices.add(i);
            // Stable sorting retains row-major tie order, matching the Python evaluator.
            Collections.sort(indices,(a,b)->Float.compare(heat[b],heat[a]));
            List<double[]> result=new ArrayList<>();
            for(int i:indices){
                int x=i%w,y=i/w;boolean duplicate=false;
                for(double[] old:result)if((x-old[0])*(x-old[0])+(y-old[1])*(y-old[1])<=9){duplicate=true;break;}
                if(duplicate)continue;
                double px=x,py=y;
                if(x>0&&x<w-1&&y>0&&y<h-1){
                    px+=offset(heat[i-1],heat[i],heat[i+1]);py+=offset(heat[i-w],heat[i],heat[i+w]);
                }
                result.add(new double[]{px,py,heat[i]});if(result.size()>=64)break;
            }
            return result;
        }finally{src.release();maxima.release();kernel.release();}
    }
    private static double offset(float a,float b,float c){
        double l=Math.log(Math.max(a,1e-7)),m=Math.log(Math.max(b,1e-7)),r=Math.log(Math.max(c,1e-7)),d=l-2*m+r;
        return d < -1e-7 ? Math.max(-.5,Math.min(.5,.5*(l-r)/d)):0;
    }
}
