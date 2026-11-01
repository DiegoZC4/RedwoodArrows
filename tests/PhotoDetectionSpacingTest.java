package org.redwoodarrows;

/** Pure point-spacing checks; no Android runtime or private photos. */
public final class PhotoDetectionSpacingTest {
    static int checks;
    static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    public static void main(String[] args){
        for(int width:new int[]{400,600,1200,3000,6000}){
            double scale=width/600.0;
            double[] origin={100*scale,200*scale};
            check(PhotoDetection.sameEntry(origin,new double[]{102*scale,200*scale},width),"Same mark is suppressed at every resolution");
            check(!PhotoDetection.sameEntry(origin,new double[]{104*scale,200*scale},width),"Close neighboring shafts survive at every resolution");
            check(!PhotoDetection.sameEntry(origin,new double[]{103*scale,203*scale},width),"Distance is radial, not a rectangular merge region");
            check(!PhotoDetection.sameEntry(origin,new double[]{106*scale,200*scale},width),"Old 7.2-pixel merge must not drop a real separate peak");
        }
        System.out.println("Photo detection spacing checks passed: "+checks);
    }
}
