package org.redwoodarrows;

import org.json.*;
import java.io.*;

public final class SyncWakeupTest {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        SyncWakeup p=new SyncWakeup();p.start(100);check(!p.begin(100)&&p.delay(100)==-1,"Offline does not poll");
        p.connection(true,101);check(p.begin(101),"Validated connection retries immediately");check(!p.begin(102),"One foreground attempt at a time");
        p.completed(102,false);check(p.delay(102)==15000,"First failure retries after 15 seconds, not an Android job window");check(!p.begin(15101)&&p.begin(15102),"Retry deadline respected");
        p.completed(15103,false);check(p.delay(15103)==30000,"Persistent failure backs off");
        p.connection(false,16000);check(p.delay(16000)==-1,"Losing network cancels foreground timer");p.connection(true,16001);check(p.begin(16001),"Network recovery skips old backoff");
        p.stop();p.completed(16002,false);check(p.delay(16002)==-1&&!p.begin(999999),"Leaving app stops polls even if a request completes later");
        p.start(17000);check(p.begin(17000),"Reopening retries immediately despite previous failure");p.completed(17001,true);check(p.delay(17001)==60000&&p.failures==0,"Healthy open gallery checks once per minute");
        p.connection(true,17002);check(!p.begin(17002),"Repeated capabilities notifications do not create requests");
        for(int i=0;i<20;i++){p.start(20000+i);check(p.begin(20000+i),"Retry starts");p.completed(20000+i,false);}check(p.delay(20019)==300000,"Failure backoff is capped");
        IOException secret=new IOException("Bearer private-token and /private/personal.jpg");String diagnostic=SyncDiagnostics.report(12,"download_photo",0,3,secret).toString();
        check(!diagnostic.contains("private-token")&&!diagnostic.contains("personal.jpg")&&!diagnostic.contains("Bearer"),"Diagnostic excludes exception messages and private data");
        check(new JSONObject(diagnostic).getInt("downloaded")==3,"Partial progress retained");
        check(SyncDiagnostics.status(new java.net.SocketTimeoutException()).contains("timed out"),"Timeout has actionable status");
        check(SyncDiagnostics.status(new SyncHttp.Failure(401,"secret")).contains("sign in"),"Auth failure remains explicit");
        check(SyncDiagnostics.status(new SyncHttp.Failure(503,"secret")).contains("503"),"Server error code visible without response contents");
        System.out.println("Foreground sync recovery checks passed: "+checks);
    }
}
