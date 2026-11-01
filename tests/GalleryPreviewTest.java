package org.redwoodarrows;

import android.content.Context;
import android.graphics.*;
import java.util.*;

/** Synthetic maps only; never reads or updates the user's library. */
public final class GalleryPreviewTest {
    static int checks;
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static int render(TargetCanvas map,int width,int height,int color){
        map.layout(0,0,width,height);Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);map.draw(new Canvas(bitmap));
        int pixels=0;for(int y=0;y<height;y++)for(int x=0;x<width;x++)if(bitmap.getPixel(x,y)==color)pixels++;bitmap.recycle();return pixels;
    }
    static void run(Context context)throws Exception{
        int purple=0xFFBD9AF7;Store.Group group=Store.Group.start(TargetCatalog.find("mlp-nfh-35"),new ShooterRoster.Person("iris","Iris"));
        Store.View frame=new Store.View();frame.id="one";frame.file="one.jpg";frame.width=400;frame.height=400;
        frame.calibration=new Geometry.Calibration(new double[]{.005,0,-1,0,-.005,1,0,0,1},.01,PhotoDetection.AUTO+"test");
        frame.observations.add(new Store.Observation(1,new double[]{220,180},null));group.views.add(frame);
        String before=group.json().toString();TargetCanvas map=TargetCanvas.mappedThumbnail(context,group,purple);
        check(map.results.size()==1&&!Double.isFinite(map.results.get(0).x),"unconfirmed detector alignment never becomes a mapped point");
        check(render(map,180,224,purple)==0,"missing calibration renders no colored arrow");
        check(before.equals(group.json().toString()),"rendering cannot approve a proposed calibration");
        frame.calibration.reviewed=true;before=group.json().toString();map=TargetCanvas.mappedThumbnail(context,group,purple);
        check(Math.abs(map.results.get(0).x-17.5)<.001,"map uses calibrated millimeter coordinates");
        check(render(map,180,224,purple)>0,"mapped arrow renders at compact phone size");
        check(render(map,480,224,purple)>0,"mapped arrow renders at a wide viewport");
        frame.observations.add(new Store.Observation(2,new double[]{800,100},null));map=TargetCanvas.mappedThumbnail(context,group,purple);
        check(render(map,180,224,purple)>0,"outlying arrow remains framed");
        group.views.add(new Store.View());Store.View pending=group.views.get(1);pending.id="two";pending.file="two.jpg";pending.width=400;pending.height=400;pending.observations.add(new Store.Observation(3,new double[]{200,210},null));
        map=TargetCanvas.mappedThumbnail(context,group,purple);check(map.results.stream().filter(r->Double.isFinite(r.x)).count()==2,"partial maps exclude unmapped entries without deleting them");
        check(map.results.size()==3,"all marked IDs survive partial preview");
        check(map.small&&map.mappedOnly&&map.dotColor==purple,"gallery map keeps shooter color and thumbnail mode");
        System.out.println("GalleryPreviewTest: "+checks+" checks passed");
    }
}
