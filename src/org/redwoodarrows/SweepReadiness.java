package org.redwoodarrows;

import java.util.*;

/** Evidence-based stopping rule, NOT a probability of accuracy or completeness.
 * Camera centers are expressed in the common target plane's radius units.
 * Turning the camera at one position supplies no new viewing angle. */
public final class SweepReadiness {
    public static final int MIN_VIEWS=4,MIN_ARROW_VIEWS=3,STABLE_UPDATES=2;
    public static final double DISTINCT_DEGREES=3,SPAN_DEGREES=12,ARROW_SPAN_DEGREES=8;
    public static final class View {
        public final String id;
        public final double[] camera;
        public final boolean quality;
        public final String problem;
        public View(String id,double[] camera,boolean quality,String problem){this.id=id;this.camera=camera==null?null:camera.clone();this.quality=quality;this.problem=problem;}
    }
    public static final class Assessment {
        public final boolean ready;
        public final int angles,stableArrows,arrows,stableUpdates;
        public final double spanDegrees;
        public final String title,guidance;
        public final Map<Integer,String> trackIssues;
        Assessment(boolean ready,int angles,int supported,int arrows,int stable,double span,String title,String guidance,Map<Integer,String> issues){this.ready=ready;this.angles=angles;stableArrows=supported;this.arrows=arrows;stableUpdates=stable;spanDegrees=span;this.title=title;this.guidance=guidance;trackIssues=Collections.unmodifiableMap(new LinkedHashMap<>(issues));}
    }
    private final Set<String> processed=new HashSet<>();
    private final List<View> independent=new ArrayList<>();
    private List<double[]> previous=new ArrayList<>();
    private int stable;
    private Assessment last;
    public static Assessment starting(){return new Assessment(false,0,0,0,0,0,"Finding the target","Keep the whole face in view and hold briefly. The sweep stops automatically when positions stabilize.",Collections.emptyMap());}
    public Assessment update(View current,MultiViewFusion.Result fusion,double gate){
        if(current==null||current.id==null||!(gate>0&&Double.isFinite(gate)))throw new IllegalArgumentException("Invalid sweep evidence");
        // Re-rendering or rechecking the same result never advances convergence.
        if(!processed.add(current.id))return last==null?starting():last;
        List<MultiViewFusion.Track> tracks=fusion==null?Collections.emptyList():fusion.tracks;
        int arrows=tracks.size(),supported=0;
        String problem=current.problem==null?"":current.problem;
        boolean eligible=current.quality&&validCamera(current.camera);
        if(eligible&&!independent.isEmpty()&&independent.get(0).camera[2]*current.camera[2]<=0)eligible=false;
        boolean distinct=eligible;
        if(distinct)for(View old:independent)if(angle(old.camera,current.camera,0,0)<DISTINCT_DEGREES){distinct=false;break;}
        if(distinct)independent.add(current);
        double span=span(independent,0,0);
        Set<String> recent=new HashSet<>();
        for(int i=Math.max(0,independent.size()-3);i<independent.size();i++)recent.add(independent.get(i).id);
        boolean observedNow=false;Map<Integer,String> issues=new LinkedHashMap<>();int trackId=0;
        for(MultiViewFusion.Track track:tracks){
            trackId++;
            List<View> supporting=new ArrayList<>();boolean seenRecently=false;
            for(MultiViewFusion.Evidence e:track.evidence){
                observedNow|=current.id.equals(e.view);seenRecently|=recent.contains(e.view);
                for(View view:independent)if(view.id.equals(e.view)){
                    boolean unique=true;for(View old:supporting)if(angle(old.camera,view.camera,track.x,track.y)<DISTINCT_DEGREES)unique=false;
                    if(unique)supporting.add(view);
                }
            }
            String issue=track.ambiguous?"Close-arrow association ambiguous":supporting.size()<MIN_ARROW_VIEWS?"Needs more distinct tracked angles":span(supporting,track.x,track.y)<ARROW_SPAN_DEGREES?"Entry needs a wider range of angles":track.spread>gate/3?"Entry positions disagree across views":!seenRecently?"Entry has not been seen from recent angles":"";
            if(issue.isEmpty())supported++;else issues.put(trackId,issue);
        }
        boolean consistent=samePositions(previous,tracks,gate/6);
        boolean complete=arrows>0&&supported==arrows&&fusion.ambiguousObservations==0;
        boolean enough=independent.size()>=MIN_VIEWS&&span>=SPAN_DEGREES;
        if(distinct&&complete&&enough&&consistent&&observedNow)stable++;else stable=0;
        previous=new ArrayList<>();for(MultiViewFusion.Track t:tracks)previous.add(new double[]{t.x,t.y});
        boolean ready=stable>=STABLE_UPDATES;
        String title,guidance;
        if(ready){title="Enough angles · positions stable";guidance="Opening your grouping. Check the dots once; a fully hidden or consistently missed arrow can still be absent.";}
        else if(!eligible){title="Need a clearer view";guidance=problem.isEmpty()?"Keep the whole target in view and let motion tracking settle.":problem;}
        else if(arrows==0){title="Looking for arrow entries";guidance="Move a little closer, keep the whole face visible, and try a side angle. No detections does not mean the target is empty.";}
        else if(!distinct||!enough){title="Move a little farther around";guidance="Sidestep in a short arc with the target in view, then hold briefly. Turning in place or moving only closer adds little angle.";}
        else if(!complete){title="Checking "+(arrows-supported)+" uncertain "+(arrows-supported==1?"entry":"entries");guidance="Try the other side or a slightly higher view to expose overlapping shafts. Keep the arrows still.";}
        else {title=consistent?"Checking position stability":"Positions are still changing";guidance="One more nearby angle, then hold briefly. Newly exposed arrows restart the stability check.";}
        last=new Assessment(ready,independent.size(),supported,arrows,stable,span,title,guidance,issues);return last;
    }
    private static boolean samePositions(List<double[]> old,List<MultiViewFusion.Track> now,double tolerance){
        if(old.isEmpty()||old.size()!=now.size())return false;
        Set<Integer> used=new HashSet<>();
        for(double[] p:old){int match=-1;for(int i=0;i<now.size();i++)if(Math.hypot(p[0]-now.get(i).x,p[1]-now.get(i).y)<=tolerance){if(match>=0)return false;match=i;}if(match<0||!used.add(match))return false;}
        return true;
    }
    static boolean validCamera(double[] p){return p!=null&&p.length==3&&Double.isFinite(p[0])&&Double.isFinite(p[1])&&Double.isFinite(p[2])&&Math.abs(p[2])>.2;}
    static double angle(double[] a,double[] b,double x,double y){
        if(!validCamera(a)||!validCamera(b))return 0;
        double ax=a[0]-x,ay=a[1]-y,bx=b[0]-x,by=b[1]-y;
        double d=(ax*bx+ay*by+a[2]*b[2])/Math.sqrt((ax*ax+ay*ay+a[2]*a[2])*(bx*bx+by*by+b[2]*b[2]));
        return Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,d))));
    }
    private static double span(List<View> views,double x,double y){double span=0;for(int i=0;i<views.size();i++)for(int j=0;j<i;j++)span=Math.max(span,angle(views.get(i).camera,views.get(j).camera,x,y));return span;}
}
