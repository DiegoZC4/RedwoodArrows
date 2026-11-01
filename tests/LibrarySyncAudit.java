package org.redwoodarrows;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Read-only compatibility check against a copied library; outputs aggregates only. */
public final class LibrarySyncAudit {
    static Map<String,String> inventory(Path root)throws Exception {
        Map<String,String> out=new TreeMap<>();
        try(java.util.stream.Stream<Path> paths=Files.walk(root)){
            for(Path path:(Iterable<Path>)paths.filter(Files::isRegularFile)::iterator)out.put(root.relativize(path).toString(),SyncFiles.hash(path.toFile()));
        }
        return out;
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Use a copied Android files directory");
        Path root=Paths.get(args[0]).toRealPath();Map<String,String> before=inventory(root);Store store=new Store(root.toFile());
        int groups=0,reviews=0,checked=0,references=0,maxBytes=0;Set<String> images=new HashSet<>();
        for(Store.Group group:store.list()){
            SyncFiles.Snapshot snapshot=SyncFiles.snapshot(store,group,true);
            Store.Group roundtrip=Store.Group.from(new JSONObject(snapshot.body));
            Set<String> original=TrainingReviews.checkedPhotos(store,group),restored=TrainingReviews.checkedPhotos(store,roundtrip);
            if(!original.equals(restored))throw new IOException("Shared review status changed in the round trip");
            if(!Store.csv(group).equals(Store.csv(roundtrip)))throw new IOException("Measurements changed in the round trip");
            groups++;checked+=original.size();references+=snapshot.photos.length();reviews+=roundtrip.photoReviews==null?0:roundtrip.photoReviews.length();
            maxBytes=Math.max(maxBytes,snapshot.body.getBytes("UTF-8").length);
            for(int i=0;i<snapshot.photos.length();i++)images.add(snapshot.photos.getJSONObject(i).getString("sha256"));
        }
        if(!before.equals(inventory(root)))throw new IOException("The source copy changed during the audit");
        System.out.println(new JSONObject().put("groups",groups).put("portable_reviews",reviews).put("checked_photos",checked)
            .put("photo_references",references).put("unique_photo_blobs",images.size()).put("largest_group_json_bytes",maxBytes)
            .put("files_unchanged",before.size()).put("network_used",false).toString());
    }
}
