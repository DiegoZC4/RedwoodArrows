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
import org.opencv.imgproc.LineSegmentDetector;

/** Experimental semantic recognition + image-edge geometry. No automatic IDs,
 * target calibration, saved marks or hit positions are generated here. */
public final class HybridVision {
    static {System.loadLibrary("opencv_java4");}
    private static final int SIZE=320;
    private final Net net;
    public final double threshold;
    public final String modelSha256;

    private static byte[] read(InputStream input,int limit)throws Exception {
        try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1){if(out.size()+n>limit)throw new IOException("Oversized model asset");out.write(buffer,0,n);}
            return out.toByteArray();
        }
    }
    public static HybridVision fromAssets(Context context)throws Exception {
        return new HybridVision(read(context.getAssets().open("hybrid/arrow-recognizer.onnx"),4*1024*1024),
                new JSONObject(new String(read(context.getAssets().open("hybrid/model-card.json"),128*1024),StandardCharsets.UTF_8)));
    }
    public static HybridVision fromDirectory(File directory)throws Exception {
        return new HybridVision(read(new FileInputStream(new File(directory,"arrow-recognizer.onnx")),4*1024*1024),
                new JSONObject(new String(read(new FileInputStream(new File(directory,"model-card.json")),128*1024),StandardCharsets.UTF_8)));
    }
    private HybridVision(byte[] model,JSONObject card)throws Exception {
        Core.setNumThreads(2);
        if(!card.getString("status").equals("trained_experimental")||card.getInt("input_size")!=SIZE||
                !card.getString("onnx_file").equals("arrow-recognizer.onnx")||
                !card.getJSONArray("channels").getString(0).equals("shaft")||
                !card.getJSONArray("channels").getString(1).equals("vanes_nocks"))
            throw new IOException("Unsupported hybrid model");
        threshold=card.getDouble("operating_threshold");
        if(!(threshold>0&&threshold<1))throw new IOException("Invalid hybrid operating point");
        StringBuilder hex=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(model))hex.append(String.format(Locale.US,"%02x",b&255));
        modelSha256=hex.toString();
        if(!modelSha256.equals(card.getString("onnx_sha256")))throw new IOException("Hybrid model checksum mismatch");
        MatOfByte data=new MatOfByte(model);
        try{net=Dnn.readNetFromONNX(data);}finally{data.release();}
        if(net.empty())throw new IOException("Hybrid model could not be loaded");
        net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);net.setPreferableTarget(Dnn.DNN_TARGET_CPU);
    }
    private static Mat owned(List<Mat> mats,Mat mat){mats.add(mat);return mat;}

    /** Caller owns the two-channel 320x320 network output (NCHW flattened). */
    public synchronized float[] networkOutput(Bitmap bitmap){
        List<Mat> mats=new ArrayList<>();
        try{
            Mat rgba=owned(mats,new Mat()),bgr=owned(mats,new Mat()),resized=owned(mats,new Mat());
            Utils.bitmapToMat(bitmap,rgba);Imgproc.cvtColor(rgba,bgr,Imgproc.COLOR_RGBA2BGR);
            double factor=SIZE/(double)Math.max(bitmap.getWidth(),bitmap.getHeight());
            int w=Math.max(1,(int)Math.rint(bitmap.getWidth()*factor)),h=Math.max(1,(int)Math.rint(bitmap.getHeight()*factor));
            Imgproc.resize(bgr,resized,new Size(w,h),0,0,factor<1?Imgproc.INTER_AREA:Imgproc.INTER_LINEAR);
            Mat canvas=owned(mats,new Mat(SIZE,SIZE,CvType.CV_8UC3,new Scalar(127,127,127)));
            Mat roi=owned(mats,canvas.submat(new Rect((SIZE-w)/2,(SIZE-h)/2,w,h)));resized.copyTo(roi);
            Mat blob=owned(mats,Dnn.blobFromImage(canvas,1/255.0,new Size(SIZE,SIZE),new Scalar(0,0,0),true,false));
            net.setInput(blob);Mat output=owned(mats,net.forward());
            if(output.dims()!=4||output.size(0)!=1||output.size(1)!=2||output.size(2)!=SIZE||output.size(3)!=SIZE)
                throw new IllegalArgumentException("Unexpected hybrid network output");
            float[] values=new float[2*SIZE*SIZE];output.get(new int[]{0,0,0,0},values);
            for(float value:values)if(!Float.isFinite(value)||value<-.001||value>1.001)throw new IllegalArgumentException("Invalid hybrid probabilities");
            return values;
        }finally{for(int i=mats.size()-1;i>=0;i--)mats.get(i).release();}
    }

    public synchronized List<double[]> shafts(Bitmap bitmap){
        float[] values=networkOutput(bitmap);
        List<Mat> mats=new ArrayList<>();LineSegmentDetector lsd=Imgproc.createLineSegmentDetector(Imgproc.LSD_REFINE_STD);
        try{
            Mat rgba=owned(mats,new Mat()),bgr=owned(mats,new Mat()),resized=owned(mats,new Mat()),small=owned(mats,new Mat()),lines=owned(mats,new Mat());
            Utils.bitmapToMat(bitmap,rgba);Imgproc.cvtColor(rgba,bgr,Imgproc.COLOR_RGBA2BGR);
            double scale=Math.min(1.,1200.0/Math.max(bitmap.getWidth(),bitmap.getHeight()));
            Imgproc.resize(bgr,resized,new Size(),scale,scale,Imgproc.INTER_AREA);Imgproc.cvtColor(resized,small,Imgproc.COLOR_BGR2GRAY);
            double factor=SIZE/(double)Math.max(bitmap.getWidth(),bitmap.getHeight());
            int w=Math.max(1,(int)Math.rint(bitmap.getWidth()*factor)),h=Math.max(1,(int)Math.rint(bitmap.getHeight()*factor));
            Mat all=owned(mats,new Mat(SIZE*2,SIZE,CvType.CV_32F));all.put(0,0,values);
            Mat crop=owned(mats,all.submat(new Rect((SIZE-w)/2,(SIZE-h)/2,w,h)));
            // Match desktop's two-stage restoration before line fitting.
            Mat full=owned(mats,new Mat()),prob=owned(mats,new Mat());
            Imgproc.resize(crop,full,new Size(bitmap.getWidth(),bitmap.getHeight()),0,0,Imgproc.INTER_LINEAR);
            Imgproc.resize(full,prob,small.size(),0,0,Imgproc.INTER_LINEAR);
            float[] p=new float[prob.rows()*prob.cols()];prob.get(0,0,p);
            lsd.detect(small,lines);List<double[]> edges=new ArrayList<>();
            for(int i=0;i<lines.rows();i++){double[] edge=lines.get(i,0);if(length(edge)>=Math.max(18,Math.max(small.cols(),small.rows())*.03))edges.add(edge);}
            Collections.sort(edges,(a,b)->Double.compare(length(b),length(a)));
            if(edges.size()>180)edges=new ArrayList<>(edges.subList(0,180));
            List<Candidate> proposals=new ArrayList<>();
            for(int i=0;i<edges.size();i++)for(int j=i+1;j<edges.size();j++){
                double[] a=edges.get(i),b=edges.get(j),la=line(a),lb=line(b);double alen=length(a),blen=length(b);
                double dot=la[0]*lb[0]+la[1]*lb[1];if(Math.abs(dot)<.999)continue;
                double width=Math.abs(la[0]*(b[0]+b[2])/2+la[1]*(b[1]+b[3])/2+la[2]);if(width<1.3||width>16)continue;
                double ax=(a[2]-a[0])/alen,ay=(a[3]-a[1])/alen;
                double bt0=(b[0]-a[0])*ax+(b[1]-a[1])*ay,bt1=(b[2]-a[0])*ax+(b[3]-a[1])*ay;
                double lo=Math.max(0,Math.min(bt0,bt1)),hi=Math.min(alen,Math.max(bt0,bt1));if(hi-lo<Math.max(16,.35*Math.min(alen,blen)))continue;
                double[] center={a[0]+lo*ax,a[1]+lo*ay,a[0]+hi*ax,a[1]+hi*ay};
                for(int k=0;k<4;k+=2){double offset=-(center[k]*lb[0]+center[k+1]*lb[1]+lb[2])/dot;center[k]+=offset*la[0]/2;center[k+1]+=offset*la[1]/2;}
                double[] normal=line(center),support=new double[31];double mean=0;
                for(int k=0;k<31;k++){double t=.05+.9*k/30,x=center[0]+t*(center[2]-center[0]),y=center[1]+t*(center[3]-center[1]);
                    for(double offset:new double[]{-1.5,0,1.5})support[k]=Math.max(support[k],sample(p,prob.cols(),prob.rows(),x+offset*normal[0],y+offset*normal[1]));mean+=support[k];}
                mean/=31;Arrays.sort(support);double lower=(support[7]+support[8])/2;
                if(mean<threshold||lower<threshold*.55)continue;
                for(int k=0;k<4;k++)center[k]/=scale;
                proposals.add(new Candidate(center,width/scale,mean*Math.sqrt((hi-lo)/scale)));
            }
            Collections.sort(proposals,(a,b)->Double.compare(b.score,a.score));List<Candidate> kept=new ArrayList<>();
            for(Candidate c:proposals){boolean duplicate=false;double[] lc=line(c.segment);
                for(Candidate old:kept){double[] ll=line(old.segment);if(Math.abs(lc[0]*ll[0]+lc[1]*ll[1])<.999)continue;
                    double error=(Math.abs(c.segment[0]*ll[0]+c.segment[1]*ll[1]+ll[2])+Math.abs(c.segment[2]*ll[0]+c.segment[3]*ll[1]+ll[2]))/2;
                    if(error<=Math.max(1.8/scale,.6*Math.min(c.width,old.width))){duplicate=true;break;}}
                if(!duplicate)kept.add(c);
            }
            List<double[]> result=new ArrayList<>();for(int i=0;i<Math.min(24,kept.size());i++)result.add(kept.get(i).segment);return result;
        }finally{lsd.clear();for(int i=mats.size()-1;i>=0;i--)mats.get(i).release();}
    }
    private static final class Candidate {double[] segment;double width,score;Candidate(double[] s,double w,double v){segment=s;width=w;score=v;}}
    private static double length(double[] s){return Math.hypot(s[2]-s[0],s[3]-s[1]);}
    private static double[] line(double[] s){return Geometry.line(new double[]{s[0],s[1]},new double[]{s[2],s[3]});}
    private static float pixel(float[] p,int w,int h,int x,int y){return x>=0&&y>=0&&x<w&&y<h?p[y*w+x]:0;}
    private static double sample(float[] p,int w,int h,double x,double y){
        // OpenCV INTER_LINEAR remap uses a 1/32-pixel interpolation table.
        x=Math.rint((float)x*32)/32.;y=Math.rint((float)y*32)/32.;int ix=(int)Math.floor(x),iy=(int)Math.floor(y);double fx=x-ix,fy=y-iy;
        return (1-fy)*((1-fx)*pixel(p,w,h,ix,iy)+fx*pixel(p,w,h,ix+1,iy))+fy*((1-fx)*pixel(p,w,h,ix,iy+1)+fx*pixel(p,w,h,ix+1,iy+1));
    }
}
