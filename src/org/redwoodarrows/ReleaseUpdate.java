package org.redwoodarrows;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;
import org.json.*;

/** Public release metadata only. No sync token, photo or account is involved. */
final class ReleaseUpdate {
    static final String REPOSITORY="https://github.com/DiegoZC4/RedwoodArrows";
    static final String API="https://api.github.com/repos/DiegoZC4/RedwoodArrows/releases/latest";
    static final long CHECK_INTERVAL=15*60_000L,RETRY_INTERVAL=60_000L,DAY=24*60*60_000L,MAX_CACHE_AGE=7*DAY;
    static final int MAX_BODY=65_536;
    static final Pattern VERSION=Pattern.compile("(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})");
    interface Transport {Reply fetch(String etag)throws Exception;}
    interface Clock {long now();}
    static final class Release {
        final String tag,url;final long bytes;
        Release(String tag,long bytes){this.tag=tag;this.bytes=bytes;url=REPOSITORY+"/releases/download/"+tag+"/RedwoodArrows.apk";}
        String version(){return tag.substring(1);}
        JSONObject json()throws JSONException{return new JSONObject().put("tag",tag).put("bytes",bytes);}
    }
    static final class State {
        String etag="";Release release;long checked,attempt,retryAt;
        static State read(String json){State s=new State();try{JSONObject j=new JSONObject(json);s.checked=Math.max(0,j.optLong("checked"));s.attempt=Math.max(0,j.optLong("attempt"));s.retryAt=Math.max(0,j.optLong("retryAt"));s.etag=safeEtag(j.optString("etag"));JSONObject r=j.optJSONObject("release");if(r!=null)s.release=release(r.optString("tag"),r.optLong("bytes"));}catch(Exception ignored){}return s;}
        String json(){try{JSONObject j=new JSONObject().put("checked",checked).put("attempt",attempt).put("retryAt",retryAt).put("etag",etag);if(release!=null)j.put("release",release.json());return j.toString();}catch(JSONException e){throw new IllegalStateException(e);}}
        State copy(){return read(json());}
    }
    static final class Reply {
        final int status;final String body,etag;final long retryMs;
        Reply(int status,String body,String etag,long retryMs){this.status=status;this.body=body;this.etag=safeEtag(etag);this.retryMs=retryMs;}
    }
    static Release release(String tag,long bytes){if(!tag.startsWith("v")||!VERSION.matcher(tag.substring(1)).matches()||bytes<=0||bytes>=100_000_000)return null;return new Release(tag,bytes);}
    static Release parse(String body)throws JSONException {
        if(body==null||body.length()>MAX_BODY)throw new IllegalArgumentException("Release response too large");
        JSONObject j=new JSONObject(body);
        if(j.getBoolean("draft")||j.getBoolean("prerelease"))return null;
        String tag=j.getString("tag_name");JSONArray assets=j.getJSONArray("assets");Release found=null;
        for(int i=0;i<assets.length();i++){JSONObject a=assets.getJSONObject(i);if(!"RedwoodArrows.apk".equals(a.optString("name")))continue;
            Release r=release(tag,a.optLong("size"));
            if(r==null||!"uploaded".equals(a.optString("state"))||!r.url.equals(a.optString("browser_download_url"))||found!=null)return null;
            found=r;
        }return found;
    }
    static boolean newer(Release release,String installed){
        if(release==null||installed==null)return false;String local=installed.split("-",2)[0];
        if(!VERSION.matcher(local).matches())return false;
        String[] a=release.version().split("\\."),b=local.split("\\.");
        for(int i=0;i<3;i++){int c=Integer.compare(Integer.parseInt(a[i]),Integer.parseInt(b[i]));if(c!=0)return c>0;}return false;
    }
    static boolean fresh(long now,long then,long ttl){return then>0&&now>=then&&now-then<ttl;}
    static boolean due(State s,long now){return !fresh(now,s.checked,CHECK_INTERVAL)&&!fresh(now,s.attempt,RETRY_INTERVAL)&&!(s.retryAt>now&&s.retryAt-now<=DAY);}
    static Release offer(State s,String installed,long now,boolean online){return online&&fresh(now,s.checked,MAX_CACHE_AGE)&&newer(s.release,installed)?s.release:null;}
    static State accept(State old,Reply reply,long now){
        State s=old.copy();s.attempt=now;
        try{
            if(reply.status==200){s.release=parse(reply.body);s.etag=reply.etag;s.checked=now;s.retryAt=0;}
            else if(reply.status==304&&s.checked>0){s.checked=now;s.retryAt=0;if(!reply.etag.isEmpty())s.etag=reply.etag;}
            else if(reply.status==404){s.release=null;s.etag="";s.checked=now;s.retryAt=0;}
            else{s.retryAt=now+Math.max(RETRY_INTERVAL,Math.min(DAY,reply.retryMs));}
        }catch(Exception invalid){s.retryAt=now+RETRY_INTERVAL;}
        return s;
    }
    static String safeEtag(String value){return value!=null&&value.length()<=512&&value.matches("[\\x20-\\x7E]*")?value:"";}
    static String body(InputStream input)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
        while((n=input.read(buffer))!=-1){if(bytes.size()+n>MAX_BODY)throw new IOException("Release response too large");bytes.write(buffer,0,n);}return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
    }
    static final class GitHub implements Transport {
        public Reply fetch(String etag)throws Exception {
            HttpURLConnection c=(HttpURLConnection)new URL(API).openConnection();
            try{
                c.setConnectTimeout(4000);c.setReadTimeout(4000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
                c.setRequestProperty("Accept","application/vnd.github+json");c.setRequestProperty("X-GitHub-Api-Version","2026-03-10");c.setRequestProperty("User-Agent","RedwoodArrows-UpdateCheck");
                if(!safeEtag(etag).isEmpty())c.setRequestProperty("If-None-Match",etag);
                int status=c.getResponseCode();String body="";
                if(status==200){String type=c.getContentType();if(type==null||!type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")||c.getContentLengthLong()>MAX_BODY)throw new IOException("Unexpected release metadata");try(InputStream input=c.getInputStream()){body=body(input);}}
                long retry=(status==403||status==429)?60*60_000L:RETRY_INTERVAL;
                try{long seconds=Long.parseLong(c.getHeaderField("Retry-After"));retry=Math.max(RETRY_INTERVAL,Math.min(DAY,Math.min(86400,Math.max(0,seconds))*1000));}catch(Exception ignored){}
                if("0".equals(c.getHeaderField("X-RateLimit-Remaining")))try{long reset=Long.parseLong(c.getHeaderField("X-RateLimit-Reset"));if(reset>0&&reset<Long.MAX_VALUE/1000)retry=Math.max(retry,Math.min(DAY,reset*1000-System.currentTimeMillis()));}catch(Exception ignored){}
                return new Reply(status,body,c.getHeaderField("ETag"),retry);
            }finally{c.disconnect();}
        }
    }
}
