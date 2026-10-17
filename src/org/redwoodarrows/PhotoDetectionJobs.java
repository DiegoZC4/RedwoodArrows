package org.redwoodarrows;

import android.app.job.*;
import android.content.*;

/** Offline-capable persisted fallback when Android stops the foreground app. */
public final class PhotoDetectionJobs extends JobService {
    static final int ID=88053;JobParameters active;
    static void schedule(Context context){JobScheduler scheduler=context.getSystemService(JobScheduler.class);if(scheduler.getPendingJob(ID)==null)scheduler.schedule(new JobInfo.Builder(ID,new ComponentName(context,PhotoDetectionJobs.class)).setPersisted(true).setBackoffCriteria(60000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());}
    @Override public boolean onStartJob(JobParameters params){active=params;PhotoDetectionRuntime.request(this,()->{boolean retry;try{retry=!PhotoDetection.pending(new Store(this)).isEmpty();}catch(Exception e){retry=true;}final boolean pending=retry;new android.os.Handler(getMainLooper()).post(()->{if(active==params){active=null;jobFinished(params,pending);}});});return true;}
    @Override public boolean onStopJob(JobParameters params){if(active==params)active=null;return true;}
}
