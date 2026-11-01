package org.redwoodarrows;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** UI-thread, read-only history shared by tabs; editors still read from Store. */
final class LibrarySnapshot {
    private final Supplier<List<Store.Group>> load;
    private final LongSupplier edits,sync,detection;
    private long editVersion,syncVersion,detectionVersion;
    private List<Store.Group> groups;
    LibrarySnapshot(Supplier<List<Store.Group>> load,LongSupplier edits,LongSupplier sync,LongSupplier detection){
        this.load=load;this.edits=edits;this.sync=sync;this.detection=detection;
    }
    List<Store.Group> list(){
        long e=edits.getAsLong(),s=sync.getAsLong(),d=detection.getAsLong();
        if(groups==null||editVersion!=e||syncVersion!=s||detectionVersion!=d){
            List<Store.Group> loaded=Collections.unmodifiableList(new ArrayList<>(load.get()));
            // Keep the pre-read versions: a change during loading invalidates
            // this snapshot on the next access rather than hiding that change.
            groups=loaded;editVersion=e;syncVersion=s;detectionVersion=d;
        }
        return groups;
    }
    void clear(){groups=null;}
}
