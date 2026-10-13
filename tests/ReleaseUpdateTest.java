package org.redwoodarrows;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

public final class ReleaseUpdateTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static JSONObject fixture(String tag)throws Exception{return new JSONObject().put("tag_name",tag).put("draft",false).put("prerelease",false).put("assets",new JSONArray().put(new JSONObject().put("name","RedwoodArrows.apk").put("state","uploaded").put("size",29_000_000).put("browser_download_url",ReleaseUpdate.REPOSITORY+"/releases/download/"+tag+"/RedwoodArrows.apk")));}
    static ReleaseUpdate.Reply reply(int status,String json){return new ReleaseUpdate.Reply(status,json,"\"test-v1\"",0);}
    public static void main(String[] args)throws Exception {
        long now=1_800_000_000_000L;ReleaseUpdate.State blank=new ReleaseUpdate.State();
        ReleaseUpdate.Release next=ReleaseUpdate.parse(fixture("v0.22.0").toString());
        check(ReleaseUpdate.newer(next,"0.21.0-update-notice"),"New release recognized");
        check(!ReleaseUpdate.newer(next,"0.22.0-local"),"Already updated, no notice");
        check(!ReleaseUpdate.newer(next,"0.23.0-local"),"Never offer downgrade");
        check(ReleaseUpdate.newer(ReleaseUpdate.release("v0.100.0",10),"0.99.0"),"Numeric, not lexical comparison");
        check(ReleaseUpdate.newer(ReleaseUpdate.release("v1.0.0",10),"0.999999.999999"),"Major version comparison");
        check(!ReleaseUpdate.newer(next,"unknown"),"Unknown installed version fails closed");
        for(String tag:new String[]{"v0.22.0-rc1","v0.22.0/evil","v01.22.0","0.22.0","v2147483648.0.0","v0.0","v0.22.0?download=1"})check(ReleaseUpdate.release(tag,10)==null,"Reject unsupported tag "+tag);
        check(ReleaseUpdate.release("v0.22.0",0)==null&&ReleaseUpdate.release("v0.22.0",100_000_000)==null,"Bounded APK metadata");
        for(String key:new String[]{"draft","prerelease"})check(ReleaseUpdate.parse(fixture("v0.22.0").put(key,true).toString())==null,"Not a stable published release");
        for(String url:new String[]{"https://evil.test/app.apk","http://github.com/DiegoZC4/RedwoodArrows/releases/download/v0.22.0/RedwoodArrows.apk","https://github.com/Other/App/releases/download/v0.22.0/RedwoodArrows.apk",next.url+"?token=secret"}){JSONObject j=fixture("v0.22.0");j.getJSONArray("assets").getJSONObject(0).put("browser_download_url",url);check(ReleaseUpdate.parse(j.toString())==null,"Reject other origin/repo/URL");}
        JSONObject j=fixture("v0.22.0");j.getJSONArray("assets").getJSONObject(0).put("state","new");check(ReleaseUpdate.parse(j.toString())==null,"Incomplete upload hidden");
        j=fixture("v0.22.0");j.getJSONArray("assets").put(j.getJSONArray("assets").getJSONObject(0));check(ReleaseUpdate.parse(j.toString())==null,"Ambiguous APK hidden");
        check(ReleaseUpdate.parse(fixture("v0.22.0").put("assets",new JSONArray()).toString())==null,"Source-only releases hidden");
        check(ReleaseUpdate.due(blank,now),"First launch checks");
        ReleaseUpdate.State s=ReleaseUpdate.accept(blank,reply(200,fixture("v0.22.0").toString()),now);
        check(blank.release==null&&blank.checked==0,"Result does not mutate request snapshot");
        check(!ReleaseUpdate.due(s,now+1000),"Quick reopen uses cache");
        check(ReleaseUpdate.due(s,now+ReleaseUpdate.CHECK_INTERVAL),"Next eligible launch checks");
        check(ReleaseUpdate.offer(s,"0.21.0",now+1000,true)!=null,"Valid newer cached release offered");
        check(ReleaseUpdate.offer(s,"0.21.0",now+1000,false)==null,"No offline prompt");
        check(ReleaseUpdate.offer(s,"0.22.0-update",now+1000,true)==null,"Notice gone after upgrade");
        check(ReleaseUpdate.offer(s,"0.21.0",now+ReleaseUpdate.MAX_CACHE_AGE,true)==null,"Very stale offer hidden");
        ReleaseUpdate.State disk=ReleaseUpdate.State.read(s.json());check(disk.release.url.equals(next.url)&&disk.etag.equals(s.etag)&&disk.checked==s.checked,"Small cache survives restart");
        long later=now+ReleaseUpdate.CHECK_INTERVAL;ReleaseUpdate.State unchanged=ReleaseUpdate.accept(disk,reply(304,""),later);check(unchanged.release.url.equals(next.url)&&unchanged.checked==later,"Conditional 304 refreshes cache");
        ReleaseUpdate.State notFound=ReleaseUpdate.accept(s,reply(404,""),later);check(notFound.release==null&&notFound.etag.isEmpty(),"Removed release clears button");
        ReleaseUpdate.State same=ReleaseUpdate.accept(s,reply(200,fixture("v0.21.0").toString()),later);check(ReleaseUpdate.offer(same,"0.21.0",later,true)==null,"Server rollback clears newer notice");
        ReleaseUpdate.State limited=ReleaseUpdate.accept(s,new ReleaseUpdate.Reply(429,"","",3600000),later);check(!ReleaseUpdate.due(limited,later+3599999)&&ReleaseUpdate.due(limited,later+3600000),"Respect rate-limit backoff");
        for(int code:new int[]{0,301,302,403,429,500,503}){ReleaseUpdate.State failed=ReleaseUpdate.accept(blank,reply(code,"<html>captive portal</html>"),now);check(failed.release==null&&!ReleaseUpdate.due(failed,now+1000),"HTTP/network failures are quiet and bounded "+code);}
        ReleaseUpdate.State bad=ReleaseUpdate.accept(blank,reply(200,"not json"),now);check(bad.release==null&&bad.checked==0,"Invalid metadata not successful check");
        check(ReleaseUpdate.State.read("invalid").release==null,"Corrupt cache safe");
        check(ReleaseUpdate.safeEtag("\"ok\"").equals("\"ok\"")&&ReleaseUpdate.safeEtag("x\r\nAuthorization: secret").isEmpty(),"ETag cannot inject headers");
        check(ReleaseUpdate.due(s,now-1000)&&ReleaseUpdate.offer(s,"0.21.0",now-1000,true)==null,"Clock rollback does not suppress future checks");
        byte[] oversized=new byte[ReleaseUpdate.MAX_BODY+1];boolean refused=false;try{ReleaseUpdate.body(new ByteArrayInputStream(oversized));}catch(IOException e){refused=true;}check(refused,"Bounded response body");
        check(ReleaseUpdate.body(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))).equals("hello"),"Small UTF-8 response");
        if(args.length>0&&args[0].equals("--live")){ReleaseUpdate.Reply live=new ReleaseUpdate.GitHub().fetch("");check(live.status==200,"Anonymous GitHub API succeeds");ReleaseUpdate.Release r=ReleaseUpdate.parse(live.body);check(r!=null,"Current actual release accepted");System.out.println("Live public release: "+r.tag+"; "+r.bytes+" bytes; no credentials sent");}
        System.out.println("Release update checks passed: "+checks);
    }
}
