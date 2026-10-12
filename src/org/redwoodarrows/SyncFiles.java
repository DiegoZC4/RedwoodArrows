package org.redwoodarrows;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Bounded, deterministic wire format; no device paths, EXIF originals or secrets. */
final class SyncFiles {
    static final int JSON_LIMIT=2*1024*1024,PHOTO_LIMIT=8*1024*1024;
    static String id(String value)throws IOException {if(value==null||!value.matches("[A-Za-z0-9_-]{1,80}"))throw new IOException("Invalid grouping ID");return value;}
    static String photo(String value)throws IOException {if(value==null||!value.matches("[A-Za-z0-9_-]{1,80}\\.jpg"))throw new IOException("Invalid photo name");return value;}
    static String hash(byte[] data)throws Exception {return hex(MessageDigest.getInstance("SHA-256").digest(data));}
    static String hash(File file)throws Exception {MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}return hex(md.digest());}
    static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format(Locale.US,"%02x",v&255));return b.toString();}
    static byte[] bytes(InputStream in,int limit)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("Sync response exceeds the size limit");out.write(b,0,n);}return out.toByteArray();}
    static String read(File f)throws IOException {try(InputStream in=new FileInputStream(f)){return new String(bytes(in,JSON_LIMIT),StandardCharsets.UTF_8);}}
    static void atomic(File file,String value)throws IOException {atomic(file,value.getBytes(StandardCharsets.UTF_8));}
    static void atomic(File file,byte[] value)throws IOException {
        file.getParentFile().mkdirs();File tmp=new File(file.getParentFile(),file.getName()+".new");
        try(FileOutputStream out=new FileOutputStream(tmp)){out.write(value);out.getFD().sync();}
        if(!tmp.renameTo(file))throw new IOException("Could not commit synced data");
    }
    static String canonical(Object value)throws JSONException {
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;List<String> keys=new ArrayList<>();Iterator<String> i=o.keys();while(i.hasNext())keys.add(i.next());Collections.sort(keys);StringBuilder s=new StringBuilder("{");for(String k:keys){if(s.length()>1)s.append(',');s.append(JSONObject.quote(k)).append(':').append(canonical(o.get(k)));}return s.append('}').toString();}
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;StringBuilder s=new StringBuilder("[");for(int i=0;i<a.length();i++){if(i>0)s.append(',');s.append(canonical(a.get(i)));}return s.append(']').toString();}
        if(value==null||value==JSONObject.NULL)return "null";
        if(value instanceof String)return JSONObject.quote((String)value);
        if(value instanceof Number)return JSONObject.numberToString((Number)value);
        return value.toString();
    }
    static final class Snapshot {
        final String id,body,fingerprint;final JSONArray photos;
        Snapshot(String id,String body,JSONArray photos)throws Exception {
            this.id=SyncFiles.id(id);this.body=body;this.photos=photos;
            StringBuilder signed=new StringBuilder(body).append('\n');String previous="";
            for(int i=0;i<photos.length();i++){
                JSONObject p=photos.getJSONObject(i);String f=photo(p.getString("file")),h=p.getString("sha256");long size=p.getLong("bytes");
                if(f.compareTo(previous)<=0||!h.matches("[a-f0-9]{64}")||size<1||size>PHOTO_LIMIT)throw new IOException("Invalid photo manifest");
                if(i>0)signed.append('\n');signed.append(f).append(':').append(h).append(':').append(size);previous=f;
            }
            if(body.getBytes(StandardCharsets.UTF_8).length>1024*1024||photos.length()>64)throw new IOException("Grouping exceeds sync limits");
            JSONObject group=new JSONObject(body);Store.Group parsed=Store.Group.from(group);
            if(!parsed.id.equals(id)||parsed.views.size()!=photos.length()||!parsed.pendingPhotoCleanup.isEmpty())throw new IOException("Invalid shared grouping");
            Set<String> refs=new HashSet<>();for(Store.View v:parsed.views)if(!refs.add(photo(v.file)))throw new IOException("Repeated photo reference");
            for(int i=0;i<photos.length();i++)if(!refs.remove(photos.getJSONObject(i).getString("file")))throw new IOException("Unexpected shared photo");
            fingerprint=hash(signed.toString().getBytes(StandardCharsets.UTF_8));
        }
        JSONObject wire(long base)throws Exception {return new JSONObject().put("id",id).put("body",body).put("photos",photos).put("fingerprint",fingerprint).put("base",base);}
        static Snapshot remote(JSONObject wire)throws Exception {
            Snapshot s=new Snapshot(wire.getString("id"),wire.getString("body"),wire.getJSONArray("photos"));
            if(!s.fingerprint.equals(wire.getString("fingerprint")))throw new IOException("Shared grouping checksum failed");return s;
        }
    }
    static Snapshot snapshot(Store store,Store.Group g)throws Exception {
        JSONObject j=g.json();j.remove("pending_photo_cleanup");
        List<Store.View> views=new ArrayList<>(g.views);views.sort(Comparator.comparing(v->v.file));JSONArray photos=new JSONArray();
        for(Store.View v:views){File f=new File(store.directory(g),photo(v.file));
            if(!f.getCanonicalFile().getParentFile().equals(store.directory(g).getCanonicalFile())||!f.isFile()||f.length()>PHOTO_LIMIT)throw new IOException("A saved grouping photo is missing or too large");
            photos.put(new JSONObject().put("file",v.file).put("sha256",hash(f)).put("bytes",f.length()));
        }
        return new Snapshot(g.id,canonical(j),photos);
    }
}
