package org.redwoodarrows;

/** Foreground-only retry clock. Android still owns background scheduling. */
final class SyncWakeup {
    boolean visible,online,inFlight;int failures;long next;
    void start(long now){visible=true;next=now;}
    void stop(){visible=false;}
    void connection(boolean connected,long now){if(connected&&!online){next=now;failures=0;}online=connected;}
    boolean begin(long now){if(!visible||!online||inFlight||now<next)return false;inFlight=true;return true;}
    void completed(long now,boolean success){inFlight=false;failures=success?0:Math.min(6,failures+1);next=now+(success?60000:Math.min(300000,15000L<<(failures-1)));}
    long delay(long now){return !visible||!online||inFlight?-1:Math.max(1,next-now);}
}
