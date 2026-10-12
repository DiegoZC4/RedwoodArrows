package org.redwoodarrows;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** HTTPS only in the app. Test transport is explicitly injected, never user-configurable. */
final class SyncHttp implements SyncEngine.Transport {
    static final String ENDPOINT="https://diegozc.com/redwood/api.php";
    final String endpoint,token;
    static final class Failure extends IOException {final int status;Failure(int status,String message){super(message);this.status=status;}}
    SyncHttp(String token){this.endpoint=ENDPOINT;this.token=token;}
    SyncHttp(String endpoint,String token,boolean localTest)throws Exception {
        URI u=new URI(endpoint);if(!localTest||!"http".equals(u.getScheme())||!"127.0.0.1".equals(u.getHost()))throw new IOException("Test transport must stay on loopback");this.endpoint=endpoint;this.token=token;
    }
    private HttpURLConnection open(String action,String method)throws Exception {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Sync paused; saved locally");
        HttpURLConnection c=(HttpURLConnection)new URL(endpoint+"?action="+action).openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setRequestMethod(method);
        if(token!=null&&!token.isEmpty())c.setRequestProperty("Authorization","Bearer "+token);
        c.setRequestProperty("Accept","application/json");return c;
    }
    private InputStream response(HttpURLConnection c)throws Exception {
        int status=c.getResponseCode();if(status>=200&&status<300)return c.getInputStream();
        String message="Sync will retry when the connection returns";InputStream in=c.getErrorStream();
        if(in!=null)try{JSONObject j=new JSONObject(new String(SyncFiles.bytes(in,16384),StandardCharsets.UTF_8));message=j.optString("error",message);}catch(Exception ignored){}finally{in.close();}
        throw new Failure(status,message);
    }
    public JSONObject json(String action,JSONObject input)throws Exception {
        HttpURLConnection c=open(action,input==null?"GET":"POST");
        try{if(input!=null){byte[] data=input.toString().getBytes(StandardCharsets.UTF_8);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(data.length);try(OutputStream out=c.getOutputStream()){out.write(data);}}
            try(InputStream in=response(c)){return new JSONObject(new String(SyncFiles.bytes(in,SyncFiles.JSON_LIMIT),StandardCharsets.UTF_8));}
        }finally{c.disconnect();}
    }
    public void upload(String hash,File file)throws Exception {
        HttpURLConnection c=open("photo&hash="+hash,"PUT");
        try{c.setDoOutput(true);c.setRequestProperty("Content-Type","image/jpeg");c.setFixedLengthStreamingMode(file.length());
            try(InputStream in=new FileInputStream(file);OutputStream out=c.getOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Sync paused");out.write(b,0,n);}}
            try(InputStream in=response(c)){SyncFiles.bytes(in,16384);}
        }finally{c.disconnect();}
    }
    public void download(String hash,File file,long size)throws Exception {
        HttpURLConnection c=open("photo&hash="+hash,"GET");
        try{try(InputStream in=response(c);FileOutputStream out=new FileOutputStream(file)){byte[] b=new byte[16384];long count=0;int n;
            while((n=in.read(b))!=-1){count+=n;if(count>size||count>SyncFiles.PHOTO_LIMIT)throw new IOException("Photo exceeds declared size");if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Sync paused");out.write(b,0,n);}out.getFD().sync();if(count!=size)throw new IOException("Photo download was interrupted");}
            if(!hash.equals(SyncFiles.hash(file)))throw new IOException("Photo checksum failed");
        }catch(Exception e){file.delete();throw e;}finally{c.disconnect();}
    }
}
