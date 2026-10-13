package org.redwoodarrows;

import android.graphics.Bitmap;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.LineSegmentDetector;
import org.opencv.features2d.*;
import org.opencv.calib3d.Calib3d;
import java.util.*;

/** Classical suggestions, never a claim of neural arrow detection or ground truth. */
public final class Vision {
    static {System.loadLibrary("opencv_java4");}
    private Vision(){}
    private static Mat gray(Bitmap b){Mat rgba=new Mat(),g=new Mat();Utils.bitmapToMat(b,rgba);Imgproc.cvtColor(rgba,g,Imgproc.COLOR_RGBA2GRAY);rgba.release();return g;}
    public static final class Rings {
        public List<double[]> outer,inner;public Geometry.Calibration calibration;
    }
    public static Rings rings(Bitmap bitmap,String type){
        return ringCandidates(bitmap,type,false).get(0);
    }
    /** Every plausible face, not just the largest one. Selection stays explicit. */
    public static List<Rings> ringCandidates(Bitmap bitmap,String type){return ringCandidates(bitmap,type,true);}
    private static List<Rings> ringCandidates(Bitmap bitmap,String type,boolean all){
        if(!type.equals("Field")&&!type.equals("Hunter"))throw new IllegalArgumentException("Mark this design's rings manually");
        double innerRadius=type.equals("Field")?.6:.2;
        List<Rings> results=new ArrayList<>();
        Mat original=gray(bitmap),g=new Mat(),binary=new Mat(),edges=new Mat(),hierarchy=new Mat();List<MatOfPoint> contours=new ArrayList<>();
        double scale=Math.min(1,1200.0/Math.max(bitmap.getWidth(),bitmap.getHeight()));
        Mat kernel=Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,new Size(7,7));
        Mat opening=Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,new Size(11,11));
        try{
            Imgproc.resize(original,g,new Size(),scale,scale,Imgproc.INTER_AREA);Imgproc.GaussianBlur(g,g,new Size(5,5),0);
            Imgproc.threshold(g,binary,0,255,Imgproc.THRESH_BINARY_INV|Imgproc.THRESH_OTSU);
            // Remove narrow shafts that otherwise split the white band into
            // disconnected regions. This is proposal-only, not hole detection.
            Imgproc.morphologyEx(binary,binary,Imgproc.MORPH_OPEN,opening);Imgproc.morphologyEx(binary,binary,Imgproc.MORPH_CLOSE,kernel);
            Imgproc.findContours(binary,contours,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_NONE);
            // Shafts can split the filled white band. Edge arcs supply extra
            // ellipse seeds for explicit selection, still verified against BOTH
            // physical color boundaries below. Keep unattended legacy behavior.
            if(all){List<MatOfPoint> arcs=new ArrayList<>();Imgproc.Canny(g,edges,30,90);Imgproc.findContours(edges,arcs,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_NONE);contours.addAll(arcs);}
            List<RotatedRect> ellipses=new ArrayList<>();
            for(MatOfPoint c:contours){if(c.rows()<50)continue;MatOfPoint2f f=new MatOfPoint2f(c.toArray());RotatedRect e;try{e=Imgproc.fitEllipse(f);}finally{f.release();}
                double small=Math.min(e.size.width,e.size.height),large=Math.max(e.size.width,e.size.height);
                if(small<75||large>Math.max(g.rows(),g.cols())*.82||small/large<.28||e.center.x<0||e.center.x>g.cols()||e.center.y<0||e.center.y>g.rows())continue;
                double[] errors=new double[c.rows()];int n=0;double a=Math.toRadians(e.angle),co=Math.cos(a),si=Math.sin(a);
                for(Point p:c.toArray()){double dx=p.x-e.center.x,dy=p.y-e.center.y;errors[n++]=Math.abs(Math.hypot((dx*co+dy*si)/(e.size.width/2),(-dx*si+dy*co)/(e.size.height/2))-1);}
                Arrays.sort(errors);if(errors[errors.length/2]<.065)ellipses.add(e);
            }
            Collections.sort(ellipses,(a,b)->Double.compare(b.size.area(),a.size.area()));
            byte[] pixels=new byte[g.rows()*g.cols()];g.get(0,0,pixels);
            // Hunter's .6 scoring line often survives when shafts obscure the
            // outer rim. Let either line seed the same physical .2/1 pair.
            double[] seedRadii=type.equals("Field")?(all?new double[]{1,.6}:new double[]{1}):(all?new double[]{.2,1.0/3}:new double[]{.2});
            for(RotatedRect e:ellipses)for(double seed:seedRadii){try{
                Rings result=new Rings();result.inner=edgePoints(e,seed,-1,pixels,g.cols(),g.rows(),scale);
                result.outer=edgePoints(e,seed/innerRadius,type.equals("Hunter")?2:1,pixels,g.cols(),g.rows(),scale);
                if(result.outer.size()<12||result.inner.size()<12)continue;
                if(all){result.outer=ringConsensus(result.outer);result.inner=ringConsensus(result.inner);}
                Geometry.Calibration fit=Geometry.calibrate(result.outer,result.inner,innerRadius,bitmap.getWidth(),bitmap.getHeight());
                // Trim worn-hole outliers once, preserving the orientation reference.
                List<double[]> out=trim(result.outer,fit,1),in=trim(result.inner,fit,innerRadius);
                if(out.size()>=12&&in.size()>=12)fit=Geometry.calibrate(out,in,innerRadius,bitmap.getWidth(),bitmap.getHeight());
                if(fit.rms>.025)continue;fit.source="automatic two-ring proposal";result.outer=out;result.inner=in;result.calibration=Geometry.imageUp(fit);
                double[] center=Geometry.project(Geometry.inverse(result.calibration.h),0,0);boolean duplicate=false;
                for(Rings prior:results){double[] q=prior.calibration.map(center[0],center[1]);if(Math.hypot(q[0],q[1])<.15){duplicate=true;break;}}
                if(!duplicate)results.add(result);
                if(!all||results.size()>=16)return results;
            }catch(RuntimeException ignored){}}
            if(!results.isEmpty())return results;
            throw new IllegalArgumentException("The worn rings need manual marks. Use Rings, then mark clean rim sections on all four sides.");
        }finally{original.release();g.release();binary.release();edges.release();hierarchy.release();kernel.release();opening.release();for(Mat m:contours)m.release();}
    }
    /** Robust conic consensus removes shaft/hole edges before the strict
     * two-circle projective fit. No arrow labels or target center are used.
     * A majority of each sampled rim must agree; missing arcs are not invented. */
    private static List<double[]> ringConsensus(List<double[]> points){
        Random random=new Random(419+points.size());List<double[]> best=new ArrayList<>();double bestError=Double.POSITIVE_INFINITY;
        for(int trial=0;trial<80;trial++){
            Set<Integer> used=new HashSet<>();Point[] sample=new Point[6];for(int i=0;i<sample.length;i++){int at;do{at=random.nextInt(points.size());}while(!used.add(at));double[] p=points.get(at);sample[i]=new Point(p[0],p[1]);}
            MatOfPoint2f input=new MatOfPoint2f(sample);try{
                RotatedRect e=Imgproc.fitEllipse(input);double small=Math.min(e.size.width,e.size.height),large=Math.max(e.size.width,e.size.height);
                if(!Double.isFinite(large)||small<10||large>20000||small/large<.12)continue;
                double a=Math.toRadians(e.angle),co=Math.cos(a),si=Math.sin(a),error=0;List<double[]> accepted=new ArrayList<>();
                for(double[] p:points){double x=p[0]-e.center.x,y=p[1]-e.center.y;double d=Math.abs(Math.hypot((x*co+y*si)/(e.size.width/2),(-x*si+y*co)/(e.size.height/2))-1);if(d<.04){accepted.add(p);error+=d*d;}}
                if(accepted.size()>best.size()||accepted.size()==best.size()&&error<bestError){best=accepted;bestError=error;}
            }catch(RuntimeException ignored){}finally{input.release();}
        }
        if(best.size()<Math.max(12,points.size()*.6))throw new IllegalArgumentException("Too little clean ring evidence");return best;
    }
    private static List<double[]> trim(List<double[]> points,Geometry.Calibration fit,double radius){List<double[]> out=new ArrayList<>();for(int i=0;i<points.size();i++){double[] p=points.get(i),q=fit.map(p[0],p[1]);if(i==0||Math.abs(Math.hypot(q[0],q[1])-radius)<.027)out.add(p);}return out;}
    private static List<double[]> edgePoints(RotatedRect e,double radius,int sign,byte[] pixels,int w,int h,double scale){
        List<double[]> points=new ArrayList<>();double angle=Math.toRadians(e.angle),co=Math.cos(angle),si=Math.sin(angle);
        for(int i=0;i<48;i++){double t=-Math.PI/2+i*Math.PI*2/48,ux=Math.cos(t)*e.size.width/2,uy=Math.sin(t)*e.size.height/2,dx=co*ux-si*uy,dy=si*ux+co*uy,len=Math.hypot(dx,dy);
            double best=-1,bestRadius=0;
            for(double r=radius*.79;r<=radius*1.21;r+=1.0/len){double x=e.center.x+r*dx,y=e.center.y+r*dy;double delta=3/len;
                double inside=sample(pixels,w,h,x-delta*dx,y-delta*dy),outside=sample(pixels,w,h,x+delta*dx,y+delta*dy);
                if(inside<0||outside<0)continue;
                // Hunter's outer rim is a thin white line on black paper,
                // not Field's black-to-white area boundary. Fit its bright
                // ridge center, not an arbitrary edge of the printed line.
                double contrast=sign==2?sample(pixels,w,h,x,y)-Math.max(inside,outside):sign*(outside-inside);double score=contrast-35*Math.abs(r-radius)/radius;
                if(contrast>(sign==2?12:24)&&score>best){best=score;bestRadius=r;}
            }
            if(best>(sign==2?10:18))points.add(new double[]{(e.center.x+bestRadius*dx)/scale,(e.center.y+bestRadius*dy)/scale});
        }return points;
    }
    private static double sample(byte[] p,int w,int h,double x,double y){int ix=(int)Math.round(x),iy=(int)Math.round(y);if(ix<1||iy<1||ix>=w-1||iy>=h-1)return -1;double sum=0;for(int j=-1;j<=1;j++)for(int i=-1;i<=1;i++)sum+=p[(iy+j)*w+ix+i]&255;return sum/9;}
    public static List<double[]> shafts(Bitmap bitmap,Geometry.Calibration calibration){
        Mat original=gray(bitmap),g=new Mat(),lines=new Mat();LineSegmentDetector detector=Imgproc.createLineSegmentDetector(Imgproc.LSD_REFINE_STD);
        double scale=Math.min(1,1200.0/Math.max(bitmap.getWidth(),bitmap.getHeight()));List<double[]> raw=new ArrayList<>(),out=new ArrayList<>();
        try{Imgproc.resize(original,g,new Size(),scale,scale,Imgproc.INTER_AREA);detector.detect(g,lines);
            for(int i=0;i<lines.rows();i++){double[] l=lines.get(i,0);double length=Math.hypot(l[2]-l[0],l[3]-l[1]);if(length<Math.max(g.rows(),g.cols())*.065)continue;for(int j=0;j<4;j++)l[j]/=scale;raw.add(l);}
            Collections.sort(raw,(a,b)->Double.compare(length(b),length(a)));
            // Pair the two long edges of a shaft into a centerline. Unpaired edges
            // are not promoted to hits; all suggestions still require review.
            for(int i=0;i<Math.min(raw.size(),100);i++)for(int j=i+1;j<Math.min(raw.size(),100);j++){
                double[] a=raw.get(i),b=raw.get(j),la=Geometry.line(new double[]{a[0],a[1]},new double[]{a[2],a[3]}),lb=Geometry.line(new double[]{b[0],b[1]},new double[]{b[2],b[3]});
                if(Math.abs(la[0]*lb[0]+la[1]*lb[1])<.999)continue;
                double gap=Math.abs(la[0]*(b[0]+b[2])/2+la[1]*(b[1]+b[3])/2+la[2]);if(gap<2/scale||gap>16/scale)continue;
                double ax=(a[2]-a[0])/length(a),ay=(a[3]-a[1])/length(a);
                double b0=(b[0]-a[0])*ax+(b[1]-a[1])*ay,b1=(b[2]-a[0])*ax+(b[3]-a[1])*ay;
                double lo=Math.max(0,Math.min(b0,b1)),hi=Math.min(length(a),Math.max(b0,b1));if(hi-lo<.45*Math.min(length(a),length(b)))continue;
                double signed=la[0]*(b[0]+b[2])/2+la[1]*(b[1]+b[3])/2+la[2];
                double[] center={a[0]+lo*ax+la[0]*signed/2,a[1]+lo*ay+la[1]*signed/2,a[0]+hi*ax+la[0]*signed/2,a[1]+hi*ay+la[1]*signed/2};
                if(calibration!=null){double[] q=calibration.map(center[0],center[1]),r=calibration.map(center[2],center[3]);if(Math.min(Math.hypot(q[0],q[1]),Math.hypot(r[0],r[1]))>2.5)continue;}
                boolean duplicate=false;for(double[] existing:out){double[] le=Geometry.line(new double[]{existing[0],existing[1]},new double[]{existing[2],existing[3]});if(Math.abs(le[0]*la[0]+le[1]*la[1])>.998&&Math.abs(le[0]*center[0]+le[1]*center[1]+le[2])<12/scale){duplicate=true;break;}}
                if(!duplicate)out.add(center);
            }
            Collections.sort(out,(a,b)->Double.compare(length(b),length(a)));return new ArrayList<>(out.subList(0,Math.min(24,out.size())));
        }finally{original.release();g.release();lines.release();detector.clear();}
    }
    static double length(double[] line){return Math.hypot(line[2]-line[0],line[3]-line[1]);}
    public static Geometry.Calibration align(Bitmap reference,Geometry.Calibration referenceCal,Bitmap current,double[] motionPrior){
        return align(reference,referenceCal,current,motionPrior,TargetCatalog.defaultPreset());
    }
    public static Geometry.Calibration align(Bitmap reference,Geometry.Calibration referenceCal,Bitmap current,double[] motionPrior,TargetPreset preset){
        if(motionPrior!=null){try{return alignImpl(reference,referenceCal,current,motionPrior,preset);}catch(IllegalArgumentException ignored){}}
        try{return alignImpl(reference,referenceCal,current,null,preset);}
        catch(IllegalArgumentException raw){
            if(preset.family.equals("Field")){
                try{return alignRectified(reference,referenceCal,current);}
                catch(IllegalArgumentException ignored){}
            }
            throw raw;
        }
    }
    /** Internal perspective normalization only; no warped photograph is shown.
     * Independent ring fits propose the normalization, while actual paper
     * texture must establish the correspondence. Never match on arrow labels. */
    private static Geometry.Calibration alignRectified(Bitmap reference,Geometry.Calibration referenceCal,Bitmap current){
        Geometry.Calibration currentCal=rings(current,"Field").calibration;
        int size=768;double scale=size/2.4;
        double[] canvas={scale,0,size/2.,0,-scale,size/2.,0,0,1};
        Mat refGray=gray(reference),curGray=gray(current),ref=new Mat(),cur=new Mat();
        Mat wr=new Mat(3,3,CvType.CV_64F),wc=new Mat(3,3,CvType.CV_64F),mask=Mat.zeros(size,size,CvType.CV_8U),dr=new Mat(),dc=new Mat();
        MatOfKeyPoint kr=new MatOfKeyPoint(),kc=new MatOfKeyPoint();
        MatOfPoint2f from=new MatOfPoint2f(),to=new MatOfPoint2f();Mat inlierMask=new Mat(),hmat=new Mat();
        List<MatOfDMatch> forward=new ArrayList<>(),backward=new ArrayList<>();
        Feature2D detector=SIFT.create(4500);BFMatcher matcher=BFMatcher.create(Core.NORM_L2,false);
        try{
            wr.put(0,0,Geometry.mul(canvas,referenceCal.h));wc.put(0,0,Geometry.mul(canvas,currentCal.h));
            Imgproc.warpPerspective(refGray,ref,wr,new Size(size,size));Imgproc.warpPerspective(curGray,cur,wc,new Size(size,size));
            Imgproc.circle(mask,new Point(size/2.,size/2.),(int)Math.rint(scale*1.05),new Scalar(255),-1);
            detector.detectAndCompute(ref,mask,kr,dr);detector.detectAndCompute(cur,mask,kc,dc);
            if(dr.empty()||dc.empty())throw new IllegalArgumentException("Insufficient rectified paper texture");
            matcher.knnMatch(dc,dr,forward,2);matcher.knnMatch(dr,dc,backward,2);
            Map<Integer,Integer> reverse=new HashMap<>();
            for(MatOfDMatch row:backward){DMatch[] d=row.toArray();if(d.length==2&&d[0].distance<.76*d[1].distance)reverse.put(d[0].queryIdx,d[0].trainIdx);}
            KeyPoint[] rk=kr.toArray(),ck=kc.toArray();List<Point> fp=new ArrayList<>(),tp=new ArrayList<>();
            for(MatOfDMatch row:forward){DMatch[] d=row.toArray();if(d.length!=2||d[0].distance>=.76*d[1].distance)continue;
                Integer mutual=reverse.get(d[0].trainIdx);if(mutual==null||mutual!=d[0].queryIdx)continue;
                fp.add(ck[d[0].queryIdx].pt);tp.add(rk[d[0].trainIdx].pt);
            }
            if(fp.size()<18)throw new IllegalArgumentException("Too few mutual rectified texture matches");
            from.fromList(fp);to.fromList(tp);hmat.release();hmat=Calib3d.findHomography(from,to,Calib3d.RANSAC,3,inlierMask,3000,.995);
            if(hmat.empty())throw new IllegalArgumentException("Rectified registration failed");
            double[] hc=new double[9];hmat.get(0,0,hc);int count=0;double sum=0,minX=9,minY=9,maxX=-9,maxY=-9;boolean[] quadrants=new boolean[4];
            for(int i=0;i<fp.size();i++)if(inlierMask.get(i,0)[0]!=0){
                count++;Point a=fp.get(i),b=tp.get(i);double[] pred=Geometry.project(hc,a.x,a.y);sum+=Math.pow(pred[0]-b.x,2)+Math.pow(pred[1]-b.y,2);
                double x=(b.x-size/2.)/scale,y=(size/2.-b.y)/scale;quadrants[(x>=0?1:0)+(y>=0?2:0)]=true;
                minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
            }
            int spread=0;for(boolean q:quadrants)if(q)spread++;
            if(count<18||count<fp.size()*.25||spread<3||(maxX-minX)*(maxY-minY)<.6||Math.sqrt(sum/count)>2.5)
                throw new IllegalArgumentException("Rectified matches lack spatial support");
            double[] plane=Geometry.mul(Geometry.inverse(canvas),Geometry.mul(hc,canvas));
            double[] c=Geometry.project(plane,0,0),x=Geometry.project(plane,1,0),y=Geometry.project(plane,0,1);
            double dx=x[0]-c[0],dy=x[1]-c[1],ex=y[0]-c[0],ey=y[1]-c[1],nx=Math.hypot(dx,dy),ny=Math.hypot(ex,ey);
            if(Math.hypot(c[0],c[1])>=.15||nx<=.8||nx>=1.2||ny<=.8||ny>=1.2||Math.abs(dx*ex+dy*ey)>=.25||dx*ey-dy*ex<=0)
                throw new IllegalArgumentException("The two normalized target faces disagree");
            double[] h=Geometry.mul(plane,currentCal.h);Geometry.inverse(h);
            return new Geometry.Calibration(h,referenceCal.rms,"rectified texture alignment · "+count+" inliers · "+String.format(Locale.US,"%.1f",Math.sqrt(sum/count))+" px");
        }finally{
            for(Mat m:forward)m.release();for(Mat m:backward)m.release();
            for(Mat m:new Mat[]{refGray,curGray,ref,cur,wr,wc,mask,dr,dc,kr,kc,from,to,inlierMask,hmat})m.release();detector.clear();matcher.clear();
        }
    }
    private static Geometry.Calibration alignImpl(Bitmap reference,Geometry.Calibration referenceCal,Bitmap current,double[] motionPrior,TargetPreset preset){
        double sr=Math.min(1,1400.0/Math.max(reference.getWidth(),reference.getHeight())),sc=Math.min(1,1400.0/Math.max(current.getWidth(),current.getHeight()));
        Mat rg0=gray(reference),cg0=gray(current),rg=new Mat(),cg=new Mat(),mask=new Mat(),dr=new Mat(),dc=new Mat(),empty=new Mat();
        MatOfKeyPoint kr=new MatOfKeyPoint(),kc=new MatOfKeyPoint();List<MatOfDMatch> matches=new ArrayList<>();
        // SIFT is supplied by OpenCV. It tolerates the scale/viewpoint changes
        // in handheld range photos better than binary ORB descriptors.
        Feature2D detector=SIFT.create(4500);BFMatcher matcher=BFMatcher.create(Core.NORM_L2,false);
        MatOfPoint2f from=new MatOfPoint2f(),to=new MatOfPoint2f();Mat inlierMask=new Mat(),homography=new Mat();
        try{
            Imgproc.resize(rg0,rg,new Size(),sr,sr,Imgproc.INTER_AREA);Imgproc.resize(cg0,cg,new Size(),sc,sc,Imgproc.INTER_AREA);
            mask=Mat.zeros(rg.size(),CvType.CV_8UC1);double[] inverse=Geometry.inverse(referenceCal.h);double[][] outline=preset.outline(preset.circular()?1.08:1.03);Point[] boundary=new Point[outline.length];for(int i=0;i<outline.length;i++){double[] xy=Geometry.project(inverse,outline[i][0],outline[i][1]);boundary[i]=new Point(xy[0]*sr,xy[1]*sr);}MatOfPoint polygon=new MatOfPoint(boundary);try{Imgproc.fillConvexPoly(mask,polygon,new Scalar(255));}finally{polygon.release();}
            detector.detectAndCompute(rg,mask,kr,dr);detector.detectAndCompute(cg,empty,kc,dc);
            if(dr.empty()||dc.empty())throw new IllegalArgumentException("Not enough paper texture to align these views");
            matcher.knnMatch(dc,dr,matches,2);KeyPoint[] rk=kr.toArray(),ck=kc.toArray();List<Point> fp=new ArrayList<>(),tp=new ArrayList<>();Set<Integer> used=new HashSet<>();
            double[] targetToReference=Geometry.inverse(referenceCal.h);
            for(MatOfDMatch row:matches){DMatch[] d=row.toArray();if(d.length<2||d[0].distance>=.76*d[1].distance||!used.add(d[0].trainIdx))continue;
                Point a=ck[d[0].queryIdx].pt,b=rk[d[0].trainIdx].pt;
                if(motionPrior!=null){double[] q=Geometry.project(motionPrior,a.x/sc,a.y/sc);q=Geometry.project(targetToReference,q[0],q[1]);if(Math.hypot(q[0]-b.x/sr,q[1]-b.y/sr)>Math.max(90,reference.getWidth()*.1))continue;}
                fp.add(a);tp.add(b);
            }
            if(fp.size()<18)throw new IllegalArgumentException("Too few matching target features; use closer-spaced viewpoints of the same end");
            from.fromList(fp);to.fromList(tp);homography=Calib3d.findHomography(from,to,Calib3d.RANSAC,3,inlierMask,3000,.995);
            if(homography.empty())throw new IllegalArgumentException("Target registration failed");double[] hs=new double[9];homography.get(0,0,hs);
            int inliers=0;boolean[] quadrants=new boolean[4];double sum=0,minX=9,maxX=-9,minY=9,maxY=-9;
            for(int i=0;i<fp.size();i++)if(inlierMask.get(i,0)[0]!=0){inliers++;Point a=fp.get(i),b=tp.get(i);double[] pred=Geometry.project(hs,a.x,a.y);sum+=Math.pow(pred[0]-b.x,2)+Math.pow(pred[1]-b.y,2);double[] q=referenceCal.map(b.x/sr,b.y/sr);quadrants[(q[0]>=0?1:0)+(q[1]>=0?2:0)]=true;minX=Math.min(minX,q[0]);maxX=Math.max(maxX,q[0]);minY=Math.min(minY,q[1]);maxY=Math.max(maxY,q[1]);}
            int spread=0;for(boolean q:quadrants)if(q)spread++;
            if(inliers<18||inliers<fp.size()*.25||spread<3||(maxX-minX)*(maxY-minY)<.6*preset.halfWidth()*preset.halfHeight())throw new IllegalArgumentException("Matches do not cover enough of the target for safe alignment");
            double error=Math.sqrt(sum/inliers);if(error>2.5)throw new IllegalArgumentException("Photo alignment is too inconsistent");
            double[] h=Geometry.mul(referenceCal.h,Geometry.mul(new double[]{1/sr,0,0,0,1/sr,0,0,0,1},Geometry.mul(hs,new double[]{sc,0,0,0,sc,0,0,0,1})));
            Geometry.inverse(h);Geometry.Calibration c=new Geometry.Calibration(h,referenceCal.rms,"texture alignment · "+inliers+" inliers · "+String.format(Locale.US,"%.1f",error)+" px"+(motionPrior!=null?" · motion prior":""));return c;
        }finally{rg0.release();cg0.release();rg.release();cg.release();mask.release();dr.release();dc.release();empty.release();kr.release();kc.release();from.release();to.release();inlierMask.release();homography.release();for(Mat m:matches)m.release();detector.clear();matcher.clear();}
    }
    public static double[] motionPrior(Store.Group group,Store.View reference,Store.View current){
        if(reference.pose==null||current.pose==null||reference.k==null||current.k==null||reference.calibration==null||reference.session.isEmpty()||!reference.session.equals(current.session))return null;
        double[] plane=Geometry.targetPose(reference.calibration.h,reference.k,reference.rotationMatrix(),reference.pose,group.radiusMm()/1000);
        return Geometry.motionHomography(plane,current.pose,current.k,current.rotationMatrix(),group.radiusMm()/1000);
    }
}
