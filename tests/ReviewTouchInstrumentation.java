package org.redwoodarrows;

import android.app.*;
import android.os.Bundle;
import java.io.*;

/** Own-package instrumentation, with no access to Redwood Review's data. */
public final class ReviewTouchInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();Throwable[] failure=new Throwable[1];
        runOnMainSync(()->{try{System.setProperty("java.io.tmpdir",getContext().getFilesDir().getAbsolutePath());PhotoCanvasTouchTest.run(getContext());NavigationFeedbackTest.run();GalleryPreviewTest.run(getContext());}catch(Throwable e){failure[0]=e;}});
        if(failure[0]==null){result.putString("review_touch","passed");result.putInt("checks",PhotoCanvasTouchTest.checks+NavigationFeedbackTest.checks+GalleryPreviewTest.checks);finish(Activity.RESULT_OK,result);}
        else{StringWriter details=new StringWriter();failure[0].printStackTrace(new PrintWriter(details));result.putString("stream",details.toString());result.putString("review_touch","failed");finish(Activity.RESULT_CANCELED,result);}
    }
}
