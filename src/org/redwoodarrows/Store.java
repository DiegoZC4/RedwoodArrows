package org.redwoodarrows;

import android.content.Context;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;

/** Private, atomic local storage. Sync is optional and never gates a local save. */
public final class Store {
    static final Object FILE_LOCK=new Object();
    static volatile String editingGroupId;
    static volatile Runnable onChanged;
    static void changed(){Runnable listener=onChanged;if(listener!=null)listener.run();}
    final File root;
    public Store(Context c){this(c.getFilesDir());}
    Store(File appFiles){root=new File(appFiles,"groups");root.mkdirs();try{BulkLabels.recover(this);}catch(Exception e){throw new IllegalStateException("Interrupted label edit needs recovery; saved versions are preserved",e);}}
    public static JSONArray array(double[] d)throws JSONException {JSONArray a=new JSONArray();for(double v:d)a.put(Double.isFinite(v)?v:0);return a;}
    public static double[] doubles(JSONArray a){if(a==null)return null;double[] d=new double[a.length()];for(int i=0;i<d.length;i++)d[i]=a.optDouble(i);return d;}
    public static final class Observation {
        public int id;public boolean line;public double[] a,b;
        public String source="manual",associationWarning="";public double confidence=Double.NaN;
        Observation(int id,double[] a,double[] b){this.id=id;this.a=a;this.b=b;line=b!=null;}
        JSONObject json()throws JSONException {JSONObject j=new JSONObject().put("arrow",id).put("kind",line?"shaft":"impact").put("a",array(a)).put("source",source).put("association_warning",associationWarning);if(line)j.put("b",array(b));if(Double.isFinite(confidence))j.put("detector_score",confidence);return j;}
        static Observation from(JSONObject j)throws JSONException{Observation o=new Observation(j.getInt("arrow"),doubles(j.getJSONArray("a")),doubles(j.optJSONArray("b")));o.source=j.optString("source","manual");o.associationWarning=j.optString("association_warning");o.confidence=j.optDouble("detector_score",Double.NaN);return o;}
    }
    public static final class View {
        public String file,id=UUID.randomUUID().toString(),session="",sourceHash="",detectionState="",detectionOwner="";
        public int width,height,rawWidth,rawHeight,rotation;
        public long timestamp,cameraTimestamp,capturedAt;
        public double[] pose,k;
        public Geometry.Calibration calibration;
        public final List<Observation> observations=new ArrayList<>();
        public final List<double[]> rimMarks=new ArrayList<>(),innerMarks=new ArrayList<>();
        public JSONObject json()throws JSONException {
            JSONObject j=new JSONObject().put("id",id).put("file",file).put("width",width).put("height",height).put("rawWidth",rawWidth).put("rawHeight",rawHeight).put("rotation",rotation).put("timestamp_ns",timestamp).put("camera_timestamp_ns",cameraTimestamp).put("session",session);
            if(!sourceHash.isEmpty())j.put("source_sha256",sourceHash);if(capturedAt>0)j.put("captured_at_ms",capturedAt);
            if(!detectionState.isEmpty())j.put("photo_detection",detectionState).put("photo_detection_owner",detectionOwner);
            if(pose!=null)j.put("camera_pose_row_major",array(pose));if(k!=null)j.put("intrinsics",array(k));
            if(calibration!=null)j.put("calibration",new JSONObject().put("h_image_to_radius",array(calibration.h)).put("radial_fit_rms_radius",Double.isFinite(calibration.rms)?calibration.rms:JSONObject.NULL).put("source",calibration.source).put("reviewed",calibration.reviewed));
            JSONArray a=new JSONArray();for(Observation o:observations)a.put(o.json());j.put("observations",a);JSONArray rim=new JSONArray(),inside=new JSONArray();for(double[] p:rimMarks)rim.put(array(p));for(double[] p:innerMarks)inside.put(array(p));return j.put("rim_marks",rim).put("inner_marks",inside);
        }
        static View from(JSONObject j)throws JSONException {
            View v=new View();v.id=j.getString("id");v.file=j.getString("file");v.width=j.getInt("width");v.height=j.getInt("height");v.rawWidth=j.optInt("rawWidth");v.rawHeight=j.optInt("rawHeight");v.rotation=j.optInt("rotation");v.timestamp=j.optLong("timestamp_ns");v.session=j.optString("session");v.pose=doubles(j.optJSONArray("camera_pose_row_major"));v.k=doubles(j.optJSONArray("intrinsics"));
            v.cameraTimestamp=j.optLong("camera_timestamp_ns");
            v.detectionState=j.optString("photo_detection");v.detectionOwner=j.optString("photo_detection_owner");
            v.sourceHash=j.optString("source_sha256");if(!v.sourceHash.isEmpty()&&!v.sourceHash.matches("[a-f0-9]{64}"))throw new JSONException("Invalid source photo digest");v.capturedAt=Math.max(0,j.optLong("captured_at_ms"));
            JSONObject c=j.optJSONObject("calibration");if(c!=null){v.calibration=new Geometry.Calibration(doubles(c.getJSONArray("h_image_to_radius")),c.optDouble("radial_fit_rms_radius"),c.optString("source"));v.calibration.reviewed=c.optBoolean("reviewed");}
            JSONArray a=j.optJSONArray("observations");if(a!=null)for(int i=0;i<a.length();i++)v.observations.add(Observation.from(a.getJSONObject(i)));a=j.optJSONArray("rim_marks");if(a!=null)for(int i=0;i<a.length();i++)v.rimMarks.add(doubles(a.getJSONArray(i)));a=j.optJSONArray("inner_marks");if(a!=null)for(int i=0;i<a.length();i++)v.innerMarks.add(doubles(a.getJSONArray(i)));return v;
        }
        public double[] rotationMatrix(){return Geometry.imageRotation(rotation,rawWidth,rawHeight);}
    }
    public static final class Group {
        String diskDigest;
        public String id=UUID.randomUUID().toString(),name="",type="Field";
        public ShooterRoster.Person shooter=ShooterRoster.UNASSIGNED;
        public int diameterCm=35;public long created=System.currentTimeMillis();
        public TargetPreset target;
        Practice practice=new Practice();
        public final List<View> views=new ArrayList<>();
        // Captured when the grouping starts. Changing the global preference
        // never converts an older test grouping or removes its evidence.
        public boolean keepPhotos=true;
        public List<Result> savedResults;
        public int sourceViewCount;
        private String savedAxes="";
        final List<String> pendingPhotoCleanup=new ArrayList<>();
        public boolean coordinatesOnly(){return savedResults!=null;}
        public boolean hasData(){return !views.isEmpty()||coordinatesOnly()||practice.fired>0;}
        public TargetPreset preset(){return target==null?TargetCatalog.legacy(type,diameterCm):target;}
        public double radiusMm(){return preset().scaleMm();}
        public String targetLabel(){return preset().name;}
        public static Group start(TargetPreset target,ShooterRoster.Person shooter){Group g=new Group();g.target=target;g.type=target.family;g.diameterCm=(int)Math.round(target.diameterMm/10);g.shooter=shooter;return g;}
        public String axes(){
            if(!savedAxes.isEmpty())return savedAxes;
            boolean imageUp=!views.isEmpty()&&views.get(0).calibration!=null&&views.get(0).calibration.source.contains("reference image-up");
            return preset().circular()?(imageUp?"x right, y up; up follows the reference image":"x right, y up; first reference rim mark defines up"):"x right, y up; origin is center of untrimmed paper";
        }
        public JSONObject json()throws JSONException {
            JSONObject j=new JSONObject().put("schema",4).put("id",id).put("name",name).put("created",created).put("shooter",shooter.json()).put("target_type",type).put("diameter_cm",diameterCm).put("target_preset",presetJson(preset())).put("axes",axes()).put("measurement_notice","Estimates, not certified accuracy. Calibration and cross-view residuals are not measurement uncertainty.")
                .put("keep_test_photos",keepPhotos).put("storage",coordinatesOnly()?"coordinates_only":keepPhotos?"test_photos":"coordinates_pending");
            if(coordinatesOnly()){
                JSONArray measurements=new JSONArray();for(Result result:savedResults)measurements.put(result.json());
                JSONArray cleanup=new JSONArray();for(String file:pendingPhotoCleanup)cleanup.put(file);
                j.put("measurements_mm",measurements).put("source_view_count",sourceViewCount).put("pending_photo_cleanup",cleanup);
            }
            JSONArray a=new JSONArray();for(View v:views)a.put(v.json());return j.put("views",a).put("practice",practice.json());
        }
        static Group from(JSONObject j)throws JSONException {
            int schema=j.optInt("schema",1);if(schema<1||schema>4)throw new JSONException("Unsupported grouping version");
            Group g=new Group();g.id=j.getString("id");g.name=j.optString("name");g.shooter=ShooterRoster.Person.from(j.optJSONObject("shooter"));g.created=j.getLong("created");g.type=j.getString("target_type");g.diameterCm=j.getInt("diameter_cm");if(j.has("target_preset"))g.target=presetFrom(j.getJSONObject("target_preset"));JSONArray a=j.getJSONArray("views");for(int i=0;i<a.length();i++)g.views.add(View.from(a.getJSONObject(i)));
            if(schema>=4){
                g.keepPhotos=j.optBoolean("keep_test_photos",true);
                if(j.has("measurements_mm")){
                    if(g.keepPhotos||!g.views.isEmpty())throw new JSONException("Conflicting photo and coordinate storage");
                    g.savedResults=new ArrayList<>();a=j.getJSONArray("measurements_mm");Set<Integer> ids=new HashSet<>();
                    for(int i=0;i<a.length();i++){Result r=Result.from(a.getJSONObject(i));if(!ids.add(r.id))throw new JSONException("Repeated arrow coordinate");g.savedResults.add(r);}
                    if(g.savedResults.isEmpty())throw new JSONException("Empty coordinate snapshot");
                    g.savedAxes=j.getString("axes");g.sourceViewCount=j.getInt("source_view_count");
                    a=j.optJSONArray("pending_photo_cleanup");if(a!=null)for(int i=0;i<a.length();i++)g.pendingPhotoCleanup.add(a.getString(i));
                }
            }
            g.practice=Practice.from(j.optJSONObject("practice"));return g;
        }
        public String title(){return name.isEmpty()?new SimpleDateFormat("MMM d · h:mm a",Locale.US).format(new Date(created)):name;}
    }
    // Snapshot actual geometry: later catalog edits must not rescale an old end.
    static JSONObject presetJson(TargetPreset p)throws JSONException {
        JSONArray colors=new JSONArray();for(int c:p.fillColors)colors.put(c);
        return new JSONObject().put("id",p.id).put("name",p.name).put("category",p.category).put("family",p.family).put("sku",p.sku).put("source",p.source).put("note",p.note).put("diameter_mm",p.diameterMm).put("paper_width_mm",p.paperWidthMm).put("paper_height_mm",p.paperHeightMm).put("spots",p.spots).put("inner_ratio",p.innerRatio).put("inner_hint",p.innerHint).put("rings",array(p.rings)).put("fill_radii",array(p.fillRadii)).put("fill_colors",colors);
    }
    static TargetPreset presetFrom(JSONObject j)throws JSONException {
        JSONArray colors=j.getJSONArray("fill_colors");int[] c=new int[colors.length()];for(int i=0;i<c.length;i++)c[i]=colors.getInt(i);
        return new TargetPreset(j.getString("id"),j.getString("name"),j.getString("category"),j.getString("family"),j.getString("sku"),j.getString("source"),j.getString("note"),j.getDouble("diameter_mm"),j.getDouble("paper_width_mm"),j.getDouble("paper_height_mm"),j.getInt("spots"),j.getDouble("inner_ratio"),j.getString("inner_hint"),doubles(j.getJSONArray("rings")),doubles(j.getJSONArray("fill_radii")),c);
    }
    File directory(Group g){if(g.id==null||!g.id.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("Invalid local grouping ID");File d=new File(root,g.id);d.mkdirs();return d;}
    public void save(Group g)throws Exception {
        synchronized(FILE_LOCK){
        File d=directory(g),temp=new File(d,"group.json.new"),dest=new File(d,"group.json");
        if(g.diskDigest!=null&&dest.isFile()&&!g.diskDigest.equals(SyncFiles.hash(dest)))throw new IOException("This grouping changed since it was opened. Reopen it before editing; your saved versions are safe.");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(g.json().toString(2).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        if(!temp.renameTo(dest))throw new IOException("Cannot commit local measurements");
        g.diskDigest=SyncFiles.hash(dest);changed();
        }
    }
    static Group copy(Group original)throws JSONException{Group copy=Group.from(original.json());copy.diskDigest=original.diskDigest;return copy;}
    /** Commit ownership separately, without editing photos, marks or another end. */
    Group updatePractice(Group original,Practice practice)throws Exception {
        File file=new File(directory(original),"group.json");Group latest=read(file);
        if(!latest.json().toString().equals(original.json().toString()))throw new IOException("This grouping changed. Reopen it before editing its context.");
        practice.validateCounts(latest);latest.practice=Practice.from(practice.json());save(latest);return latest;
    }
    /** Commit ownership separately, without editing photos, marks or another end. */
    public Group assignShooter(Group original,ShooterRoster.Person shooter)throws Exception {
        Group changed=copy(original);confirmShooter(changed,shooter);save(changed);return changed;
    }
    /** Gallery snapshots must never overwrite newer saved geometry. */
    Group assignSavedShooter(Group original,ShooterRoster.Person shooter)throws Exception {
        Group latest=read(new File(directory(original),"group.json"));
        if(!original.id.equals(latest.id))throw new IOException("Grouping identity changed; reopen the gallery");
        return assignShooter(latest,shooter);
    }
    /** One durable undo, scoped to the latest gallery ownership correction. */
    static final class ShooterEdit {
        final String before,after;final Group previous,changed;
        ShooterEdit(String before,String after)throws Exception {
            this.before=before;this.after=after;previous=Group.from(new JSONObject(before));changed=Group.from(new JSONObject(after));
            if(!changed.id.equals(previous.id))throw new IOException("Undo grouping identity mismatch");
        }
    }
    private File shooterUndoFile(){return new File(root.getParentFile(),"shooter-undo.json");}
    private static String readText(File file)throws IOException{return new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);}
    private static void atomicText(File dest,String text)throws IOException {
        File temp=new File(dest.getParentFile(),dest.getName()+".new");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(text.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        if(!temp.renameTo(dest))throw new IOException("Cannot commit local change");
    }
    ShooterEdit editSavedShooter(Group original,ShooterRoster.Person shooter)throws Exception {
        synchronized(FILE_LOCK){
        File file=new File(directory(original),"group.json");String before=readText(file);Group changed=Group.from(new JSONObject(before));
        if(!changed.id.equals(original.id))throw new IOException("Grouping identity changed; reopen the gallery");
        confirmShooter(changed,shooter);ShooterEdit edit=new ShooterEdit(before,changed.json().toString(2));
        // Write-ahead record: a crash before the group commit is a harmless,
        // inactive undo; a crash after it still permits exact recovery.
        atomicText(shooterUndoFile(),new JSONObject().put("before",edit.before).put("after",edit.after).toString());
        atomicText(file,edit.after);edit.changed.diskDigest=SyncFiles.hash(file);changed();return edit;
        }
    }
    ShooterEdit lastShooterEdit()throws Exception {
        File journal=shooterUndoFile();if(!journal.isFile())return null;
        JSONObject j=new JSONObject(readText(journal));ShooterEdit edit=new ShooterEdit(j.getString("before"),j.getString("after"));
        File file=new File(directory(edit.changed),"group.json");
        return file.isFile()&&readText(file).equals(edit.after)&&!edit.before.equals(edit.after)?edit:null;
    }
    Group undoShooterEdit(ShooterEdit edit)throws Exception {
        synchronized(FILE_LOCK){
        if(edit==null)throw new IOException("No shooter change to undo");
        ShooterEdit latest=lastShooterEdit();
        if(latest==null||!latest.before.equals(edit.before)||!latest.after.equals(edit.after))throw new IOException("This grouping changed after reassignment. Its newer data was kept.");
        atomicText(new File(directory(edit.changed),"group.json"),edit.before);
        // The retained bounded journal is now inactive; interrupted cleanup
        // cannot accidentally repeat an undo or touch photos.
        changed();return read(new File(directory(edit.changed),"group.json"));
        }
    }
    /** User confirmation supersedes only the old inferred-ownership warning. */
    static String confirmedOwnerWarning(String warning){return warning.replaceAll("(?i)Shooter provisional:[^.]*?(?:\\.|$)\\s*","").trim();}
    static void confirmShooter(Group group,ShooterRoster.Person shooter){group.shooter=shooter;for(View view:group.views)for(Observation o:view.observations)o.associationWarning=confirmedOwnerWarning(o.associationWarning);if(group.savedResults!=null)for(Result r:group.savedResults)r.warning=confirmedOwnerWarning(r.warning);}
    public static Group exportSnapshot(Group original,ShooterRoster roster)throws JSONException{
        Group copy=Group.from(original.json());copy.shooter=roster.resolve(original.shooter);copy.pendingPhotoCleanup.clear();return copy;
    }
    public void writeArchive(Group target,OutputStream out)throws Exception {
        ZipOutputStream zip=new ZipOutputStream(out);
        zip.putNextEntry(new ZipEntry("group.json"));zip.write(target.json().toString(2).getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        zip.putNextEntry(new ZipEntry("measurements.csv"));zip.write(csv(target).getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        for(View v:target.views){File file=photoForCleanup(target,v.file);zip.putNextEntry(new ZipEntry(v.file));try(InputStream in=new FileInputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))>=0)zip.write(b,0,n);}zip.closeEntry();}
        zip.finish();
    }
    /** Finalize only a new opt-in grouping. Commit/reopen coordinates before
     * removing exact app-owned JPEG copies. Camera-roll originals are untouched.
     * Unresolved drafts and all legacy/test groups keep their photos. */
    public Group finishCoordinates(Group original)throws Exception {
        if(original.keepPhotos)throw new IllegalArgumentException("This test grouping keeps its photos");
        if(original.coordinatesOnly())return retryPhotoCleanup(original);
        List<Result> measured=results(original);
        if(measured.isEmpty())throw new IllegalArgumentException("No positions yet. The draft photos are still saved.");
        for(Result r:measured)if(!Double.isFinite(r.x)||!Double.isFinite(r.y))throw new IllegalArgumentException("An arrow is unresolved. The draft photos are still saved.");
        Group compact=copy(original);compact.savedAxes=original.axes();compact.sourceViewCount=original.views.size();
        compact.savedResults=new ArrayList<>();for(Result r:measured)compact.savedResults.add(Result.from(r.json()));
        for(View v:compact.views){photoForCleanup(compact,v.file);if(!compact.pendingPhotoCleanup.contains(v.file))compact.pendingPhotoCleanup.add(v.file);}
        compact.views.clear();save(compact);
        Group reopened=read(new File(directory(compact),"group.json"));
        if(!reopened.json().toString().equals(compact.json().toString()))throw new IOException("Coordinate verification failed; photo copies have been kept");
        return retryPhotoCleanup(reopened);
    }
    private File photoForCleanup(Group g,String name)throws IOException {
        if(name==null||!name.matches("[A-Za-z0-9_-]+\\.jpg"))throw new IOException("Invalid app photo name; nothing removed");
        File dir=directory(g),file=new File(dir,name);
        if(!dir.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())||!file.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile()))throw new IOException("Photo is outside this grouping; nothing removed");
        if(file.exists()&&!file.isFile())throw new IOException("Expected a photo file; nothing removed");
        return file;
    }
    /** Idempotent recovery if the process stops between commit and cleanup. */
    public Group retryPhotoCleanup(Group compact)throws Exception {
        if(!compact.coordinatesOnly()||compact.keepPhotos||compact.pendingPhotoCleanup.isEmpty())return compact;
        Group durable=read(new File(directory(compact),"group.json"));
        if(!durable.json().toString().equals(compact.json().toString()))throw new IOException("Saved grouping changed; no photo cleanup performed");
        List<File> files=new ArrayList<>();for(String name:compact.pendingPhotoCleanup)files.add(photoForCleanup(compact,name));
        Group cleaned=copy(compact);
        for(File file:files)if(!file.exists()||file.delete())cleaned.pendingPhotoCleanup.remove(file.getName());
        try{save(cleaned);return cleaned;}catch(IOException failure){return compact; /* Saved coordinates + retry list remain durable. */}
    }
    public List<Group> list(){List<Group> groups=new ArrayList<>();File[] dirs=root.listFiles();if(dirs!=null)for(File d:dirs){try{groups.add(read(new File(d,"group.json")));}catch(Exception ignored){}}
        Collections.sort(groups,(a,b)->Long.compare(b.created,a.created));return groups;}
    Group read(File file)throws Exception {synchronized(FILE_LOCK){byte[] bytes;try(InputStream in=new FileInputStream(file)){bytes=SyncFiles.bytes(in,SyncFiles.JSON_LIMIT);}Group g=Group.from(new JSONObject(new String(bytes,StandardCharsets.UTF_8)));g.diskDigest=SyncFiles.hash(bytes);return g;}}
    public Bitmap bitmap(Group g,View v){File file=new File(directory(g),v.file);return orientPhoto(BitmapFactory.decodeFile(file.getAbsolutePath()),file);}
    /** Display orientation only. Never rewrite the captured JPEG or bake in marks. */
    static Bitmap orientPhoto(Bitmap image,File file){
        if(image==null)return null;int orientation=1;
        try{orientation=new ExifInterface(file.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}catch(IOException ignored){}
        Matrix m=new Matrix();switch(orientation){case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;case 7:m.setRotate(270);m.postScale(-1,1);break;case 8:m.setRotate(270);break;}
        if(m.isIdentity())return image;Bitmap upright=Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),m,true);if(upright!=image)image.recycle();return upright;
    }
    /** Image-first transaction: a visible saved round always has its original JPEG.
     * The passed shutter snapshot is not mutated, and analysis is never required. */
    Group saveCameraPhoto(Group shutter,byte[] jpeg,long capturedAt,long cameraTimestamp)throws Exception {
        if(!shutter.shooter.assigned()||!Double.isFinite(shutter.practice.meters)||shutter.practice.meters<=0)throw new IOException("Choose shooter and distance before taking a photo");
        if(shutter.coordinatesOnly()||!shutter.keepPhotos||shutter.views.size()>=64)throw new IOException("Start a new photo grouping");
        if(jpeg==null||jpeg.length<4||jpeg.length>SyncFiles.PHOTO_LIMIT||(jpeg[0]&255)!=255||(jpeg[1]&255)!=216)throw new IOException("Camera photo is missing or exceeds the 8 MB sync limit");
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>SyncFiles.PHOTO_MAX_DIMENSION||bounds.outHeight>SyncFiles.PHOTO_MAX_DIMENSION||(long)bounds.outWidth*bounds.outHeight>16_000_000L)throw new IOException("Camera returned an unreadable or oversized photo");
        int orientation=new ExifInterface(new ByteArrayInputStream(jpeg)).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);
        View view=new View();view.file=view.id+".jpg";view.sourceHash=SyncFiles.hash(jpeg);view.capturedAt=capturedAt;view.cameraTimestamp=cameraTimestamp;
        view.rawWidth=bounds.outWidth;view.rawHeight=bounds.outHeight;boolean swap=orientation>=5&&orientation<=8;view.width=swap?bounds.outHeight:bounds.outWidth;view.height=swap?bounds.outWidth:bounds.outHeight;
        PhotoDetection.prepare(this,view);
        Group saved=copy(shutter);if(!saved.hasData())saved.created=capturedAt;saved.views.add(view);
        synchronized(FILE_LOCK){
            File photo=new File(directory(saved),view.file);if(photo.exists())throw new IOException("Camera photo identity collision");
            try{SyncFiles.atomic(photo,jpeg);save(saved);}
            catch(Exception failure){
                // A notification failure after the manifest commit must not remove
                // an image that the saved round already references.
                File manifest=new File(directory(saved),"group.json");
                if(manifest.isFile())try{Group committed=read(manifest);for(View v:committed.views)if(v.id.equals(view.id)&&v.sourceHash.equals(view.sourceHash))return committed;}catch(Exception ignored){}
                if(photo.exists()&&!photo.delete())android.util.Log.w("RedwoodArrows","Uncommitted camera photo retained for recovery");
                File partial=new File(photo.getPath()+".new");if(partial.exists()&&!partial.delete())android.util.Log.w("RedwoodArrows","Incomplete camera photo retained");
                throw failure;
            }
        }
        return saved;
    }
    public View importPhoto(Context c,Group g,Uri uri)throws Exception {
        if(g.coordinatesOnly())throw new IOException("Cannot add photos to a completed coordinates-only grouping");
        File temporary=File.createTempFile("selected-photo-",".image",c.getCacheDir());Bitmap b=null;
        try{
            try(InputStream in=c.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temporary)){if(in==null)throw new IOException("Photo access was lost; select it again");byte[] chunk=new byte[16384];int n;long size=0;while((n=in.read(chunk))!=-1){size+=n;if(size>64L*1024*1024)throw new IOException("Photo exceeds the 64 MB import limit");out.write(chunk,0,n);}}
            String hash=SyncFiles.hash(temporary);for(View existing:g.views)if(hash.equals(existing.sourceHash))return existing;
            if(g.views.size()>=64)throw new IOException("A grouping can contain at most 64 photos");
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(temporary.getPath(),bounds);
            if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("This file is not a readable photo");
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>3000)options.inSampleSize*=2;
            b=BitmapFactory.decodeFile(temporary.getPath(),options);if(b==null)throw new IOException("This photo could not be decoded");
            int orientation=1;long captured=0;try{ExifInterface exif=new ExifInterface(temporary.getPath());orientation=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,1);String date=exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);if(date!=null){SimpleDateFormat format=new SimpleDateFormat("yyyy:MM:dd HH:mm:ss",Locale.US);format.setLenient(false);String offset=exif.getAttribute("OffsetTimeOriginal");if(offset!=null&&offset.matches("[+-][0-9]{2}:[0-9]{2}"))format.setTimeZone(TimeZone.getTimeZone("GMT"+offset));captured=format.parse(date).getTime();if(captured<946684800000L||captured>System.currentTimeMillis()+86400000L)captured=0;}}catch(Exception ignored){}
            Matrix m=new Matrix();switch(orientation){case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;case 7:m.setRotate(270);m.postScale(-1,1);break;case 8:m.setRotate(270);break;}
            if(!m.isIdentity()){Bitmap rotated=Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true);if(rotated!=b)b.recycle();b=rotated;}
            if(Math.max(b.getWidth(),b.getHeight())>2048){double s=2048.0/Math.max(b.getWidth(),b.getHeight());Bitmap small=Bitmap.createScaledBitmap(b,Math.max(1,(int)Math.round(b.getWidth()*s)),Math.max(1,(int)Math.round(b.getHeight()*s)),true);if(small!=b)b.recycle();b=small;}
            View v=new View();v.sourceHash=hash;v.capturedAt=captured;PhotoDetection.prepare(this,v);saveBitmap(g,v,b);long created=g.created;
            if(g.diskDigest==null&&g.views.isEmpty()&&captured>0)g.created=captured;
            g.views.add(v);try{save(g);}catch(Exception failure){g.views.remove(v);g.created=created;throw failure;}return v;
        }finally{if(b!=null)b.recycle();if(!temporary.delete())android.util.Log.w("RedwoodArrows","Could not remove temporary imported photo");}
    }
    public void saveBitmap(Group g,View v,Bitmap b)throws IOException {v.file=v.id+".jpg";v.width=b.getWidth();v.height=b.getHeight();try(FileOutputStream out=new FileOutputStream(new File(directory(g),v.file))){if(!b.compress(Bitmap.CompressFormat.JPEG,95,out))throw new IOException("Photo save failed");out.getFD().sync();}}
    public static final class Result {
        public int id,views;public double x,y,rms,angle;public String method,warning="";
        JSONObject json()throws JSONException{return new JSONObject().put("arrow",id).put("x_mm",x).put("y_mm",y).put("agreement_mm",Double.isFinite(rms)?rms:JSONObject.NULL).put("view_angle_degrees",Double.isFinite(angle)?angle:JSONObject.NULL).put("views",views).put("method",method).put("warning",warning);}
        static Result from(JSONObject j)throws JSONException{Result r=new Result();r.id=j.getInt("arrow");r.x=j.getDouble("x_mm");r.y=j.getDouble("y_mm");if(!Double.isFinite(r.x)||!Double.isFinite(r.y))throw new JSONException("Invalid saved coordinate");r.rms=j.optDouble("agreement_mm",Double.NaN);r.angle=j.optDouble("view_angle_degrees",Double.NaN);r.views=j.getInt("views");r.method=j.getString("method");r.warning=j.optString("warning");return r;}
    }
    public static List<Result> results(Group g){
        if(g.coordinatesOnly()){List<Result> copy=new ArrayList<>();try{for(Result r:g.savedResults)copy.add(Result.from(r.json()));}catch(JSONException e){throw new IllegalArgumentException("Invalid stored measurements",e);}return copy;}
        TreeSet<Integer> ids=new TreeSet<>();for(View v:g.views)for(Observation o:v.observations)ids.add(o.id);
        List<Result> out=new ArrayList<>();
        for(int id:ids){List<double[]> points=new ArrayList<>(),lines=new ArrayList<>();boolean unreviewed=false,neural=false;Set<String> seenLines=new HashSet<>(),seenPoints=new HashSet<>(),warnings=new LinkedHashSet<>();
            for(View v:g.views){if(v.calibration==null)continue;for(Observation o:v.observations)if(o.id==id){if(!v.calibration.reviewed){unreviewed=true;continue;}try{double[] a=v.calibration.map(o.a[0],o.a[1]);if(o.line){if(seenLines.add(v.id))lines.add(Geometry.line(a,v.calibration.map(o.b[0],o.b[1])));}else if(seenPoints.add(v.id)){points.add(new double[]{a[0],a[1],Double.isFinite(o.confidence)?o.confidence:1.});neural|=o.source.startsWith("impact-model:");if(!o.associationWarning.isEmpty())warnings.add(o.associationWarning);}}catch(IllegalArgumentException ignored){}}}
            Result r=new Result();r.id=id;
            if(!points.isEmpty()){View reference=g.views.get(0);double bandwidth=neural&&reference.calibration!=null?Geometry.associationGate(reference.calibration,reference.width,reference.height)*.35:.02;double[] m=Geometry.robustMean(points,bandwidth);r.x=m[0]*g.radiusMm();r.y=m[1]*g.radiusMm();r.method=neural?(points.size()>1?"multi-view neural impact":"single-view neural impact"):(points.size()>1?"multi-view marked impact":"marked impact");r.views=points.size();r.rms=Geometry.spread(points)*g.radiusMm();if(r.rms>8)warnings.add("Impact marks disagree between views");r.warning=String.join(" · ",warnings);}
            else try{Geometry.Intersection p=Geometry.intersect(lines);r.x=p.x*g.radiusMm();r.y=p.y*g.radiusMm();r.rms=p.rms*g.radiusMm();r.angle=p.angleDegrees;r.views=p.views;r.method="shaft intersection";if(p.views==2)r.warning="Two views: no independent residual check";}
            catch(IllegalArgumentException e){r.x=Double.NaN;r.y=Double.NaN;r.method="not resolved";r.views=lines.size();r.warning=e.getMessage();}
            if(unreviewed)r.warning+=(r.warning.isEmpty()?"":" · ")+"Unreviewed alignment excluded";
            out.add(r);
        }return out;
    }
    static String csvText(String text){String safe=text;if(!safe.isEmpty()&&"=+-@\t\r".indexOf(safe.charAt(0))>=0)safe="'"+safe;return "\""+safe.replace("\"","\"\"")+"\"";}
    public static String csv(Group g){StringBuilder s=new StringBuilder("arrow,x_mm,y_mm,method,views,agreement_mm,view_angle_degrees,warning,shooter_id,shooter_name\n");for(Result r:results(g))s.append(String.format(Locale.US,"%d,%s,%s,%s,%d,%.3f,%.2f,%s,%s,%s\n",r.id,Double.isFinite(r.x)?String.format(Locale.US,"%.3f",r.x):"",Double.isFinite(r.y)?String.format(Locale.US,"%.3f",r.y):"",r.method,r.views,r.rms,r.angle,csvText(r.warning),csvText(g.shooter.id),csvText(g.shooter.name)));return s.toString();}
}
