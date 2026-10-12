package org.redwoodarrows;

import java.util.*;

/** Conservative association in ONE registered target plane. Missing detections
 * are not negative evidence: another viewpoint may expose an occluded entry.
 * At most one observation from a view may support a track. Close/ambiguous
 * arrows remain unresolved instead of being forcibly merged. */
public final class MultiViewFusion {
    private MultiViewFusion(){}
    public static final class View {
        public final String id;
        public final double[] imageToPlane;
        public final List<double[]> points;
        public View(String id,double[] h,List<double[]> points){this.id=id;imageToPlane=h;this.points=points;}
    }
    public static final class Evidence {
        public final String view;
        public final double x,y,score,imageX,imageY;
        Evidence(String view,double[] p,double[] image){this.view=view;x=p[0];y=p[1];score=image[2];imageX=image[0];imageY=image[1];}
    }
    public static final class Track {
        public final List<Evidence> evidence=new ArrayList<>();
        public double x,y,spread,confidence;
        public boolean ambiguous=false;
        /** Confidence is the best single-view detector score, NOT a posterior.
         * Repeated frames never multiply confidence or shrink claimed accuracy. */
        void refine(double gate){
            Evidence medoid=null;double best=Double.POSITIVE_INFINITY;
            for(Evidence p:evidence){double cost=0;for(Evidence q:evidence)cost+=Math.min(gate,Math.hypot(p.x-q.x,p.y-q.y));if(cost<best){best=cost;medoid=p;}}
            x=medoid.x;y=medoid.y;confidence=0;
            // Huber reweighting around a robust medoid suppresses a bad view.
            for(int it=0;it<8;it++){
                double sx=0,sy=0,sw=0;
                for(Evidence p:evidence){double residual=Math.hypot(p.x-x,p.y-y);double weight=Math.max(.05,p.score)*Math.min(1,gate*.35/Math.max(1e-12,residual));sx+=weight*p.x;sy+=weight*p.y;sw+=weight;confidence=Math.max(confidence,p.score);}
                x=sx/sw;y=sy/sw;
            }
            spread=0;for(Evidence p:evidence)spread=Math.max(spread,Math.hypot(p.x-x,p.y-y));
        }
    }
    public static final class Result {
        public final List<Track> tracks=new ArrayList<>();
        public int ambiguousObservations,views;
    }
    public static Result fuse(List<View> views,double gate){
        if(!(gate>0&&Double.isFinite(gate)))throw new IllegalArgumentException("Invalid association radius");
        Result result=new Result();Set<String> usedViews=new HashSet<>();
        for(View v:views){
            if(!usedViews.add(v.id))throw new IllegalArgumentException("Duplicate viewpoint cannot supply independent evidence");
            Geometry.inverse(v.imageToPlane);List<Evidence> entries=new ArrayList<>();
            for(double[] p:v.points){
                if(p.length<3||!Double.isFinite(p[0])||!Double.isFinite(p[1])||!(p[2]>=0&&p[2]<=1))throw new IllegalArgumentException("Invalid entry estimate");
                double[] q=Geometry.project(v.imageToPlane,p[0],p[1]);
                if(!Double.isFinite(q[0])||!Double.isFinite(q[1]))throw new IllegalArgumentException("Invalid target-plane projection");
                entries.add(new Evidence(v.id,q,p));
            }
            int nt=result.tracks.size();double[][] distances=new double[entries.size()][nt];
            for(int i=0;i<entries.size();i++)for(int j=0;j<nt;j++){
                Evidence p=entries.get(i);Track t=result.tracks.get(j);
                // Complete-link support prevents a chain of nearby hits from
                // walking across the target as more views arrive.
                double d=0;for(Evidence old:t.evidence)d=Math.max(d,Math.hypot(p.x-old.x,p.y-old.y));
                distances[i][j]=d;
            }
            for(int i=0;i<entries.size();i++){
                int nearest=-1;double first=Double.POSITIVE_INFINITY,second=Double.POSITIVE_INFINITY;
                for(int j=0;j<nt;j++){double d=distances[i][j];if(d<first){second=first;first=d;nearest=j;}else second=Math.min(second,d);}
                if(nearest>=0&&first<=gate){
                    double other=Double.POSITIVE_INFINITY;
                    for(int k=0;k<entries.size();k++)if(k!=i)other=Math.min(other,distances[k][nearest]);
                    // Require a clear, mutual, one-to-one assignment. No IDs or
                    // expected arrow count are supplied to break an ambiguity.
                    if(second-first<gate*.25||other-first<gate*.25){
                        result.ambiguousObservations++;result.tracks.get(nearest).ambiguous=true;continue;
                    }
                    Track t=result.tracks.get(nearest);
                    boolean sameView=false;for(Evidence old:t.evidence)if(old.view.equals(v.id))sameView=true;
                    if(sameView){result.ambiguousObservations++;t.ambiguous=true;continue;}
                    t.evidence.add(entries.get(i));t.refine(gate);
                }else{
                    Track t=new Track();t.evidence.add(entries.get(i));t.refine(gate);result.tracks.add(t);
                }
            }
            result.views++;
        }
        // Deterministic provisional numbering. These are track IDs, not learned
        // identities of physically distinguishable shafts.
        Collections.sort(result.tracks,(a,b)->{int c=Double.compare(b.y,a.y);return c!=0?c:Double.compare(a.x,b.x);});
        return result;
    }
}
