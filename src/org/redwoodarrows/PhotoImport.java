package org.redwoodarrows;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** User-selected photos only; one selection is one unchanged grouping. */
final class PhotoImport {
    static final int REQUEST=10,MAX_BATCH=12,MAX_VIEWS=64;
    final MainActivity a;Store.Group draft;String existingId;boolean pending;
    PhotoImport(MainActivity activity){a=activity;}
    void start(boolean existing){
        if(a.busy||pending)return;
        try{
            if(existing&&(a.group==null||a.group.coordinatesOnly()))throw new IOException("This grouping has already kept only its 2D positions. Start a new photo grouping.");
            if(existing&&a.group.views.size()>=MAX_VIEWS)throw new IOException("This grouping already has 64 views.");
            a.rememberGroups();existingId=existing?a.group.id:null;draft=existing?null:a.draftGroup();if(draft!=null)draft.keepPhotos=true;pending=true;
            Store.editingGroupId=existing?existingId:draft.id;a.page="photo-picker";
            int limit=Math.min(MAX_BATCH,existing?MAX_VIEWS-a.group.views.size():MAX_BATCH);
            Intent picker=new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
            if(limit>1)picker.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,limit);
            try{a.startActivityForResult(picker,REQUEST);}
            catch(ActivityNotFoundException unavailable){Intent files=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,limit>1);a.startActivityForResult(files,REQUEST);}
            Toast.makeText(a,"Select photos of the same grouping",Toast.LENGTH_SHORT).show();
        }catch(Exception e){cancel();a.message("Could not open photos: "+e.getMessage());}
    }
    void save(Bundle state){if(!pending)return;try{state.putBoolean("photo-import-pending",true);if(existingId!=null)state.putString("photo-import-existing",existingId);else if(draft!=null)state.putString("photo-import-draft",draft.json().toString());}catch(Exception ignored){}}
    void restore(Bundle state){if(state==null||!state.getBoolean("photo-import-pending"))return;try{existingId=state.getString("photo-import-existing");if(existingId!=null)SyncFiles.id(existingId);else draft=Store.Group.from(new JSONObject(state.getString("photo-import-draft")));pending=true;Store.editingGroupId=existingId!=null?existingId:draft.id;a.page="photo-picker";}catch(Exception ignored){pending=false;}}
    static List<Uri> selected(Intent data)throws IOException {
        LinkedHashSet<Uri> selected=new LinkedHashSet<>();
        if(data!=null&&data.getClipData()!=null){ClipData clip=data.getClipData();if(clip.getItemCount()>MAX_BATCH)throw new IOException("Choose at most 12 photos of the same grouping");for(int n=0;n<clip.getItemCount();n++)selected.add(clip.getItemAt(n).getUri());}
        else if(data!=null&&data.getData()!=null)selected.add(data.getData());
        for(Uri uri:selected)if(uri==null||!"content".equals(uri.getScheme())||uri.getAuthority()==null)throw new IOException("Choose photos using the system photo picker");
        return new ArrayList<>(selected);
    }
    Store.Group target()throws Exception {
        String id=existingId!=null?existingId:draft.id;File saved=new File(new File(a.store.root,SyncFiles.id(id)),"group.json");
        Store.Group target=saved.isFile()?a.store.read(saved):draft;
        if(target==null||target.coordinatesOnly())throw new IOException("This grouping changed. Reopen it before adding photos.");
        return target;
    }
    void result(int result,Intent data){
        if(!pending){if(result==Activity.RESULT_OK)a.message("The photo selection was interrupted. Add the photos again; saved groupings are unchanged.");return;}
        if(result!=Activity.RESULT_OK){cancel();return;}
        final List<Uri> uris;final Store.Group target;
        try{uris=selected(data);if(uris.isEmpty()){cancel();return;}target=target();if(target.views.size()+uris.size()>MAX_VIEWS)throw new IOException("A grouping can contain at most 64 views");}
        catch(Exception e){cancel();a.message(e.getMessage());return;}
        pending=false;draft=null;existingId=null;a.group=target;Store.editingGroupId=target.id;a.page="photo-import";
        final int before=target.views.size();final int[] failed={0},duplicates={0};
        a.work("Saving photos on this phone…",()->{
            for(Uri uri:uris){try{int count=target.views.size();a.store.importPhoto(a,target,uri);if(target.views.size()==count)duplicates[0]++;}catch(Exception e){failed[0]++;android.util.Log.w("RedwoodArrows","Selected photo could not be imported",e);}}
        },()->{
            int added=target.views.size()-before;if(before==0&&added>0)a.shooters.captured(target);a.analysis=null;a.undo.clear();a.groupImages.thumbs.evictAll();
            if(target.hasData()){a.group=target;a.index=Math.max(0,before);a.results();SyncRuntime.saved(a);PhotoDetectionRuntime.saved(a);}
            else a.home();
            String notice=added+" photo"+(added==1?"":"s")+" saved"+(duplicates[0]>0?" · "+duplicates[0]+" already added":"");
            if(failed[0]>0)a.message(notice+". "+failed[0]+" could not be read. Saved photos are safe; select the missing ones again. Camera-roll originals were not changed.");
            else Toast.makeText(a,notice,Toast.LENGTH_SHORT).show();
        });
    }
    void cancel(){String old=existingId;pending=false;draft=null;existingId=null;if(old!=null)try{a.openGroup(a.store.read(new File(new File(a.store.root,SyncFiles.id(old)),"group.json")));return;}catch(Exception ignored){}a.home();}
}
