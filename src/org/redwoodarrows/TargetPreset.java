package org.redwoodarrows;

import java.util.*;

/** Measurement geometry, not a reproduction of a manufacturer's artwork.
 * All distances are millimetres. Circular presets measure ONE spot, not its sheet.
 * Paper presets measure the untrimmed sheet and do not imply scoring-zone support.
 */
public final class TargetPreset {
    public final String id,name,category,family,sku,source,note,innerHint;
    public final double diameterMm,paperWidthMm,paperHeightMm,innerRatio;
    public final int spots;
    public final double[] rings,fillRadii;
    public final int[] fillColors;
    public static final int BLACK=0xFF171918,WHITE=0xFFF9F8F0,BLUE=0xFF1686C0,
        NAVY=0xFF163E79,RED=0xFFE74640,GOLD=0xFFFFD438;

    public TargetPreset(String id,String name,String category,String family,String sku,String source,String note,
                        double diameterMm,double paperWidthMm,double paperHeightMm,int spots,double innerRatio,
                        String innerHint,double[] rings,double[] fillRadii,int[] fillColors){
        this.id=id;this.name=name;this.category=category;this.family=family;this.sku=sku;this.source=source;this.note=note;
        this.diameterMm=diameterMm;this.paperWidthMm=paperWidthMm;this.paperHeightMm=paperHeightMm;
        this.spots=spots;this.innerRatio=innerRatio;this.innerHint=innerHint;
        this.rings=rings.clone();this.fillRadii=fillRadii.clone();this.fillColors=fillColors.clone();
        if(id.isEmpty()||name.isEmpty()||spots<1||spots>54||!finitePositive(paperWidthMm)||!finitePositive(paperHeightMm)
            ||!Double.isFinite(diameterMm)||diameterMm<0||fillRadii.length!=fillColors.length)
            throw new IllegalArgumentException("Invalid target preset");
        if(circular()){
            if(!(innerRatio>0&&innerRatio<1)||rings.length<2||fillRadii.length==0)throw new IllegalArgumentException("Missing circle geometry");
            double previous=0;boolean innerPresent=false;
            for(double r:rings){if(!Double.isFinite(r)||r<=previous||r>1)throw new IllegalArgumentException("Unordered rings");previous=r;if(Math.abs(r-innerRatio)<1e-8)innerPresent=true;}
            if(Math.abs(previous-1)>1e-8||!innerPresent)throw new IllegalArgumentException("Calibration boundary is not a ring");
            previous=2;for(double r:fillRadii){if(!Double.isFinite(r)||r<=0||r>=previous||r>1)throw new IllegalArgumentException("Unordered fills");previous=r;}
            if(fillRadii[0]!=1)throw new IllegalArgumentException("Missing outer fill");
        }else if(rings.length!=0||fillRadii.length!=0||innerRatio!=0)throw new IllegalArgumentException("Paper is not a circle");
    }
    private static boolean finitePositive(double n){return Double.isFinite(n)&&n>0&&n<10000;}
    public boolean circular(){return diameterMm>0;}
    public double scaleMm(){return circular()?diameterMm/2:Math.max(paperWidthMm,paperHeightMm)/2;}
    public double halfWidth(){return circular()?1:paperWidthMm/(2*scaleMm());}
    public double halfHeight(){return circular()?1:paperHeightMm/(2*scaleMm());}
    public String dimensions(){return circular()?number(diameterMm/10)+" cm "+(spots>1?"per spot":"face"):number(paperWidthMm/10)+" × "+number(paperHeightMm/10)+" cm paper";}
    public static String number(double n){return String.format(Locale.US,"%.2f",n).replaceAll("0+$","").replaceAll("\\.$","");}
    public double[][] outline(double expansion){
        if(!circular())return new double[][]{{-halfWidth()*expansion,halfHeight()*expansion},{halfWidth()*expansion,halfHeight()*expansion},{halfWidth()*expansion,-halfHeight()*expansion},{-halfWidth()*expansion,-halfHeight()*expansion}};
        double[][] points=new double[128][2];for(int i=0;i<points.length;i++){double a=2*Math.PI*i/points.length;points[i]=new double[]{expansion*Math.cos(a),expansion*Math.sin(a)};}return points;
    }
}
