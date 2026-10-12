package org.redwoodarrows;

import java.util.*;
import org.apache.commons.math3.fitting.leastsquares.*;
import org.apache.commons.math3.linear.*;
import org.apache.commons.math3.util.Pair;

/** Target-plane geometry. Coordinates are x-right, y-up in target radii.
 * Line equations adapt htkseason/Archery-Scoring, Apache-2.0 (see THIRD_PARTY.md).
 * No assumption is made about arrow count or whether a hit is inside the face.
 */
public final class Geometry {
    private Geometry() {}
    public static final class Calibration {
        public double[] h;
        public double rms;
        public String source;
        public boolean reviewed;
        public Calibration(double[] h, double rms, String source) {
            this.h=h; this.rms=rms; this.source=source;
        }
        public double[] map(double x,double y) { return project(h,x,y); }
    }
    public static double[] project(double[] h,double x,double y) {
        double z=h[6]*x+h[7]*y+h[8];
        if (Math.abs(z)<1e-12) throw new IllegalArgumentException("Point is on the projective horizon");
        return new double[]{(h[0]*x+h[1]*y+h[2])/z,(h[3]*x+h[4]*y+h[5])/z};
    }
    public static double[] mul(double[] a,double[] b) {
        double[] out=new double[9];
        for(int i=0;i<3;i++) for(int j=0;j<3;j++) for(int k=0;k<3;k++) out[3*i+j]+=a[3*i+k]*b[3*k+j];
        return out;
    }
    public static double[] inverse(double[] a) {
        double det=a[0]*(a[4]*a[8]-a[5]*a[7])-a[1]*(a[3]*a[8]-a[5]*a[6])+a[2]*(a[3]*a[7]-a[4]*a[6]);
        if(!Double.isFinite(det)||Math.abs(det)<1e-14) throw new IllegalArgumentException("Singular calibration");
        return new double[]{(a[4]*a[8]-a[5]*a[7])/det,(a[2]*a[7]-a[1]*a[8])/det,(a[1]*a[5]-a[2]*a[4])/det,
            (a[5]*a[6]-a[3]*a[8])/det,(a[0]*a[8]-a[2]*a[6])/det,(a[2]*a[3]-a[0]*a[5])/det,
            (a[3]*a[7]-a[4]*a[6])/det,(a[1]*a[6]-a[0]*a[7])/det,(a[0]*a[4]-a[1]*a[3])/det};
    }
    /** Four paper corners in TL, TR, BR, BL order; no scoring outline is inferred.
     * Coordinates use half the sheet's longest side. Four fitted points supply no
     * independent error estimate, so RMS is NaN rather than a misleading zero.
     */
    public static Calibration calibratePaper(List<double[]> corners,double widthMm,double heightMm,int imageWidth,int imageHeight){
        if(corners.size()!=4||!Double.isFinite(widthMm)||!Double.isFinite(heightMm)||widthMm<=0||heightMm<=0)
            throw new IllegalArgumentException("Mark four untrimmed paper corners: top left, top right, bottom right, bottom left");
        double imageScale=Math.max(imageWidth,imageHeight),scale=Math.max(widthMm,heightMm)/2;
        if(imageScale<=0)throw new IllegalArgumentException("Invalid photo dimensions");
        double w=widthMm/(2*scale),h=heightMm/(2*scale),area=0;
        double[][] target={{-w,h},{w,h},{w,-h},{-w,-h}},design=new double[8][8];double[] rhs=new double[8];
        for(int i=0;i<4;i++){
            double[] a=corners.get(i),b=corners.get((i+1)%4),c=corners.get((i+2)%4);
            if(a.length!=2||!Double.isFinite(a[0])||!Double.isFinite(a[1]))throw new IllegalArgumentException("Invalid corner");
            double cross=(b[0]-a[0])*(c[1]-b[1])-(b[1]-a[1])*(c[0]-b[0]);
            if(cross<imageScale*imageScale*.0001)throw new IllegalArgumentException("Corners must run clockwise from top left around a visible, non-edge-on sheet");
            if(Math.hypot(b[0]-a[0],b[1]-a[1])<imageScale*.02)throw new IllegalArgumentException("Sheet is too small or edge-on for calibration");
            area+=a[0]*b[1]-b[0]*a[1];double x=a[0]/imageScale,y=a[1]/imageScale,u=target[i][0],v=target[i][1];
            design[2*i]=new double[]{x,y,1,0,0,0,-u*x,-u*y};design[2*i+1]=new double[]{0,0,0,x,y,1,-v*x,-v*y};rhs[2*i]=u;rhs[2*i+1]=v;
        }
        if(area<imageScale*imageScale*.01)throw new IllegalArgumentException("Show more of the sheet before calibrating");
        SingularValueDecomposition svd=new SingularValueDecomposition(new Array2DRowRealMatrix(design));
        if(svd.getConditionNumber()>1e7)throw new IllegalArgumentException("Paper calibration is unstable");
        double[] x=svd.getSolver().solve(new ArrayRealVector(rhs)).toArray();
        double[] map={x[0]/imageScale,x[1]/imageScale,x[2],x[3]/imageScale,x[4]/imageScale,x[5],x[6]/imageScale,x[7]/imageScale,1};
        double[] back=inverse(map);double first=Double.NaN;
        for(double[] t:target){double z=back[6]*t[0]+back[7]*t[1]+back[8];if(!Double.isFinite(z)||Math.abs(z)<1e-8||Double.isFinite(first)&&first*z<=0)throw new IllegalArgumentException("Paper crosses a projective horizon");first=z;}
        return new Calibration(map,Double.NaN,"four paper corners · no independent fit-error estimate");
    }
    /** Full projective fit to two concentric circles, not an affine ellipse flattening.
     * First outer point defines the positive vertical axis; alignment between photos
     * must use texture registration, not independently chosen image-up axes.
     */
    public static Calibration calibrate(List<double[]> outer,List<double[]> inner,double innerRadius,int width,int height) {
        if(outer.size()<6||inner.size()<6) throw new IllegalArgumentException("Mark at least six points around each circle");
        double s=Math.max(width,height), cx=width/2.0,cy=height/2.0;
        double[][] pts=new double[outer.size()+inner.size()][3];
        int n=0;
        for(double[] p:outer) pts[n++]=new double[]{(p[0]-cx)/s,(p[1]-cy)/s,1};
        for(double[] p:inner) pts[n++]=new double[]{(p[0]-cx)/s,(p[1]-cy)/s,innerRadius};
        // Algebraic conic gives a well-conditioned affine initialization.
        double[][] design=new double[outer.size()][6];
        for(int i=0;i<outer.size();i++) {double u=pts[i][0],v=pts[i][1]; design[i]=new double[]{u*u,u*v,v*v,u,v,1};}
        SingularValueDecomposition svd=new SingularValueDecomposition(new Array2DRowRealMatrix(design));
        double[] c=svd.getV().getColumn(5);
        if(c[0]<0) for(int i=0;i<6;i++) c[i]=-c[i];
        double det=4*c[0]*c[2]-c[1]*c[1];
        if(det<=1e-12) throw new IllegalArgumentException("Outer points do not form an ellipse; spread them around the rim");
        double ex=(c[1]*c[4]-2*c[2]*c[3])/det,ey=(c[1]*c[3]-2*c[0]*c[4])/det;
        double k=-(c[0]*ex*ex+c[1]*ex*ey+c[2]*ey*ey+c[3]*ex+c[4]*ey+c[5]);
        if(k<=0) throw new IllegalArgumentException("Invalid outer ellipse");
        RealMatrix q=new Array2DRowRealMatrix(new double[][]{{c[0]/k,c[1]/(2*k)},{c[1]/(2*k),c[2]/k}});
        EigenDecomposition ed=new EigenDecomposition(q);
        if(ed.getRealEigenvalue(0)<=0||ed.getRealEigenvalue(1)<=0) throw new IllegalArgumentException("Invalid ellipse");
        double aspect=Math.sqrt(Math.min(ed.getRealEigenvalue(0),ed.getRealEigenvalue(1))/Math.max(ed.getRealEigenvalue(0),ed.getRealEigenvalue(1)));
        if(aspect<.12)throw new IllegalArgumentException("Target is almost edge-on; use a more front-facing reference photo");
        RealMatrix root=ed.getV().multiply(MatrixUtils.createRealDiagonalMatrix(new double[]{Math.sqrt(ed.getRealEigenvalue(0)),Math.sqrt(ed.getRealEigenvalue(1))})).multiply(ed.getVT());
        double[] init={root.getEntry(0,0),root.getEntry(0,1),-root.getEntry(0,0)*ex-root.getEntry(0,1)*ey,
            -root.getEntry(1,0),-root.getEntry(1,1),root.getEntry(1,0)*ex+root.getEntry(1,1)*ey,0,0,1};
        // The distinct generalized eigenvector of the two conics is their
        // physical center. Its polar is the target plane's line at infinity.
        // This supplies perspective initialization even for a tiny Hunter bull.
        try{double[] projective=concentricInitializer(pts,outer.size());if(radialCost(projective,pts)<radialCost(init,pts))init=projective;}catch(RuntimeException ignored){}
        double[] top=project(init,pts[0][0],pts[0][1]);
        double angle=Math.PI/2-Math.atan2(top[1],top[0]);
        init=mul(new double[]{Math.cos(angle),-Math.sin(angle),0,Math.sin(angle),Math.cos(angle),0,0,0,1},init);
        double[] start=Arrays.copyOf(init,8),target=new double[pts.length+2];
        for(int i=0;i<pts.length;i++) target[i]=pts[i][2];
        target[pts.length]=0; target[pts.length+1]=1;
        MultivariateJacobianFunction fn=p->{
            double[] value=new double[pts.length+2]; double[][] jac=new double[value.length][8];
            for(int i=0;i<pts.length;i++) {
                double u=pts[i][0],v=pts[i][1],z=p.getEntry(6)*u+p.getEntry(7)*v+1;
                double x=(p.getEntry(0)*u+p.getEntry(1)*v+p.getEntry(2))/z;
                double y=(p.getEntry(3)*u+p.getEntry(4)*v+p.getEntry(5))/z;
                double r=Math.max(1e-10,Math.hypot(x,y)); value[i]=r;
                jac[i]=new double[]{x*u/(r*z),x*v/(r*z),x/(r*z),y*u/(r*z),y*v/(r*z),y/(r*z),-r*u/z,-r*v/z};
            }
            double u=pts[0][0],v=pts[0][1],z=p.getEntry(6)*u+p.getEntry(7)*v+1;
            double x=(p.getEntry(0)*u+p.getEntry(1)*v+p.getEntry(2))/z,y=(p.getEntry(3)*u+p.getEntry(4)*v+p.getEntry(5))/z;
            value[pts.length]=x;value[pts.length+1]=y;
            jac[pts.length]=new double[]{u/z,v/z,1/z,0,0,0,-x*u/z,-x*v/z};
            jac[pts.length+1]=new double[]{0,0,0,u/z,v/z,1/z,-y*u/z,-y*v/z};
            return new Pair<RealVector,RealMatrix>(new ArrayRealVector(value),new Array2DRowRealMatrix(jac));
        };
        LeastSquaresOptimizer.Optimum fit=new LevenbergMarquardtOptimizer().optimize(new LeastSquaresBuilder().start(start).target(target).model(fn).maxEvaluations(600).maxIterations(300).build());
        double[] h=Arrays.copyOf(fit.getPoint().toArray(),9);h[8]=1;
        double sum=0;
        for(double[] p:pts) {
            double z=h[6]*p[0]+h[7]*p[1]+1;
            if(z<0.06) throw new IllegalArgumentException("Too oblique or unstable calibration; take a more front-facing view");
            double[] t=project(h,p[0],p[1]);double e=Math.hypot(t[0],t[1])-p[2];sum+=e*e;
        }
        double rms=Math.sqrt(sum/pts.length);
        if(!Double.isFinite(rms)||rms>.045) throw new IllegalArgumentException("Ring points disagree; check the selected circle and target type");
        // Reject points concentrated on one arc even when algebraic residual is tiny.
        for(int part=0;part<2;part++) {
            boolean[] quadrants=new boolean[4];int count=0;
            for(int i=part==0?0:outer.size();i<(part==0?outer.size():pts.length);i++) {
                double[] p=project(h,pts[i][0],pts[i][1]);int qi=(p[0]>=0?1:0)+(p[1]>=0?2:0);quadrants[qi]=true;
            }
            for(boolean b:quadrants) if(b) count++;
            if(count<4) throw new IllegalArgumentException("Use points on all four sides of both circles");
        }
        h=mul(h,new double[]{1/s,0,-cx/s,0,1/s,-cy/s,0,0,1});inverse(h);
        return new Calibration(h,rms,"two-ring projective fit");
    }
    private static double radialCost(double[] h,double[][] points){double sum=0;for(double[] p:points){double[] xy=project(h,p[0],p[1]);double e=Math.hypot(xy[0],xy[1])-p[2];sum+=e*e;}return sum;}
    private static RealMatrix conicMatrix(double[][] points,int start,int end){double[][] design=new double[end-start][6];for(int i=start;i<end;i++){double u=points[i][0],v=points[i][1];design[i-start]=new double[]{u*u,u*v,v*v,u,v,1};}double[] c=new SingularValueDecomposition(new Array2DRowRealMatrix(design)).getV().getColumn(5);if(c[0]<0)for(int i=0;i<6;i++)c[i]=-c[i];return new Array2DRowRealMatrix(new double[][]{{c[0],c[1]/2,c[3]/2},{c[1]/2,c[2],c[4]/2},{c[3]/2,c[4]/2,c[5]}});}
    private static double[] concentricInitializer(double[][] points,int outerCount){
        RealMatrix outer=conicMatrix(points,0,outerCount),inner=conicMatrix(points,outerCount,points.length);
        EigenDecomposition eigen=new EigenDecomposition(new LUDecomposition(outer).getSolver().solve(inner));
        double best=Double.POSITIVE_INFINITY;int unique=0;for(int i=0;i<3;i++)for(int j=i+1;j<3;j++){double difference=Math.abs(eigen.getRealEigenvalue(i)-eigen.getRealEigenvalue(j));if(difference<best){best=difference;unique=3-i-j;}}
        RealVector center=eigen.getEigenvector(unique),line=outer.operate(center);if(Math.abs(line.getEntry(2))<1e-9)throw new IllegalArgumentException("Conic initializer is unstable");
        double[] rectify={1,0,0,0,1,0,line.getEntry(0)/line.getEntry(2),line.getEntry(1)/line.getEntry(2),1};
        double[][] rectified=new double[outerCount][3];for(int i=0;i<outerCount;i++){double denominator=rectify[6]*points[i][0]+rectify[7]*points[i][1]+1;if(denominator<.05)throw new IllegalArgumentException("Invalid conic perspective");double[] xy=project(rectify,points[i][0],points[i][1]);rectified[i]=new double[]{xy[0],xy[1],1};}
        RealMatrix c=conicMatrix(rectified,0,outerCount),a=c.getSubMatrix(0,1,0,1);RealVector offset=new ArrayRealVector(new double[]{c.getEntry(0,2),c.getEntry(1,2)}),ctr=new LUDecomposition(a).getSolver().solve(offset).mapMultiply(-1);double factor=-(ctr.dotProduct(a.operate(ctr))+2*offset.dotProduct(ctr)+c.getEntry(2,2));
        EigenDecomposition ed=new EigenDecomposition(a.scalarMultiply(1/factor));if(ed.getRealEigenvalue(0)<=0||ed.getRealEigenvalue(1)<=0)throw new IllegalArgumentException("Invalid affine conic");RealMatrix root=ed.getV().multiply(MatrixUtils.createRealDiagonalMatrix(new double[]{Math.sqrt(ed.getRealEigenvalue(0)),Math.sqrt(ed.getRealEigenvalue(1))})).multiply(ed.getVT());double x=ctr.getEntry(0),y=ctr.getEntry(1);
        double[] affine={root.getEntry(0,0),root.getEntry(0,1),-root.getEntry(0,0)*x-root.getEntry(0,1)*y,-root.getEntry(1,0),-root.getEntry(1,1),root.getEntry(1,0)*x+root.getEntry(1,1)*y,0,0,1};return mul(affine,rectify);
    }
    public static double[] line(double[] a,double[] b) {
        double dx=b[0]-a[0],dy=b[1]-a[1],len=Math.hypot(dx,dy);
        if(len<1e-8) throw new IllegalArgumentException("Shaft endpoints are too close");
        return new double[]{dy/len,-dx/len,(b[0]*a[1]-a[0]*b[1])/len};
    }
    public static final class Intersection {
        public final double x,y,rms,condition,angleDegrees;
        public final int views;
        Intersection(double x,double y,double rms,double condition,double angle,int views) {this.x=x;this.y=y;this.rms=rms;this.condition=condition;this.angleDegrees=angle;this.views=views;}
    }
    /** All observations must refer to ONE physical arrow from distinct views. */
    public static Intersection intersect(List<double[]> lines) {
        if(lines.size()<2) throw new IllegalArgumentException("Use two or more views of this arrow");
        double[][] a=new double[lines.size()][2];double[] b=new double[lines.size()];double maxAngle=0;
        for(int i=0;i<lines.size();i++) {
            double[] l=lines.get(i);a[i][0]=l[0];a[i][1]=l[1];b[i]=-l[2];
            for(int j=0;j<i;j++) maxAngle=Math.max(maxAngle,Math.toDegrees(Math.acos(Math.min(1,Math.abs(l[0]*lines.get(j)[0]+l[1]*lines.get(j)[1])))));
        }
        SingularValueDecomposition svd=new SingularValueDecomposition(new Array2DRowRealMatrix(a));
        double condition=svd.getConditionNumber();
        if(maxAngle<4||condition>30) throw new IllegalArgumentException("Views are too similar; move sideways, not just rotate the phone");
        double[] xy=svd.getSolver().solve(new ArrayRealVector(b)).toArray();double sum=0;
        for(double[] l:lines) {double e=l[0]*xy[0]+l[1]*xy[1]+l[2];sum+=e*e;}
        double rms=Math.sqrt(sum/lines.size());
        if(rms>.045) throw new IllegalArgumentException("Views disagree; check arrow IDs, shaft marks and target alignment");
        return new Intersection(xy[0],xy[1],rms,condition,maxAngle,lines.size());
    }
    public static double[] mean(List<double[]> points) {
        double x=0,y=0;for(double[] p:points){x+=p[0];y+=p[1];}
        return points.isEmpty()?new double[]{0,0}:new double[]{x/points.size(),y/points.size()};
    }
    /** Robust Huber center; third coordinate, when present, is an evidence
     * weight, not a calibrated probability. Residuals are not accuracy bounds. */
    public static double[] robustMean(List<double[]> points,double bandwidth){
        if(points.isEmpty())return new double[]{0,0};
        double best=Double.POSITIVE_INFINITY;double[] center=points.get(0).clone();
        for(double[] p:points){double cost=0;for(double[] q:points)cost+=Math.hypot(p[0]-q[0],p[1]-q[1]);if(cost<best){best=cost;center=p.clone();}}
        for(int iteration=0;iteration<10;iteration++){
            double x=0,y=0,total=0;
            for(double[] p:points){double weight=Math.max(.05,p.length>2?p[2]:1)*Math.min(1,bandwidth/Math.max(1e-12,Math.hypot(p[0]-center[0],p[1]-center[1])));x+=p[0]*weight;y+=p[1]*weight;total+=weight;}
            center[0]=x/total;center[1]=y/total;
        }
        return new double[]{center[0],center[1]};
    }
    public static double associationGate(Calibration calibration,int width,int height){
        double x=width/2.,y=height/2.,step=width*.01;
        double[] p=calibration.map(x,y),px=calibration.map(x+step,y),py=calibration.map(x,y+step);
        return Math.max(.01,Math.min(.15,Math.max(Math.hypot(px[0]-p[0],px[1]-p[1]),Math.hypot(py[0]-p[0],py[1]-p[1]))));
    }
    /** Circles have no printed orientation. Give automatic fits a predictable
     * reference-image up axis without changing any radial fit or saved fit. */
    public static Calibration imageUp(Calibration calibration){
        double[] center=project(inverse(calibration.h),0,0);
        double[] up=calibration.map(center[0],center[1]-10);
        double angle=Math.PI/2-Math.atan2(up[1],up[0]);
        double co=Math.cos(angle),si=Math.sin(angle);
        Calibration result=new Calibration(mul(new double[]{co,-si,0,si,co,0,0,0,1},calibration.h),calibration.rms,calibration.source+" · reference image-up");
        result.reviewed=calibration.reviewed;return result;
    }
    public static double spread(List<double[]> points) {
        double d=0;for(int i=0;i<points.size();i++)for(int j=0;j<i;j++)d=Math.max(d,Math.hypot(points.get(i)[0]-points.get(j)[0],points.get(i)[1]-points.get(j)[1]));return d;
    }
    public static double[] identity4() {return new double[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};}
    /** Row-major rigid transform matrices throughout this class. */
    public static double[] mul4(double[] a,double[] b) {double[] c=new double[16];for(int i=0;i<4;i++)for(int j=0;j<4;j++)for(int k=0;k<4;k++)c[i*4+j]+=a[i*4+k]*b[k*4+j];return c;}
    public static double[] inverseRigid(double[] m) {double[] r=identity4();for(int i=0;i<3;i++)for(int j=0;j<3;j++)r[i*4+j]=m[j*4+i];for(int i=0;i<3;i++)r[i*4+3]=-(r[i*4]*m[3]+r[i*4+1]*m[7]+r[i*4+2]*m[11]);return r;}
    public static double[] imageRotation(int degrees,int width,int height) {
        switch((degrees%360+360)%360) {
        case 90:return new double[]{0,-1,height-1,1,0,0,0,0,1};
        case 180:return new double[]{-1,0,width-1,0,-1,height-1,0,0,1};
        case 270:return new double[]{0,1,0,-1,0,width-1,0,0,1};
        default:return new double[]{1,0,0,0,1,0,0,0,1};
        }
    }
    /** Metric plane pose from a calibrated photograph and that frame's intrinsics. */
    public static double[] targetPose(double[] h,double[] k,double[] imageRotation,double[] cameraPose,double radiusMeters) {
        double[] m=mul(inverse(k),mul(inverse(imageRotation),inverse(h)));
        double n1=Math.sqrt(m[0]*m[0]+m[3]*m[3]+m[6]*m[6]),n2=Math.sqrt(m[1]*m[1]+m[4]*m[4]+m[7]*m[7]);
        double scale=(n1+n2)/2;if(m[8]<0)scale=-scale;
        double[] x={m[0]/scale,m[3]/scale,m[6]/scale},y={m[1]/scale,m[4]/scale,m[7]/scale};normalize(x);
        double dot=x[0]*y[0]+x[1]*y[1]+x[2]*y[2];for(int i=0;i<3;i++)y[i]-=dot*x[i];normalize(y);
        double[] z={x[1]*y[2]-x[2]*y[1],x[2]*y[0]-x[0]*y[2],x[0]*y[1]-x[1]*y[0]};
        double[] tc={x[0],y[0],z[0],m[2]/scale*radiusMeters,x[1],y[1],z[1],m[5]/scale*radiusMeters,x[2],y[2],z[2],m[8]/scale*radiusMeters,0,0,0,1};
        double[] flip={1,0,0,0,0,-1,0,0,0,0,-1,0,0,0,0,1};
        return mul4(cameraPose,mul4(flip,tc));
    }
    public static double[] motionHomography(double[] plane,double[] cameraPose,double[] k,double[] imageRotation,double radiusMeters) {
        double[] flip={1,0,0,0,0,-1,0,0,0,0,-1,0,0,0,0,1};
        double[] m=mul4(flip,mul4(inverseRigid(cameraPose),plane));
        if(m[11]<=0) throw new IllegalArgumentException("Target is behind the camera");
        return inverse(mul(imageRotation,mul(k,new double[]{m[0]*radiusMeters,m[1]*radiusMeters,m[3],m[4]*radiusMeters,m[5]*radiusMeters,m[7],m[8]*radiusMeters,m[9]*radiusMeters,m[11]})));
    }
    private static void normalize(double[] v) {double n=Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);if(n<1e-9)throw new IllegalArgumentException("Degenerate pose");for(int i=0;i<3;i++)v[i]/=n;}
}
