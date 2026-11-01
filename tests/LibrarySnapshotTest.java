package org.redwoodarrows;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class LibrarySnapshotTest {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        AtomicLong edits=new AtomicLong(),sync=new AtomicLong(),detection=new AtomicLong();int[] reads={0};
        List<Store.Group> source=new ArrayList<>();Store.Group old=new Store.Group();old.name="Before review";source.add(old);
        LibrarySnapshot cache=new LibrarySnapshot(()->{reads[0]++;return source;},edits::get,sync::get,detection::get);
        List<Store.Group> home=cache.list();
        check(reads[0]==1&&home.size()==1,"first display reads library");
        check(cache.list()==home&&reads[0]==1,"tabs share parsed metadata");
        String original=old.json().toString();
        new HomeStats(home,Collections.emptyList(),"",Double.NaN);
        check(old.json().toString().equals(original),"chart leaves cached metadata unchanged");
        boolean immutable=false;try{home.clear();}catch(UnsupportedOperationException expected){immutable=true;}
        check(immutable&&source.size()==1,"display cannot remove cached records");
        Store.Group review=new Store.Group();review.name="Reviewed";source.set(0,review);edits.incrementAndGet();
        check(cache.list().get(0)==review&&reads[0]==2,"review or label save invalidates history");
        check(home.get(0)==old,"old page retains independent list snapshot");
        source.add(new Store.Group());sync.incrementAndGet();
        check(cache.list().size()==2&&reads[0]==3,"downloaded photos appear after sync");
        detection.incrementAndGet();List<Store.Group> detected=cache.list();
        check(reads[0]==4&&detected!=home,"completed detection invalidates history");
        check(cache.list()==detected&&reads[0]==4,"unchanged data not reread");
        cache.clear();check(cache.list()!=detected&&reads[0]==5,"backgrounding forces fresh data on next access");
        source.clear();edits.incrementAndGet();check(cache.list().isEmpty(),"removed records do not survive invalidation");
        int emptyReads=reads[0];check(cache.list().isEmpty()&&reads[0]==emptyReads,"empty library is cached too");
        int[] raceReads={0};AtomicLong race=new AtomicLong();
        LibrarySnapshot raced=new LibrarySnapshot(()->{if(++raceReads[0]==1)race.incrementAndGet();return Collections.emptyList();},race::get,()->0,()->0);
        raced.list();raced.list();check(raceReads[0]==2,"change during read not stamped as current");
        raced.list();check(raceReads[0]==2,"stable read reused after race");
        boolean[] fail={false};int[] attempts={0};AtomicLong revision=new AtomicLong();
        LibrarySnapshot retry=new LibrarySnapshot(()->{attempts[0]++;if(fail[0])throw new IllegalStateException("test read failure");return source;},revision::get,()->0,()->0);
        retry.list();revision.incrementAndGet();fail[0]=true;boolean threw=false;
        try{retry.list();}catch(IllegalStateException expected){threw=true;}
        check(threw,"read failures are not silently replaced by stale data");
        fail[0]=false;retry.list();check(attempts[0]==3,"failed read is retried");
        System.out.println("LibrarySnapshotTest: "+checks+" checks passed");
    }
}
