package org.redwoodarrows;

import org.json.*;
import java.io.*;
import java.util.*;

/** Durable scan-based outbox. A local commit cannot fall between save and enqueue.
 * Every acknowledgement names the exact bytes sent, not the latest mutable group.
 * The single engine runner is independent of camera/inference work. */
final class SyncEngine {
    interface Transport {JSONObject json(String action,JSONObject input)throws Exception;void upload(String hash,File file)throws Exception;void download(String hash,File file,long size)throws Exception;}
    final Store store;final File directory,stateFile;final Transport remote;JSONObject state,entries;int uploaded,downloaded;
    SyncEngine(Store store,File directory,Transport remote)throws Exception {
        this.store=store;this.directory=directory;this.remote=remote;directory.mkdirs();stateFile=new File(directory,"state.json");
        state=stateFile.isFile()?new JSONObject(SyncFiles.read(stateFile)):new JSONObject().put("cursor",0).put("entries",new JSONObject());entries=state.getJSONObject("entries");
    }
    void bind(String library)throws Exception {
        if(state.has("library")&&!state.getString("library").equals(library))throw new IOException("This phone is linked to another library; nothing was uploaded");
        state.put("library",library);persist();
    }
    private void persist()throws Exception {SyncFiles.atomic(stateFile,state.toString());}
    private JSONObject entry(String id)throws Exception {JSONObject e=entries.optJSONObject(id);if(e==null){e=new JSONObject().put("revision",0).put("fingerprint","");entries.put(id,e);}return e;}
    private void ack(String id,long revision,String fingerprint)throws Exception {entries.put(id,new JSONObject().put("revision",revision).put("fingerprint",fingerprint));persist();}
    private File groupFile(String id)throws Exception {return new File(new File(store.root,SyncFiles.id(id)),"group.json");}
    private SyncFiles.Snapshot local(String id)throws Exception {synchronized(Store.FILE_LOCK){File f=groupFile(id);return f.isFile()?SyncFiles.snapshot(store,store.read(f)):null;}}
    private void conflict(String id,JSONObject wire)throws Exception {SyncFiles.atomic(new File(directory,"conflict-"+SyncFiles.id(id)+".json"),wire.toString());}
    private File conflictFile(String id)throws Exception {return new File(directory,"conflict-"+SyncFiles.id(id)+".json");}
    private void clearConflict(String id)throws Exception {File f=conflictFile(id);if(f.exists()&&!f.delete())throw new IOException("Could not clear the resolved conflict");}
    void run()throws Exception {
        bind(remote.json("me",null).getString("library"));
        JSONObject deferred=state.optJSONObject("deferred");if(deferred==null){deferred=new JSONObject();state.put("deferred",deferred);}
        List<String> waiting=new ArrayList<>();Iterator<String> keys=deferred.keys();while(keys.hasNext())waiting.add(keys.next());
        for(String id:waiting){interrupted();pull(remote.json("group&id="+SyncFiles.id(id),null),deferred);persist();}
        // Pull before push. Shared shooter IDs arrive along with the group snapshots.
        int pages=0;boolean more;
        do{
            JSONArray changes=remote.json("changes&after="+state.optLong("cursor"),null).getJSONArray("changes");more=changes.length()==50;
            for(int i=0;i<changes.length();i++){
                interrupted();JSONObject change=changes.getJSONObject(i);String id=SyncFiles.id(change.getString("id"));
                JSONObject wire=remote.json("group&id="+id,null);pull(wire,deferred);
                state.put("cursor",change.getLong("revision"));persist();
            }
        }while(more&&++pages<20); // Bounded jobs; the next run continues the saved cursor.
        if(more)throw new IOException("More groupings remain; continuing in the next sync job");
        for(Store.Group group:store.list()){
            interrupted();if(!group.hasData()||(!group.keepPhotos&&!group.coordinatesOnly()&&!group.views.isEmpty()))continue;
            String id=group.id;if(conflictFile(id).isFile())continue;
            SyncFiles.Snapshot snapshot=local(id);JSONObject base=entry(id);
            if(snapshot==null||snapshot.fingerprint.equals(base.optString("fingerprint")))continue;
            try{push(snapshot,base.optLong("revision"));}catch(SyncHttp.Failure e){if(e.status!=409)throw e;conflict(id,remote.json("group&id="+id,null));}
        }
        state.put("last_success",System.currentTimeMillis());persist();
    }
    private void pull(JSONObject wire,JSONObject deferred)throws Exception {
        SyncFiles.Snapshot incoming=SyncFiles.Snapshot.remote(wire);String id=incoming.id;
        SyncFiles.Snapshot now=local(id);JSONObject base=entry(id);
        if(now!=null&&now.fingerprint.equals(incoming.fingerprint)){ack(id,wire.getLong("revision"),incoming.fingerprint);clearConflict(id);}
        else if(now!=null&&incoming.fingerprint.equals(base.optString("fingerprint"))){/* Echo of a previously acknowledged write; keep newer local edits queued. */}
        else if(now!=null&&!now.fingerprint.equals(base.optString("fingerprint")))conflict(id,wire);
        else if(id.equals(Store.editingGroupId)){deferred.put(id,true);return;}
        else if(!apply(wire,now==null?null:now.fingerprint)){if(id.equals(Store.editingGroupId)){deferred.put(id,true);return;}conflict(id,wire);}
        deferred.remove(id);
    }
    private static void interrupted()throws InterruptedIOException {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Sync paused; saved locally");}
    private void push(SyncFiles.Snapshot snapshot,long revision)throws Exception {
        JSONArray hashes=new JSONArray();Map<String,JSONObject> photos=new HashMap<>();
        for(int i=0;i<snapshot.photos.length();i++){JSONObject p=snapshot.photos.getJSONObject(i);hashes.put(p.getString("sha256"));photos.put(p.getString("sha256"),p);}
        JSONArray missing=remote.json("missing",new JSONObject().put("hashes",hashes)).getJSONArray("missing");
        for(int i=0;i<missing.length();i++){
            interrupted();String hash=missing.getString(i);JSONObject p=photos.get(hash);if(p==null)throw new IOException("Unexpected server photo request");
            File file=new File(groupFile(snapshot.id).getParentFile(),SyncFiles.photo(p.getString("file")));
            if(!hash.equals(SyncFiles.hash(file)))throw new IOException("Photo changed while syncing; retrying later");remote.upload(hash,file);
        }
        JSONObject accepted=remote.json("save",snapshot.wire(revision));
        if(!snapshot.fingerprint.equals(accepted.getString("fingerprint")))throw new IOException("Server acknowledgement did not match this grouping");
        ack(snapshot.id,accepted.getLong("revision"),snapshot.fingerprint);uploaded++;
    }
    private boolean apply(JSONObject wire,String expectedLocal)throws Exception {
        SyncFiles.Snapshot incoming=SyncFiles.Snapshot.remote(wire);File dest=groupFile(incoming.id),folder=dest.getParentFile();folder.mkdirs();
        // Immutable image names may be installed first. A crash leaves harmless,
        // verified orphan files, never a visible half-downloaded grouping.
        for(int i=0;i<incoming.photos.length();i++){
            interrupted();JSONObject p=incoming.photos.getJSONObject(i);String hash=p.getString("sha256");File photo=new File(folder,SyncFiles.photo(p.getString("file")));
            if(photo.isFile()){if(!SyncFiles.hash(photo).equals(hash))throw new IOException("A local photo differs; both copies were preserved on their devices");continue;}
            File partial=new File(directory,"download.part");try{remote.download(hash,partial,p.getLong("bytes"));if(partial.length()!=p.getLong("bytes")||!SyncFiles.hash(partial).equals(hash))throw new IOException("Photo checksum failed");synchronized(Store.FILE_LOCK){if(photo.exists()){if(!SyncFiles.hash(photo).equals(hash))throw new IOException("Photo changed during download");}else if(!partial.renameTo(photo))throw new IOException("Could not save the downloaded photo");}}finally{if(partial.exists())partial.delete();}
        }
        synchronized(Store.FILE_LOCK){
            if(incoming.id.equals(Store.editingGroupId))return false;
            SyncFiles.Snapshot now=local(incoming.id);String current=now==null?null:now.fingerprint;
            if(!Objects.equals(current,expectedLocal))return false;
            SyncFiles.atomic(dest,incoming.body);ack(incoming.id,wire.getLong("revision"),incoming.fingerprint);clearConflict(incoming.id);downloaded++;return true;
        }
    }
    List<String> conflicts(){List<String> list=new ArrayList<>();File[] files=directory.listFiles();if(files!=null)for(File f:files)if(f.getName().startsWith("conflict-")&&f.getName().endsWith(".json"))list.add(f.getName().substring(9,f.getName().length()-5));Collections.sort(list);return list;}
    /** An explicit choice, never an automatic last-writer-wins overwrite. */
    void resolve(String id,boolean keepLocal)throws Exception {
        if(!conflictFile(id).isFile())throw new IOException("This conflict is already resolved");
        resolve(id,keepLocal,new JSONObject(SyncFiles.read(conflictFile(id))).getLong("revision"));
    }
    void resolve(String id,boolean keepLocal,long reviewedRevision)throws Exception {
        if(!conflictFile(id).isFile())throw new IOException("This conflict is already resolved");
        JSONObject latest=remote.json("group&id="+SyncFiles.id(id),null);
        if(reviewedRevision!=latest.getLong("revision")){conflict(id,latest);throw new IOException("The shared version changed again. Review both versions before choosing.");}
        SyncFiles.Snapshot.remote(latest);SyncFiles.Snapshot now=local(id);
        if(now==null)throw new IOException("Local grouping is missing");
        // Keep a private recovery copy of the local manifest; photos are immutable
        // and retained. Server versions also retain every acknowledged revision.
        File backups=new File(directory,"recovery");backups.mkdirs();
        SyncFiles.atomic(new File(backups,id+"-"+now.fingerprint+".json"),now.wire(entry(id).optLong("revision")).toString());
        if(keepLocal){push(now,latest.getLong("revision"));clearConflict(id);}
        else if(!apply(latest,now.fingerprint))throw new IOException("The grouping changed during your choice; reopen the conflict");
    }
}
