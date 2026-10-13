package org.redwoodarrows;

import android.content.Context;
import android.net.*;
import android.os.*;

/** An open gallery must not wait for Android's batched background job. No wake
 * lock, background poll, battery exemption, or Wi-Fi setting changes. */
final class ForegroundSync {
    final Context context;final Handler handler=new Handler(Looper.getMainLooper());
    final SyncWakeup clock=new SyncWakeup();final ConnectivityManager connectivity;
    ConnectivityManager.NetworkCallback callback;
    final Runnable tick=this::attempt;
    ForegroundSync(Context c){context=c.getApplicationContext();connectivity=context.getSystemService(ConnectivityManager.class);}
    long now(){return SystemClock.elapsedRealtime();}
    static boolean online(Context c){try{ConnectivityManager manager=c.getSystemService(ConnectivityManager.class);NetworkCapabilities caps=manager.getNetworkCapabilities(manager.getActiveNetwork());return caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);}catch(Exception ignored){return false;}}
    void start(){
        clock.start(now());
        if(callback==null){callback=new ConnectivityManager.NetworkCallback(){
            @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities caps){handler.post(()->changed());}
            @Override public void onLost(Network network){handler.post(()->changed());}
        };try{connectivity.registerDefaultNetworkCallback(callback);}catch(Exception ignored){callback=null;}}
        changed();
    }
    void stop(){clock.stop();handler.removeCallbacks(tick);if(callback!=null){try{connectivity.unregisterNetworkCallback(callback);}catch(Exception ignored){}callback=null;}}
    void changed(){if(!clock.visible)return;clock.connection(online(context),now());handler.removeCallbacks(tick);attempt();}
    void schedule(){handler.removeCallbacks(tick);long delay=clock.delay(now());if(delay>=0)handler.postDelayed(tick,delay);}
    void attempt(){
        if(!clock.visible)return;
        clock.connection(online(context),now());
        if(!clock.online){if(new SyncSession(context).present()&&!SyncRuntime.running){SyncRuntime.status="Offline · saved on this phone";SyncRuntime.notifyUi();}return;}
        // Another requester already owns the serial lane; do not queue duplicate
        // full-library scans behind it. Recheck only while the activity is open.
        if(SyncRuntime.running){handler.removeCallbacks(tick);handler.postDelayed(tick,1000);return;}
        if(!clock.begin(now())){schedule();return;}
        if(!new SyncSession(context).present()){clock.completed(now(),true);schedule();return;}
        SyncRuntime.request(context,()->{boolean success=SyncRuntime.lastAttemptSucceeded;handler.post(()->{clock.completed(now(),success);schedule();});});
    }
}
