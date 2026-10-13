package org.redwoodarrows;

import java.util.*;

/** A chosen physical face is encoded in the existing calibration source, so
 * older clients preserve the choice when saving shooter/distance corrections. */
final class TargetSelection {
    static final String PREFIX="chosen target · ";
    static boolean locked(Store.Group g){return !g.views.isEmpty()&&g.views.get(0).calibration!=null&&g.views.get(0).calibration.source.startsWith(PREFIX);}
    static Geometry.Calibration copy(Geometry.Calibration c){Geometry.Calibration r=new Geometry.Calibration(c.h.clone(),c.rms,c.source);r.reviewed=c.reviewed;return r;}
    /** Require a tap near the bull, never silently choose a distant target. */
    static int nearest(List<Geometry.Calibration> candidates,double x,double y){
        int selected=-1;double best=.48;
        for(int i=0;i<candidates.size();i++){try{double[] p=candidates.get(i).map(x,y);double d=Math.hypot(p[0],p[1]);if(d<best){best=d;selected=i;}}catch(IllegalArgumentException ignored){}}
        return selected;
    }
    /** Work on a draft. Pixel marks/IDs and photos stay unchanged; old-plane
     * coordinates must not be mixed into the newly chosen reference plane. */
    static Store.Group draft(Store.Group source,String viewId,Geometry.Calibration calibration)throws Exception {
        if(source.coordinatesOnly())throw new IllegalArgumentException("This grouping no longer has photos");
        Store.Group g=Store.copy(source);Store.View reference=null;
        for(Store.View v:g.views)if(v.id.equals(viewId))reference=v;
        if(reference==null)throw new IllegalArgumentException("Photo changed; reopen this grouping");
        Geometry.inverse(calibration.h);
        for(Store.View v:g.views){v.calibration=null;v.rimMarks.clear();v.innerMarks.clear();}
        reference.calibration=copy(calibration);reference.calibration.reviewed=true;
        reference.calibration.source=PREFIX+calibration.source.replace(PREFIX,"");
        g.views.remove(reference);g.views.add(0,reference);g.practice.checked="";
        return g;
    }
}
