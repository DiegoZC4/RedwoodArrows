package org.redwoodarrows;

import java.util.*;

/** Bounded keyframes from visual-inertial camera translation, not integrated
 * accelerometer samples. Rotation in place does not produce another keyframe. */
public final class SweepPolicy {
    // A resource bound, never a declaration that enough evidence was collected.
    public static final int LIMIT=24;
    private double[] last;
    private long lastTime;
    private volatile int count;
    private final List<double[]> captured=new ArrayList<>();
    private double[] previousPosition,previousRotation;
    private long previousTime,steadySince;
    /** Wait for a brief low-motion interval instead of shooting mid-sidestep. */
    public boolean settled(double[] position,double[] quaternion,long timeNanos){
        if(!finite(position,3)||!finite(quaternion,4)||timeNanos<previousTime){lostTracking();return false;}
        // LATEST_CAMERA_IMAGE may repeat a camera timestamp at display FPS.
        // A duplicate is not motion and must not restart the settling window.
        if(timeNanos==previousTime)return false;
        boolean slow=false;
        if(previousPosition!=null){
            double dt=(timeNanos-previousTime)/1e9,d=0,dot=0,na=0,nb=0;
            for(int i=0;i<3;i++)d+=Math.pow(position[i]-previousPosition[i],2);
            for(int i=0;i<4;i++){dot+=quaternion[i]*previousRotation[i];na+=quaternion[i]*quaternion[i];nb+=previousRotation[i]*previousRotation[i];}
            double degrees=na*nb>0?Math.toDegrees(2*Math.acos(Math.min(1,Math.abs(dot)/Math.sqrt(na*nb)))):180;
            slow=dt<=.2&&Math.sqrt(d)/dt<=.08&&degrees/dt<=12;
        }
        if(!slow)steadySince=timeNanos;
        previousPosition=position.clone();previousRotation=quaternion.clone();previousTime=timeNanos;
        return slow&&timeNanos-steadySince>=250_000_000L;
    }
    public void lostTracking(){previousPosition=null;previousRotation=null;previousTime=0;steadySince=0;}
    private static boolean finite(double[] p,int n){if(p==null||p.length!=n)return false;for(double x:p)if(!Double.isFinite(x))return false;return true;}
    public boolean ready(double[] translation,long timeNanos){
        if(translation==null||translation.length!=3||count>=LIMIT)return false;
        for(double v:translation)if(!Double.isFinite(v))return false;
        if(last==null)return true;
        if(timeNanos<=lastTime||timeNanos-lastTime<700_000_000L)return false;
        double d=0;for(int i=0;i<3;i++)d+=Math.pow(translation[i]-last[i],2);
        if(d<.04*.04)return false;
        for(double[] old:captured){double distance=0;for(int i=0;i<3;i++)distance+=Math.pow(translation[i]-old[i],2);if(distance<.02*.02)return false;}
        return true;
    }
    public void captured(double[] translation,long timeNanos){
        if(!ready(translation,timeNanos))throw new IllegalArgumentException("Not a distinct tracked keyframe");
        last=translation.clone();captured.add(last);lastTime=timeNanos;count++;
    }
    public int count(){return count;}
}
