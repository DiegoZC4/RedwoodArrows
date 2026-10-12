package org.redwoodarrows;

import android.app.Application;
import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** One sync lane per process, separate from image analysis. */
public final class SyncRuntime extends Application {
    static final ExecutorService network=Executors.newSingleThreadExecutor();
    static final AtomicLong generation=new AtomicLong();
    static volatile String status="Saved on this phone";
    static volatile boolean running;
    static volatile Runnable uiChanged;
    static File directory(Context c){return new File(c.getFilesDir(),"sync");}
    @Override public void onCreate(){super.onCreate();Context c=getApplicationContext();
        Store.onChanged=()->{generation.incrementAndGet();if(new SyncSession(c).present()){status="Saved · waiting to sync";SyncJobs.schedule(c);notifyUi();}};
        if(new SyncSession(c).present()){status="Saved · waiting to sync";SyncJobs.schedule(c);}
    }
    static void notifyUi(){Runnable listener=uiChanged;if(listener!=null)listener.run();}
    static SyncEngine engine(Context c,JSONObject session)throws Exception {return new SyncEngine(new Store(c),directory(c),new SyncHttp(session.getString("token")));}
    static synchronized Future<?> request(Context context,Runnable done){
        Context c=context.getApplicationContext();
        return network.submit(()->{
            long start=generation.get();running=true;
            try{
                JSONObject session=new SyncSession(c).read();if(session==null){status="Saved on this phone";return;}
                status="Syncing…";notifyUi();SyncEngine engine=engine(c,session);engine.run();
                int conflicts=engine.conflicts().size();status=conflicts>0?conflicts+" edit conflict"+(conflicts==1?"":"s"):engine.state.optJSONObject("deferred").length()>0?"Saved · shared update waiting":generation.get()!=start?"Saved · waiting to sync":"Synced · just now";
                if(generation.get()!=start)SyncJobs.schedule(c);
            }catch(SyncHttp.Failure e){status=e.status==401?"Saved · sign in to sync":e.status==507?"Saved · cloud storage full":"Saved · sync will retry";}
            catch(Exception e){status="Saved · sync will retry";}
            finally{running=false;notifyUi();if(done!=null)done.run();}
        });
    }
}
