package org.redwoodarrows;

import android.app.job.*;
import android.content.*;
import java.util.concurrent.Future;

/** Persisted Android network-constrained jobs; no foreground-service battery drain. */
public final class SyncJobs extends JobService {
    static final int ONCE=88051,PERIODIC=88052;
    final java.util.Map<Integer,Future<?>> work=new java.util.HashMap<>();
    static void schedule(Context context){
        if(!new SyncSession(context).present())return;
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);ComponentName service=new ComponentName(context,SyncJobs.class);
        if(scheduler.getPendingJob(ONCE)==null)scheduler.schedule(new JobInfo.Builder(ONCE,service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setBackoffCriteria(60000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
        if(scheduler.getPendingJob(PERIODIC)==null)scheduler.schedule(new JobInfo.Builder(PERIODIC,service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(15*60*1000).build());
    }
    static void cancel(Context c){JobScheduler s=c.getSystemService(JobScheduler.class);s.cancel(ONCE);s.cancel(PERIODIC);}
    @Override public boolean onStartJob(JobParameters params){work.put(params.getJobId(),SyncRuntime.request(this,()->new android.os.Handler(getMainLooper()).post(()->{if(work.remove(params.getJobId())!=null)jobFinished(params,SyncRuntime.status.contains("retry")||SyncRuntime.status.contains("waiting"));})));return true;}
    @Override public boolean onStopJob(JobParameters params){Future<?> pending=work.remove(params.getJobId());if(pending!=null)pending.cancel(true);return true;}
}
