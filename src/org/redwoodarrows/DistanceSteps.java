package org.redwoodarrows;

/** Non-cyclic range stops. An untouched unknown/custom value is never rounded. */
final class DistanceSteps {
    static boolean same(double a,double b){return Double.compare(a,b)==0;}
    static double at(double meters,String unit,int steps){
        if(steps==0)return meters;
        double value=Practice.fromMeters(meters,unit),maximum=Practice.fromMeters(1000,unit);
        int direction=steps>0?1:-1,count=Math.min(2000,Math.abs(steps));
        if(!Practice.distance(meters)){value=20;count--;}
        for(int i=0;i<count;i++){
            double next;
            if(direction>0)next=value<20-1e-8?Math.floor(value+1e-8)+1:(Math.floor(value/5+1e-8)+1)*5;
            else next=value>20+1e-8?Math.max(20,(Math.ceil(value/5-1e-8)-1)*5):Math.ceil(value-1e-8)-1;
            // Keep sub-yard custom entries valid, and don't wrap at either end.
            value=direction>0?Math.min(maximum,next):Math.max(Math.min(1,value),next);
        }
        return Math.min(1000,Practice.toMeters(value,unit));
    }
}
