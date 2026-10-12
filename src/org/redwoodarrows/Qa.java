package org.redwoodarrows;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.CvType;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.android.Utils;

/** Explicit debug-only local fixture runner. Fixtures are never in the APK. */
public final class Qa {
    static void run(Context c,Store store)throws Exception {
        Store.Group g=null;for(Store.Group existing:store.list())if(existing.name.equals("Oct 7 multi-view trial")){g=existing;break;}
        JSONObject report=new JSONObject();JSONArray frames=new JSONArray();
        File fixtures=new File(c.getFilesDir(),"qa");String[] files={"20261007_162956.jpg","20261007_162949.jpg","20261007_163004.jpg"};
        if(g==null){g=new Store.Group();g.name="Oct 7 multi-view trial";for(String file:files){File path=new File(fixtures,file);if(!path.isFile())throw new IOException("Private QA fixture missing: "+file);store.importPhoto(c,g,Uri.fromFile(path));}}
        for(Store.View v:g.views)if(!v.observations.isEmpty())throw new IllegalStateException("The trial has user marks; preserving it and skipping the fixture rewrite");
        Bitmap reference=store.bitmap(g,g.views.get(0));
        try{
            try{Vision.Rings rings=Vision.rings(reference,"Field");g.views.get(0).calibration=rings.calibration;report.put("automatic_calibration",true).put("ring_fit_rms_mm",rings.calibration.rms*g.radiusMm());}catch(Exception e){report.put("automatic_calibration",false).put("calibration_error",e.toString());}
            report.put("opencv",Core.VERSION);
            for(int i=0;i<g.views.size();i++){Store.View v=g.views.get(i);Bitmap b=i==0?reference:store.bitmap(g,v);JSONObject f=new JSONObject().put("file",files[i]);
                try{f.put("shaft_candidates",Vision.shafts(b,v.calibration).size());if(i>0&&g.views.get(0).calibration!=null){v.calibration=Vision.align(reference,g.views.get(0).calibration,b,null);f.put("alignment",v.calibration.source);}f.put("image_width",b.getWidth()).put("image_height",b.getHeight());}catch(Exception e){f.put("error",e.toString());}finally{if(i>0)b.recycle();}frames.put(f);
            }
            // Verify persistence through an actual round trip, with no fabricated hits.
            store.save(g);Store.Group roundTrip=store.read(new File(store.directory(g),"group.json"));if(roundTrip.views.size()!=3)throw new AssertionError("View count changed on reload");
            report.put("persistence_round_trip",true).put("confirmed_arrow_positions",Store.results(roundTrip).size()).put("frames",frames).put("measurement_accuracy_validated",false);
            report.put("synthetic_registration_error_mm",syntheticRegistration());
            report.put("result_contract_checks",resultChecks());
            for(Store.Group candidate:store.list())if(candidate.name.equals("Oct 7 camera-roll trial")&&!candidate.views.isEmpty()){Bitmap first=store.bitmap(candidate,candidate.views.get(0));try{Vision.Rings ring=Vision.rings(first,"Field");report.put("first_photo_ring_rms_mm",ring.calibration.rms*candidate.radiusMm());report.put("first_photo_shaft_candidates",Vision.shafts(first,ring.calibration).size());}catch(Exception e){report.put("first_photo_error",e.toString());}finally{first.recycle();}break;}
        }finally{reference.recycle();}
        try(FileOutputStream out=new FileOutputStream(new File(c.getFilesDir(),"qa-report.json"))){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}
    }
    static double syntheticRegistration(){
        Bitmap reference=Bitmap.createBitmap(900,1000,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(reference);canvas.drawColor(0xFFE4E3D7);Paint p=new Paint(3);p.setColor(0xFF262E2A);canvas.drawCircle(450,500,320,p);p.setColor(0xFFEAECE2);canvas.drawCircle(450,500,192,p);p.setColor(0xFF262E2A);canvas.drawCircle(450,500,64,p);Random random=new Random(319);
        for(int i=0;i<650;i++){double r=Math.sqrt(random.nextDouble())*310,a=random.nextDouble()*2*Math.PI;p.setColor(i%2==0?0xFFAAA98E:0xFF56554B);canvas.drawCircle((float)(450+r*Math.cos(a)),(float)(500+r*Math.sin(a)),2+random.nextInt(5),p);}
        double[] warp={.95,.12,15,-.08,1,40,.0001,-.00006,1};Mat src=new Mat(),dst=new Mat(),h=new Mat(3,3,CvType.CV_64F);Bitmap current=Bitmap.createBitmap(1000,1100,Bitmap.Config.ARGB_8888);
        try{h.put(0,0,warp);Utils.bitmapToMat(reference,src);Imgproc.warpPerspective(src,dst,h,new Size(1000,1100));Utils.matToBitmap(dst,current);Geometry.Calibration base=new Geometry.Calibration(new double[]{1/320.0,0,-450/320.0,0,-1/320.0,500/320.0,0,0,1},0,"synthetic");Geometry.Calibration result=Vision.align(reference,base,current,null);double sum=0;
            for(double[] t:new double[][]{{0,0},{.5,.2},{-.3,-.5},{.7,-.4},{-.6,.6}}){double[] xy=Geometry.project(warp,450+320*t[0],500-320*t[1]);xy=result.map(xy[0],xy[1]);sum+=Math.pow(xy[0]-t[0],2)+Math.pow(xy[1]-t[1],2);}double rms=Math.sqrt(sum/5)*175;if(rms>3)throw new AssertionError("Synthetic registration error "+rms);return rms;
        }finally{reference.recycle();current.recycle();src.release();dst.release();h.release();}
    }
    static int resultChecks(){Store.Group g=new Store.Group();Store.View a=new Store.View(),b=new Store.View();a.calibration=new Geometry.Calibration(new double[]{1,0,0,0,1,0,0,0,1},0,"synthetic");b.calibration=new Geometry.Calibration(a.calibration.h.clone(),0,"synthetic");a.calibration.reviewed=b.calibration.reviewed=true;g.views.add(a);g.views.add(b);a.observations.add(new Store.Observation(1,new double[]{1.2,-.7},new double[]{1.6,.4}));b.observations.add(new Store.Observation(1,new double[]{1.2,-.7},new double[]{.2,-.1}));Store.Result r=Store.results(g).get(0);if(Math.abs(r.x-210)>1e-6||Math.abs(r.y+122.5)>1e-6)throw new AssertionError("Off-face impact lost");a.calibration.reviewed=false;if(Double.isFinite(Store.results(g).get(0).x))throw new AssertionError("Unreviewed calibration was used");a.calibration.reviewed=true;a.observations.add(new Store.Observation(1,new double[]{.1,.2},null));r=Store.results(g).get(0);if(!r.method.equals("marked impact")||Math.abs(r.x-17.5)>1e-6)throw new AssertionError("Manual impact priority failed");if(!Store.csv(g).contains("17.500,35.000"))throw new AssertionError("CSV coordinate serialization failed");return 4;}
}
