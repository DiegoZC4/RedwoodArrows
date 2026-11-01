package org.redwoodarrows;

import android.graphics.*;
import java.io.*;

/** Isolated synthetic photo for manual device UI checks; never a training label. */
public final class ReviewAutosaveFixture {
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Give a new temporary fixture directory");
        File root=new File(args[0]);if(root.exists())throw new IOException("Fixture output already exists");
        Store store=new Store(new File(root,"files"));Store.Group group=Store.Group.start(TargetCatalog.find("mlp-nff-20"),new ShooterRoster.Person("test","Test"));
        group.id="review-autosave-test";group.name="Autosave test (temporary)";
        Store.View view=new Store.View();view.id="review-autosave-photo";view.file=view.id+".jpg";view.width=600;view.height=800;view.detectionState="edited";
        Bitmap image=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(Color.rgb(205,210,214));
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.WHITE);canvas.drawRect(40,80,560,720,paint);paint.setColor(Color.rgb(160,35,45));
        canvas.drawCircle(160,250,12,paint);canvas.drawCircle(430,520,12,paint);
        for(double[] p:new double[][]{{160,250},{430,520}}){Store.Observation mark=new Store.Observation(view.observations.size()+1,p,null);mark.source="synthetic-test";view.observations.add(mark);}
        group.views.add(view);try(OutputStream out=new FileOutputStream(new File(store.directory(group),view.file))){if(!image.compress(Bitmap.CompressFormat.JPEG,95,out))throw new IOException("Fixture encoding failed");}finally{image.recycle();}
        store.save(group);System.out.println("Synthetic fixture prepared: "+group.id);
    }
}
