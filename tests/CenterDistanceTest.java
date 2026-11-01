package org.redwoodarrows;

import java.util.*;

public final class CenterDistanceTest {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static void near(double actual,double expected,String why){check(Math.abs(actual-expected)<1e-8,why);}
    public static void main(String[] args)throws Exception {
        Store.Group group=Store.Group.start(TargetCatalog.defaultPreset(),ShooterRoster.UNASSIGNED);
        Store.View view=new Store.View();view.file="fixture.jpg";view.width=1000;view.height=1000;
        double[] h={.003, .0002, -1.5, .0001, -.0032, 1.6, .0002, -.0001, 1};
        view.calibration=new Geometry.Calibration(h,.001,"test reference image-up");view.calibration.reviewed=true;
        double[] pixel=Geometry.project(Geometry.inverse(h),.3,.4);
        view.observations.add(new Store.Observation(1,pixel,null));group.views.add(view);
        Store.Result result=Store.results(group).get(0);
        near(result.x,.3*group.radiusMm(),"X uses target radius, not shooting distance");
        near(result.y,.4*group.radiusMm(),"Y uses projective calibration");
        near(GroupPresentation.centerDistance(result),.5*group.radiusMm(),"Euclidean center distance");
        List<double[]> outer=new ArrayList<>(),inner=new ArrayList<>();
        for(int i=0;i<24;i++){double angle=i*Math.PI/12;outer.add(Geometry.project(Geometry.inverse(h),Math.cos(angle),Math.sin(angle)));inner.add(Geometry.project(Geometry.inverse(h),.6*Math.cos(angle),.6*Math.sin(angle)));}
        Geometry.Calibration fit=Geometry.calibrate(outer,inner,.6,1000,1000);
        double[] fitted=fit.map(pixel[0],pixel[1]);near(Math.hypot(fitted[0],fitted[1]),.5,"Two-ring fit recovers radius under perspective");
        for(double radius:group.preset().rings){double[] ring=Geometry.project(Geometry.inverse(h),radius*.6,radius*.8);double[] back=Geometry.project(h,ring[0],ring[1]);near(Math.hypot(back[0],back[1]),radius,"Drawn ring and measured radius use inverse transforms");}
        group.practice.meters=13.716;
        near(GroupPresentation.centerDistance(Store.results(group).get(0)),.5*group.radiusMm(),"Range metadata cannot rescale target coordinates");
        check(GroupPresentation.coordinateLabel(result).contains("from center"),"Center distance is named");
        check(new GroupPresentation(group).coordinateTable().contains("Center mm"),"Table names millimeter units");
        view.calibration.reviewed=false;
        check(Double.isNaN(GroupPresentation.centerDistance(Store.results(group).get(0))),"Unconfirmed geometry cannot measure distance");
        check(GroupPresentation.coordinateLabel(Store.results(group).get(0)).contains("Not measured"),"No invented zero for unmeasured dots");
        view.calibration.reviewed=true;
        group.target=new TargetPreset("offline-photo-unknown-target","Unknown","Unassigned","Unknown","","","",0,1,1,1,0,"",new double[0],new double[0],new int[0]);
        check(Double.isNaN(GroupPresentation.centerDistance(Store.results(group).get(0))),"Schema placeholder is never a physical scale");
        check(Store.results(group).get(0).warning.contains("Target size not set"),"Missing scale is explicit");
        group.target=TargetCatalog.defaultPreset();view.calibration=null;
        check(Double.isNaN(GroupPresentation.centerDistance(Store.results(group).get(0))),"Missing calibration stays unmeasured");
        Store.Result invalid=new Store.Result();invalid.x=3;invalid.y=Double.POSITIVE_INFINITY;
        check(Double.isNaN(GroupPresentation.centerDistance(invalid)),"Reject non-finite coordinates");
        Store.Group partial=Store.Group.start(TargetCatalog.defaultPreset(),ShooterRoster.UNASSIGNED);
        Store.View calibrated=new Store.View();calibrated.id="known-face";calibrated.file="known.jpg";calibrated.width=calibrated.height=1000;
        calibrated.calibration=new Geometry.Calibration(h,.001,"test reference image-up");calibrated.calibration.reviewed=true;
        Store.View unassigned=new Store.View();unassigned.id="unassigned-face";unassigned.file="same-photo.jpg";unassigned.width=unassigned.height=1000;
        for(int id=1;id<=7;id++)(id<=3?calibrated:unassigned).observations.add(new Store.Observation(id,new double[]{400+id*10,500},null));
        partial.views.add(calibrated);partial.views.add(unassigned);String unchanged=partial.json().toString();
        PhotoReview review=new PhotoReview(partial);
        check(review.marked==7&&review.located==3,"seven correct image dots can have only three measured target positions");
        check(review.needsCalibration==4,"missing calibrations are counted independently of dot review");
        check(review.counts().equals("7 marked | 3 measured"),"measured label does not imply the other dots were not found");
        check(review.measurementStatus().equals("4 need target calibration"),"partial-count reason is explicit");
        check(!review.reviewed&&review.located==3,"measurement count does not require a review checkmark");
        check(new GroupPresentation(partial).count().equals("3 / 7 measured"),"detail count uses the same measurement terminology");
        check(partial.json().toString().equals(unchanged),"count presentation leaves every dot and calibration unchanged");
        unassigned.calibration=new Geometry.Calibration(h,.001,"test reference image-up");unassigned.calibration.reviewed=true;
        review=new PhotoReview(partial);
        check(review.located==7&&review.needsCalibration==0&&review.measurementStatus().isEmpty(),"all calibrated dots measure without checking reviewed");
        check(!review.reviewed,"calibration does not forge a human checkmark");
        unassigned.calibration.reviewed=false;review=new PhotoReview(partial);
        check(review.located==3&&review.needsCalibration==4,"unconfirmed alignment still needs calibration confirmation");
        System.out.println("Center distance: "+checks+" checks passed");
    }
}
