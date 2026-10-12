package org.redwoodarrows;

/** Read-only preview geometry shared by the photo warp and its fitted outlines. */
final class TargetPreview {
    private TargetPreview(){}

    static double[] imageToPreview(Geometry.Calibration calibration,int photoWidth,int photoHeight,int decodedWidth,int decodedHeight,int size,double extent){
        if(calibration==null||!calibration.reviewed)throw new IllegalArgumentException("Not aligned");
        if(decodedWidth<=0||decodedHeight<=0||photoWidth<=0||photoHeight<=0||size<2||!Double.isFinite(extent)||extent<=0)throw new IllegalArgumentException("Invalid image geometry");
        double center=(size-1)/2.,scale=(size-1)*.49/extent;
        double[] h=Geometry.mul(new double[]{scale,0,center,0,-scale,center,0,0,1},Geometry.mul(calibration.h,new double[]{photoWidth/(double)decodedWidth,0,0,0,photoHeight/(double)decodedHeight,0,0,0,1}));
        for(double n:h)if(!Double.isFinite(n))throw new IllegalArgumentException("Invalid alignment");
        double[] inv=Geometry.inverse(h);double sign=0;
        for(double[] q:new double[][]{{0,0},{size-1,0},{size-1,size-1},{0,size-1}}){double z=inv[6]*q[0]+inv[7]*q[1]+inv[8];if(Math.abs(z)<1e-10||sign*z<0)throw new IllegalArgumentException("Alignment crosses horizon");sign=z;}
        return h;
    }

    /** Project the fit's original-photo curves through the same warp.
     * Presets supply ring ratios, not new independent detections. A bad saved
     * fit stays visibly offset from the photographed rings: never refit here.
     * Paper targets have an outline, not invented circular scoring zones.
     */
    static double[][][] fittedBoundaries(Geometry.Calibration calibration,int photoWidth,int photoHeight,TargetPreset preset,int size,double extent){
        double[] photoToPreview=imageToPreview(calibration,photoWidth,photoHeight,photoWidth,photoHeight,size,extent);
        double[] planeToPhoto=Geometry.inverse(calibration.h);
        double[][][] boundaries=new double[preset.circular()?preset.rings.length:1][][];
        for(int ring=0;ring<boundaries.length;ring++){
            double[][] plane=preset.outline(preset.circular()?preset.rings[ring]:1);
            boundaries[ring]=new double[plane.length][2];
            for(int i=0;i<plane.length;i++){
                double[] photo=Geometry.project(planeToPhoto,plane[i][0],plane[i][1]);
                double[] q=Geometry.project(photoToPreview,photo[0],photo[1]);
                if(!Double.isFinite(q[0])||!Double.isFinite(q[1]))throw new IllegalArgumentException("Invalid target outline");
                boundaries[ring][i]=q;
            }
        }
        return boundaries;
    }
}
