package org.redwoodarrows;

/** Small geometric quality checks shared with deterministic host tests. */
public final class SweepEvidence {
    private SweepEvidence(){}
    public static boolean faceVisible(double[] h,double[][] outline,int width,int height){
        if(width<=0||height<=0)return false;
        double[] inverse=Geometry.inverse(h);
        for(double[] p:outline){double[] q=Geometry.project(inverse,p[0],p[1]);if(!Double.isFinite(q[0])||!Double.isFinite(q[1])||q[0]<width*.01||q[0]>width*.99||q[1]<height*.01||q[1]>height*.99)return false;}
        return true;
    }
    public static boolean motionAgrees(double[] aligned,double[] motion,double tolerance){
        if(!(tolerance>0&&Double.isFinite(tolerance)))return false;
        double[] inverse=Geometry.inverse(aligned);
        for(double x:new double[]{-.6,0,.6})for(double y:new double[]{-.6,0,.6}){
            double[] image=Geometry.project(inverse,x,y),other=Geometry.project(motion,image[0],image[1]);
            double d=Math.hypot(other[0]-x,other[1]-y);if(!Double.isFinite(d)||d>tolerance)return false;
        }
        return true;
    }
}
