package org.redwoodarrows;

import java.text.SimpleDateFormat;
import java.util.*;

/** Descriptive statistics, not standard errors or certified measurement accuracy. */
final class ProgressStats {
    static final class End {
        final Store.Group group;final List<double[]> points=new ArrayList<>();
        int records,unlocated,misses;boolean countsKnown,checked;
        End(Store.Group g){group=g;}
    }
    static final class Window {
        final List<End> ends=new ArrayList<>();final List<double[]> points=new ArrayList<>();
        int records,unlocated,misses,unchecked;boolean countsKnown=true;
        double x=Double.NaN,y=Double.NaN,spread=Double.NaN,error=Double.NaN,r80=Double.NaN,sx=Double.NaN,sy=Double.NaN;
        void finish(){
            for(End e:ends){points.addAll(e.points);records+=e.records;unlocated+=e.unlocated;misses+=e.misses;countsKnown&=e.countsKnown;if(!e.checked)unchecked++;}
            if(points.isEmpty())return;
            x=0;y=0;for(double[] p:points){x+=p[0];y+=p[1];}x/=points.size();y/=points.size();double xx=0,yy=0,ee=0;double[] radii=new double[points.size()];int at=0;
            // Population RMS: no n-dependent precision bonus or per-end recentering.
            for(double[] p:points){double dx=p[0]-x,dy=p[1]-y;xx+=dx*dx;yy+=dy*dy;ee+=p[0]*p[0]+p[1]*p[1];radii[at++]=Math.hypot(dx,dy);}
            error=Math.sqrt(ee/points.size());if(points.size()<2)return;
            sx=Math.sqrt(xx/points.size());sy=Math.sqrt(yy/points.size());spread=Math.hypot(sx,sy);Arrays.sort(radii);r80=radii[(int)Math.ceil(.8*radii.length)-1];
        }
        double missRate(){return countsKnown&&records>0?(double)misses/records:Double.NaN;}
        double offset(){return Math.hypot(x,y);}
        int sessions(){Set<String> days=new HashSet<>();SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.US);for(End e:ends)days.add(f.format(new Date(e.group.created)));return days.size();}
    }
    final List<End> ends=new ArrayList<>();
    int noDistance,notChecked,unconfirmedOwner,empty,wrongDistance,unsupportedTarget;
    ProgressStats(List<Store.Group> groups,String shooter,String setup,boolean angular,boolean checkedOnly,double distanceFilter){
        Set<String> seen=new HashSet<>();List<Store.Group> sorted=new ArrayList<>(groups);sorted.sort((a,b)->{int order=Long.compare(b.created,a.created);return order==0?a.id.compareTo(b.id):order;});
        for(Store.Group g:sorted){
            if(!seen.add(g.id)||!g.hasData()||!g.shooter.id.equals(shooter)||!g.practice.setup.equals(setup))continue;
            if(!g.preset().circular()){unsupportedTarget++;continue;}
            boolean provisional=false;List<Store.Result> results=Store.results(g);for(Store.Result r:results)if(r.warning.toLowerCase(Locale.US).contains("shooter provisional"))provisional=true;
            if(!g.shooter.assigned()||provisional){unconfirmedOwner++;continue;}
            if(checkedOnly&&!g.practice.isChecked(g)){notChecked++;continue;}
            if(Practice.distance(distanceFilter)&&!atDistance(g,results,distanceFilter)){wrongDistance++;continue;}
            // A partial-distance end is excluded whole, never selectively tightened.
            if(angular){boolean known=!results.isEmpty()||Practice.distance(g.practice.meters);for(Store.Result r:results)if(!Practice.distance(g.practice.distanceFor(r.id)))known=false;if(!known){noDistance++;continue;}}
            End e=new End(g);e.checked=g.practice.isChecked(g);e.misses=Math.max(0,g.practice.misses);e.countsKnown=g.practice.fired>=results.size()+e.misses&&g.practice.misses>=0;
            Set<Integer> ids=new HashSet<>();for(Store.Result r:results)if(ids.add(r.id)&&Double.isFinite(r.x)&&Double.isFinite(r.y)){double scale=angular?g.practice.distanceFor(r.id):1;e.points.add(new double[]{r.x/scale,r.y/scale});}
            e.records=Math.max(results.size()+e.misses,g.practice.fired);e.unlocated=Math.max(0,e.records-e.points.size()-e.misses);
            if(e.records==0){empty++;continue;}ends.add(e);
        }
    }
    static boolean atDistance(Store.Group g,List<Store.Result> results,double meters){
        if(g.practice.misses>0||g.practice.fired>results.size())if(!Practice.distance(g.practice.meters)||Math.abs(g.practice.meters-meters)>=.001)return false;
        if(results.isEmpty())return Practice.distance(g.practice.meters)&&Math.abs(g.practice.meters-meters)<.001;
        for(Store.Result r:results)if(!Practice.distance(g.practice.distanceFor(r.id))||Math.abs(g.practice.distanceFor(r.id)-meters)>=.001)return false;return true;
    }
    Window recent(int target){return window(0,target);}
    Window previous(int target){return window(recent(target).ends.size(),target);}
    private Window window(int skip,int target){
        Window w=new Window();int n=0;for(int i=skip;i<ends.size()&&n<target;i++){End e=ends.get(i);w.ends.add(e);n+=e.records;}w.finish();return w;
    }
    List<Window> trend(int target){List<Window> out=new ArrayList<>();for(int i=0;i<Math.min(30,ends.size());i++)out.add(window(i,target));Collections.reverse(out);return out;}
    List<Window> sessions(){
        LinkedHashMap<String,Window> days=new LinkedHashMap<>();SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.US);
        for(End e:ends){String key=f.format(new Date(e.group.created));if(!days.containsKey(key))days.put(key,new Window());days.get(key).ends.add(e);}
        List<Window> result=new ArrayList<>(days.values());for(Window w:result)w.finish();return result;
    }
}
