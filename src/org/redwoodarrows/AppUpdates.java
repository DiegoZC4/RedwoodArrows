package org.redwoodarrows;

import android.content.*;
import android.net.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

/** Foreground-only, event-driven release check; never downloads/installs an APK. */
final class AppUpdates {
    private static AppUpdates shared;
    private static final ExecutorService NETWORK=Executors.newSingleThreadExecutor();
    static synchronized AppUpdates get(Context c){if(shared==null){String version="";try{version=c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName;}catch(Exception ignored){}shared=new AppUpdates(c.getApplicationContext(),version,c.getSharedPreferences("app-updates",0),new ReleaseUpdate.GitHub(),System::currentTimeMillis,true);}return shared;}
    final String installed;final SharedPreferences prefs;final ReleaseUpdate.Transport transport;final ReleaseUpdate.Clock clock;
    final Handler main=new Handler(Looper.getMainLooper());final Set<Runnable> listeners=new LinkedHashSet<>();
    final ConnectivityManager connectivity;final boolean observeNetwork;
    ReleaseUpdate.State state;boolean online,running;ConnectivityManager.NetworkCallback callback;
    // Dependency injection is package-private for isolated tests; there is no
    // exported intent, server setting or UI for changing the trusted endpoint.
    AppUpdates(Context c,String installed,SharedPreferences prefs,ReleaseUpdate.Transport transport,ReleaseUpdate.Clock clock,boolean observeNetwork){this.installed=installed;this.prefs=prefs;this.transport=transport;this.clock=clock;this.observeNetwork=observeNetwork;connectivity=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);state=ReleaseUpdate.State.read(prefs.getString("state",""));}
    ReleaseUpdate.Release available(){return ReleaseUpdate.offer(state,installed,clock.now(),online);}
    void start(Runnable listener){listeners.add(listener);if(observeNetwork&&callback==null){
        callback=new ConnectivityManager.NetworkCallback(){
            @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities caps){connected(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));}
            @Override public void onLost(Network n){connected(false);}
        };
        try{connectivity.registerDefaultNetworkCallback(callback,main);NetworkCapabilities caps=connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());online=caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);}
        catch(Exception unavailable){try{connectivity.unregisterNetworkCallback(callback);}catch(Exception ignored){}callback=null;online=false;}
    }listener.run();check();}
    void stop(Runnable listener){listeners.remove(listener);if(listeners.isEmpty()&&callback!=null){try{connectivity.unregisterNetworkCallback(callback);}catch(Exception ignored){}callback=null;online=false;}}
    void connected(boolean connected){online=connected;notifyUi();if(connected)check();}
    void notifyUi(){for(Runnable listener:new ArrayList<>(listeners))listener.run();}
    void persist(){prefs.edit().putString("state",state.json()).apply();}
    void check(){if(listeners.isEmpty()||!online||running||!ReleaseUpdate.due(state,clock.now()))return;
        running=true;state.attempt=clock.now();persist();final ReleaseUpdate.State before=state.copy();
        NETWORK.execute(()->{ReleaseUpdate.Reply reply;try{reply=transport.fetch(before.etag);}catch(Exception offline){reply=new ReleaseUpdate.Reply(0,"","",ReleaseUpdate.RETRY_INTERVAL);}
            final ReleaseUpdate.State result=ReleaseUpdate.accept(before,reply,clock.now());main.post(()->{state=result;running=false;persist();notifyUi();});
        });
    }
}
