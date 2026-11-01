package org.redwoodarrows;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.*;

/** Explicit offline device probe; inputs/outputs are disposable test files only. */
public final class ImpactVisionDeviceProbe {
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("model-directory upright-image output-json");
        ImpactVision detector=ImpactVision.fromDirectory(new File(args[0]));
        Bitmap bitmap=BitmapFactory.decodeFile(args[1]);
        if(bitmap==null)throw new IllegalArgumentException("Cannot decode fixture");
        try{
            long start=System.nanoTime();
            ImpactVision.Result result=detector.detect(bitmap);
            double elapsed=(System.nanoTime()-start)/1e6;
            JSONArray points=new JSONArray();
            for(double[] point:result.points)points.put(new JSONArray(point));
            JSONObject report=new JSONObject().put("width",result.width).put("height",result.height)
                .put("image_width",bitmap.getWidth()).put("image_height",bitmap.getHeight())
                .put("model_sha256",detector.modelSha256).put("milliseconds",elapsed).put("points",points);
            Files.write(new File(args[2]).toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
            try(DataOutputStream output=new DataOutputStream(new FileOutputStream(args[2]+".f32be"))){
                for(float value:result.probabilities)output.writeFloat(value);
            }
            System.out.println(report.toString());
        }finally{bitmap.recycle();}
    }
}
