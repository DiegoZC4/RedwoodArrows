package org.redwoodarrows;

import java.io.File;
import org.json.*;

/** One bounded app-private receipt, plus the last failure. Never retain tokens,
 * response bodies, exception messages, photo paths, account names or labels. */
final class SyncDiagnostics {
    static JSONObject report(long started,String phase,int uploaded,int downloaded,Throwable error)throws JSONException {
        JSONObject j=new JSONObject().put("started",started).put("finished",System.currentTimeMillis()).put("success",error==null)
            .put("phase",phase).put("uploaded",uploaded).put("downloaded",downloaded);
        if(error!=null){j.put("error_type",error.getClass().getSimpleName());if(error instanceof SyncHttp.Failure)j.put("http_status",((SyncHttp.Failure)error).status);
            JSONArray frames=new JSONArray();for(StackTraceElement frame:error.getStackTrace())if(frame.getClassName().startsWith("org.redwoodarrows.")&&frames.length()<6)frames.put(frame.getClassName()+"."+frame.getMethodName()+":"+frame.getLineNumber());j.put("app_frames",frames);}
        return j;
    }
    static void save(File directory,JSONObject report){try{String json=report.toString();SyncFiles.atomic(new File(directory,"last-run.json"),json);if(!report.optBoolean("success"))SyncFiles.atomic(new File(directory,"last-failure.json"),json);}catch(Exception ignored){/* Diagnostics never turn a successful sync into a failure. */}}
    static String status(Throwable error){
        if(error instanceof SyncHttp.Failure){int code=((SyncHttp.Failure)error).status;if(code==401)return "Saved · sign in to sync";if(code==507)return "Saved · cloud storage full";return "Saved · server "+code+" · will retry";}
        if(error instanceof java.net.UnknownHostException||error instanceof java.net.ConnectException)return "No connection · saved · will retry";
        if(error instanceof java.net.SocketTimeoutException)return "Connection timed out · saved · will retry";
        if(error instanceof javax.net.ssl.SSLException)return "Secure connection failed · saved · will retry";
        if(error instanceof org.json.JSONException)return "Shared data error · saved · will retry";
        if(error instanceof java.io.InterruptedIOException)return "Sync paused · saved · will retry";
        return "Sync interrupted · saved · will retry";
    }
}
