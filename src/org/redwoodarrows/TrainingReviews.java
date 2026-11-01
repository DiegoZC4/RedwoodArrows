package org.redwoodarrows;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Photo-space review journals. The reviewed collection also carries them atomically with the group. */
final class TrainingReviews {
    static File file(Store store,String group,String view)throws IOException {
        return new File(new File(new File(store.root.getParentFile(),"training-reviews"),SyncFiles.id(group)),SyncFiles.id(view)+".json");
    }
    static File pending(File file){return new File(file.getParentFile(),file.getName().replace(".json",".pending"));}
    static String digest(File file)throws Exception{return file.exists()?SyncFiles.hash(file):"";}
    static JSONArray points(PhotoAssignments model)throws Exception {
        Store.View reference=model.reference();TreeMap<Integer,JSONObject> points=new TreeMap<>();
        if(reference.width<=0||reference.height<=0)throw new IOException("Photo dimensions unavailable");
        for(Store.View view:model.family()){
            if(view.width!=reference.width||view.height!=reference.height)throw new IOException("Photo aliases have different dimensions");
            for(Store.Observation mark:view.observations){
                if(mark.line)throw new IOException("Training reviews require entry dots, not shaft lines");
                if(mark.id<1||mark.id>999||mark.a==null||mark.a.length!=2)throw new IOException("Invalid arrow entry");
                double x=mark.a[0],y=mark.a[1];
                if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||y<0||x>=reference.width||y>=reference.height)throw new IOException("Entry dot is outside the photo");
                JSONObject old=points.get(mark.id);
                if(old!=null){if(old.getDouble("x")!=x||old.getDouble("y")!=y)throw new IOException("Conflicting entry dots share an arrow number");continue;}
                JSONObject point=new JSONObject().put("arrow",mark.id).put("x",x==0?0.0:x).put("y",y==0?0.0:y).put("source",mark.source);
                if(Double.isFinite(mark.confidence))point.put("detector_score",mark.confidence);points.put(mark.id,point);
            }
        }
        return new JSONArray(points.values());
    }
    static String fingerprint(String image,int width,int height,JSONArray points)throws Exception {
        TreeMap<Integer,JSONObject> sorted=new TreeMap<>();
        for(int i=0;i<points.length();i++){JSONObject p=points.getJSONObject(i);if(sorted.put(p.getInt("arrow"),p)!=null)throw new IOException("Duplicate review entry");}
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(bytes)){
            out.write(("redwood-dot-review-v1\n"+image+"\n").getBytes(StandardCharsets.US_ASCII));
            out.writeInt(width);out.writeInt(height);out.writeInt(sorted.size());
            for(JSONObject p:sorted.values()){double x=p.getDouble("x"),y=p.getDouble("y");out.writeInt(p.getInt("arrow"));out.writeDouble(x==0?0.0:x);out.writeDouble(y==0?0.0:y);}
        }
        return SyncFiles.hash(bytes.toByteArray());
    }
    static String imageHash(Store store,PhotoAssignments model)throws Exception {
        String hash="";for(Store.View view:model.family()){
            String source=view.file;Set<String> seen=new HashSet<>();
            while(model.copies.containsKey(source)){if(!seen.add(source))throw new IOException("Invalid photo aliases");source=model.copies.get(source);}
            File photo=new File(store.directory(model.draft),SyncFiles.photo(source));
            if(!photo.isFile())throw new IOException("Original photo is missing");
            String actual=SyncFiles.hash(photo);if(!hash.isEmpty()&&!hash.equals(actual))throw new IOException("Photo aliases have different image bytes");hash=actual;
            File alias=new File(store.directory(model.draft),SyncFiles.photo(view.file));
            if(alias.exists()&&!alias.equals(photo)&&!actual.equals(SyncFiles.hash(alias)))throw new IOException("Photo alias changed");
        }
        return hash;
    }
    static final class Session {
        final String groupId,viewId,photoFile,imageHash;final int width,height;final File file;
        JSONObject record;String diskDigest,checked="";boolean initiallyChecked,fresh;
        Session(Store store,PhotoAssignments model)throws Exception {
            Store.View canonical=model.family().get(0);groupId=model.draft.id;viewId=canonical.id;photoFile=canonical.file;width=canonical.width;height=canonical.height;
            JSONArray current=points(model);imageHash=imageHash(store,model);file=file(store,groupId,viewId);diskDigest=digest(file);
            JSONObject shared=model.draft.photoReviews==null?null:model.draft.photoReviews.optJSONObject(viewId);fresh=diskDigest.isEmpty()&&shared==null;
            if(fresh){record=new JSONObject().put("schema",1).put("group_id",groupId).put("view_id",viewId).put("photo_file",photoFile).put("image_sha256",imageHash).put("width",width).put("height",height).put("group_created_ms",model.draft.created).put("captured_at_ms",canonical.capturedAt).put("revision",0);
                record.put("baseline",new JSONObject().put("kind","review-start").put("observed_at_ms",System.currentTimeMillis()).put("points",current));
            }else{
                record=shared==null?new JSONObject(SyncFiles.read(file)):new JSONObject(shared.toString());
                if(record.getInt("schema")!=1||!groupId.equals(record.getString("group_id"))||!viewId.equals(record.getString("view_id"))||!photoFile.equals(record.getString("photo_file"))||!imageHash.equals(record.getString("image_sha256"))||width!=record.getInt("width")||height!=record.getInt("height"))throw new IOException("Saved training review belongs to a different photo");
                JSONObject latest=record.optJSONObject("latest");
                if(latest!=null&&Boolean.TRUE.equals(latest.opt("complete"))&&!pending(file).exists()){
                    String saved=latest.getString("fingerprint");
                    if(saved.equals(fingerprint(imageHash,width,height,latest.getJSONArray("points")))&&saved.equals(fingerprint(imageHash,width,height,current)))checked=saved;
                }
            }
            initiallyChecked=!checked.isEmpty();
        }
        String current(PhotoAssignments model)throws Exception {
            Store.View canonical=model.family().get(0);
            if(!groupId.equals(model.draft.id)||!viewId.equals(canonical.id)||!photoFile.equals(canonical.file)||width!=canonical.width||height!=canonical.height)throw new IOException("Photo identity changed; reopen the review");
            return fingerprint(imageHash,width,height,points(model));
        }
        boolean checked(PhotoAssignments model){try{if(!checked.isEmpty()&&!checked.equals(current(model)))checked="";}catch(Exception invalid){checked="";}return !checked.isEmpty();}
        void setChecked(PhotoAssignments model,boolean complete)throws Exception {checked=complete?current(model):"";}
        boolean dirty(PhotoAssignments model){return checked(model)!=initiallyChecked;}
        void detectorBaseline(List<double[]> hits,String modelHash,double threshold)throws Exception {
            if(!fresh||!record.getJSONObject("baseline").getString("kind").equals("review-start")||modelHash==null||!modelHash.matches("[a-f0-9]{64}")||!Double.isFinite(threshold))return;
            JSONArray dots=new JSONArray();int id=0;
            for(double[] hit:hits){if(hit.length<3||!Double.isFinite(hit[0])||!Double.isFinite(hit[1])||!Double.isFinite(hit[2])||hit[0]<0||hit[1]<0||hit[0]>=width||hit[1]>=height||id>=999)continue;
                dots.put(new JSONObject().put("arrow",++id).put("x",hit[0]).put("y",hit[1]).put("source","impact-model:inference").put("detector_score",hit[2]));}
            record.put("baseline",new JSONObject().put("kind","detector-output").put("observed_at_ms",System.currentTimeMillis()).put("model_sha256",modelHash).put("threshold",threshold).put("points",dots));
        }
        Store.Group save(Store store,PhotoAssignments model)throws Exception {
            synchronized(Store.FILE_LOCK){
                String fingerprint=current(model);boolean complete=checked(model);
                if(!imageHash.equals(imageHash(store,model)))throw new IOException("Photo changed; reopen the review");
                if(!diskDigest.equals(digest(file)))throw new IOException("Review changed elsewhere; reopen the photo");
                JSONObject next=new JSONObject(record.toString());next.put("revision",record.getLong("revision")+1);
                next.put("latest",new JSONObject().put("saved_at_ms",System.currentTimeMillis()).put("complete",complete).put("fingerprint",fingerprint).put("points",points(model)));
                byte[] bytes=next.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>SyncFiles.JSON_LIMIT)throw new IOException("Training review is too large");
                // A pending marker makes interrupted two-file commits ineligible for export.
                File marker=pending(file);SyncFiles.atomic(marker,"pending");
                if(complete)for(Store.View view:model.family())if(!view.detectionState.isEmpty())view.detectionState="edited";
                if(model.draft.photoReviews==null)model.draft.photoReviews=new JSONObject();
                model.draft.photoReviews.put(viewId,new JSONObject(next.toString()));
                // Store listeners may throw after the group rename. Keep the marker
                // on every ambiguous failure, including failures before that rename.
                Store.Group saved=PhotoReview.save(model,store);
                try{SyncFiles.atomic(file,bytes);diskDigest=SyncFiles.hash(bytes);record=next;fresh=false;
                    if(!marker.delete())throw new IOException("Review marker could not be cleared");
                }catch(Exception failure){throw new IOException("Photo saved; training status not saved. Tap to retry.",failure);}
                initiallyChecked=complete;
                return saved;
            }
        }
    }
    static Session open(Store store,PhotoAssignments model)throws Exception {synchronized(Store.FILE_LOCK){return new Session(store,model);}}
    static Set<String> checkedPhotos(Store store,Store.Group group){
        Set<String> checked=new HashSet<>();
        synchronized(Store.FILE_LOCK){for(int at:PhotoAssignments.photoIndices(group))try{
            Store.View view=group.views.get(at);
            // Unreviewed photos need no image I/O. Existing labels are verified
            // against the real JPEG and current dots before showing a checkmark.
            if(!file(store,group.id,view.id).isFile()&&(group.photoReviews==null||!group.photoReviews.has(view.id)))continue;
            PhotoAssignments model=new PhotoAssignments(group,view.id);
            if(open(store,model).checked(model))checked.add(view.id);
        }catch(Exception invalid){/* Invalid or interrupted labels need review. */}}
        return checked;
    }
    static JSONObject forSync(Store store,Store.Group group)throws Exception {
        JSONObject records=new JSONObject();
        synchronized(Store.FILE_LOCK){for(int at:PhotoAssignments.photoIndices(group)){
            Store.View view=group.views.get(at);File local=file(store,group.id,view.id);
            if(pending(local).exists())throw new IOException("A photo review is still saving; sync will retry");
            if(!local.isFile()&&(group.photoReviews==null||!group.photoReviews.has(view.id)))continue;
            PhotoAssignments model=new PhotoAssignments(group,view.id);Session session=open(store,model);
            JSONObject record=new JSONObject(session.record.toString());JSONObject latest=record.optJSONObject("latest");
            if(latest!=null)latest.put("complete",session.checked(model));
            records.put(view.id,record);
        }}
        return records;
    }
    static void validatePoints(JSONArray points,int width,int height)throws Exception {
        if(points.length()>999)throw new IOException("Too many reviewed entries");
        Set<Integer> ids=new HashSet<>();
        for(int i=0;i<points.length();i++){JSONObject point=points.getJSONObject(i);int id=point.getInt("arrow");double x=point.getDouble("x"),y=point.getDouble("y");
            if(id<1||id>999||!ids.add(id)||!Double.isFinite(x)||!Double.isFinite(y)||x<0||y<0||x>=width||y>=height||!(point.get("source") instanceof String))throw new IOException("Invalid reviewed entry");
            if(point.has("detector_score")&&!Double.isFinite(point.getDouble("detector_score")))throw new IOException("Invalid reviewed detector score");
        }
    }
    static void validateWire(Store.Group group,JSONArray photos)throws Exception {
        if(group.photoReviews==null)return;
        Map<String,String> hashes=new HashMap<>();for(int i=0;i<photos.length();i++){JSONObject p=photos.getJSONObject(i);hashes.put(p.getString("file"),p.getString("sha256"));}
        Set<String> remaining=new HashSet<>();Iterator<String> keys=group.photoReviews.keys();while(keys.hasNext())remaining.add(keys.next());
        for(int at:PhotoAssignments.photoIndices(group)){
            Store.View view=group.views.get(at);if(!remaining.remove(view.id))continue;
            JSONObject record=group.photoReviews.getJSONObject(view.id);String hash=hashes.get(view.file);
            if(record.getInt("schema")!=1||!group.id.equals(record.getString("group_id"))||!view.id.equals(record.getString("view_id"))||!view.file.equals(record.getString("photo_file"))||!Objects.equals(hash,record.getString("image_sha256"))||view.width!=record.getInt("width")||view.height!=record.getInt("height")||group.created!=record.getLong("group_created_ms")||view.capturedAt!=record.getLong("captured_at_ms")||record.getLong("revision")<1)throw new IOException("Shared review photo identity does not match");
            PhotoAssignments model=new PhotoAssignments(group,view.id);
            for(Store.View alias:model.family())if(!Objects.equals(hash,hashes.get(alias.file)))throw new IOException("Shared review aliases differ");
            JSONObject latest=record.getJSONObject("latest"),baseline=record.getJSONObject("baseline");
            if(!(latest.get("complete") instanceof Boolean)||latest.getLong("saved_at_ms")<0||baseline.getLong("observed_at_ms")<0||!Arrays.asList("detector-output","review-start").contains(baseline.getString("kind")))throw new IOException("Invalid shared review status");
            validatePoints(latest.getJSONArray("points"),view.width,view.height);validatePoints(baseline.getJSONArray("points"),view.width,view.height);
            String saved=latest.getString("fingerprint");
            if(!saved.equals(fingerprint(hash,view.width,view.height,latest.getJSONArray("points")))||(latest.getBoolean("complete")&&!saved.equals(fingerprint(hash,view.width,view.height,points(model)))))throw new IOException("Shared review does not match its arrow dots");
        }
        if(!remaining.isEmpty())throw new IOException("Shared review has an unknown photo");
    }
    static Store.Group confirm(Store store,Store.Group displayed,String photo,boolean complete)throws Exception {
        synchronized(Store.FILE_LOCK){
            File saved=new File(store.directory(displayed),"group.json");
            if(displayed.diskDigest==null||!saved.isFile()||!displayed.diskDigest.equals(SyncFiles.hash(saved)))throw new IOException("This photo changed. Reopen it before checking the dots.");
            PhotoAssignments model=new PhotoAssignments(displayed,photo);
            if(complete)for(Store.View view:model.family())if(view.detectionState.equals("pending"))throw new IOException("Arrows are still being detected. Please check them when detection finishes.");
            Session session=open(store,model);
            if(session.checked(model)==complete&&!pending(session.file).exists())return displayed;
            session.setChecked(model,complete);return session.save(store,model);
        }
    }
    static void recordDetection(Store store,Store.Group group,Store.View view,List<double[]> raw,String modelHash,double threshold)throws Exception {
        synchronized(Store.FILE_LOCK){PhotoAssignments model=new PhotoAssignments(group,view.id);Session session=open(store,model);if(!session.fresh)return;
            session.detectorBaseline(raw,modelHash,threshold);
            if(!session.record.getJSONObject("baseline").getString("kind").equals("detector-output"))return;
            session.record.put("revision",1).put("latest",new JSONObject().put("saved_at_ms",System.currentTimeMillis()).put("complete",false).put("fingerprint",session.current(model)).put("points",points(model)));
            SyncFiles.atomic(session.file,session.record.toString());
        }
    }
}
