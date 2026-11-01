package org.redwoodarrows;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class PhotoReviewTest {
    static int checks;
    static void check(boolean condition,String why){checks++;if(!condition)throw new AssertionError(why);}
    static void rejects(Runnable action,String why){boolean rejected=false;try{action.run();}catch(IllegalArgumentException e){rejected=true;}check(rejected,why);}
    public static void main(String[] args)throws Exception {
        Path temporary=Files.createTempDirectory("redwood-review-test-");
        try{
            Store store=new Store(temporary.toFile());Store.Group source=PhotoAssignmentsTest.group(store);
            String before=source.json().toString(),photoHash=SyncFiles.hash(new File(store.directory(source),source.views.get(0).file));
            PhotoAssignments edit=new PhotoAssignments(source,source.views.get(0).id);
            check(edit.singleConfirmedTarget()!=null,"A single reviewed target can carry new entry dots");
            check(new PhotoReview(edit.draft).marked==3,"Counts existing unique arrows");
            check(new PhotoReview(edit.draft).located==3,"Counts confirmed positions separately");
            rejects(()->PhotoReview.updateCounts(edit,2,5),"Draft counts reject more misses than shots");
            check(edit.draft.practice.fired==-1&&edit.draft.practice.misses==-1,"Invalid draft count edits are atomic");
            check(!new PhotoReview(edit.draft).reviewed,"Detection/alignment alone is not a completed review");
            rejects(()->PhotoReview.finish(edit,-1,0),"Unknown shot count cannot be marked reviewed");
            check(edit.draft.practice.fired==-1&&edit.draft.practice.misses==-1,"Failed completion leaves draft counts intact");
            rejects(()->PhotoReview.finish(edit,2,0),"Reject more entries than shots");
            rejects(()->PhotoReview.finish(edit,4,0),"Reject missing unaccounted shot");
            PhotoReview.finish(edit,4,1);check(new PhotoReview(edit.draft).reviewed,"Explicit misses can account for a shot");
            check(source.json().toString().equals(before),"Finishing draft never mutates original");
            String reviewed=edit.snapshot();edit.changePreset(TargetCatalog.find("mlp-nfh-35"));
            check(edit.draft.type.equals("Hunter")&&edit.draft.diameterCm==35,"Correct preset family and physical size");
            check(edit.ids().size()==3&&edit.draft.views.size()==source.views.size(),"Changing target preserves all entry dots and photos");
            check(edit.draft.views.stream().allMatch(v->v.calibration==null),"Old geometry is invalidated for the changed face");
            check(new PhotoReview(edit.draft).located==0&&!new PhotoReview(edit.draft).reviewed,"No stale reviewed measurements");
            rejects(()->PhotoReview.finish(edit,3,0),"Unaligned positions cannot be completed");
            edit.restore(reviewed);check(new PhotoReview(edit.draft).reviewed,"Undo restores checked fingerprint and geometry");
            int revision=edit.revision;edit.changePreset(edit.draft.preset());check(edit.revision==revision,"Same preset is a no-op");
            int added=edit.add(100,100);check(!new PhotoReview(edit.draft).reviewed,"Adding a missed entry invalidates review");
            check(edit.singleConfirmedTarget()==null,"An unassigned dot prevents assuming every dot shares a target");
            rejects(()->PhotoReview.finish(edit,4,0),"Unassigned new entry must be located");
            edit.assign(new TreeSet<>(Arrays.asList(added)),PhotoAssignmentsTest.cal(200));PhotoReview.finish(edit,4,0);
            check(edit.singleConfirmedTarget()!=null,"Assigned entries restore the single confirmed target");
            Store.Group saved=edit.commit(store);check(new PhotoReview(saved).reviewed,"Completion survives save");
            Store.Group reloaded=store.read(new File(store.directory(saved),"group.json"));check(new PhotoReview(reloaded).reviewed,"Review survives restart/serialization");
            check(SyncFiles.hash(new File(store.directory(saved),source.views.get(0).file)).equals(photoHash),"Original image bytes unchanged");
            Store.Group kept=PhotoReview.save(new PhotoAssignments(reloaded,reloaded.views.get(0).id),store);
            check(new PhotoReview(kept).reviewed,"Done preserves an existing valid position check without re-confirming it");

            Store.Group quickSource=PhotoAssignmentsTest.group(store);String quickBefore=quickSource.json().toString();
            String quickPhotoHash=SyncFiles.hash(new File(store.directory(quickSource),quickSource.views.get(0).file));
            PhotoAssignments quick=new PhotoAssignments(quickSource,quickSource.views.get(0).id);quick.changePreset(TargetCatalog.find("mlp-nfh-35"));
            Store.Group quickSaved=PhotoReview.save(quick,store);PhotoReview quickSummary=new PhotoReview(quickSaved);
            check(quickSummary.marked==3&&quickSummary.located==0,"Done saves photo-space dots without requiring target alignment");
            check(quickSaved.practice.fired==-1&&quickSaved.practice.misses==-1,"Done does not guess shot counts or zero misses");
            check(!quickSummary.reviewed&&quickSaved.practice.checked.isEmpty(),"Done does not certify unconfirmed positions");
            check(quickSource.json().toString().equals(quickBefore),"Quick save does not mutate the original in-memory group");
            check(quickSaved.shooter.id.equals(quickSource.shooter.id)&&quickSaved.practice.meters==quickSource.practice.meters,"Done preserves shooter and distance");
            check(SyncFiles.hash(new File(store.directory(quickSaved),quickSaved.views.get(0).file)).equals(quickPhotoHash),"Quick save preserves original photo bytes");
            quickSaved=store.read(new File(store.directory(quickSaved),"group.json"));
            check(quickSaved.practice.fired==-1&&new PhotoReview(quickSaved).marked==3,"Unknown counts and dots survive reload after Done");

            PhotoAssignments moreDots=new PhotoAssignments(quickSaved,quickSaved.views.get(0).id);moreDots.draft.practice.fired=3;moreDots.draft.practice.misses=0;
            moreDots.add(100,100);moreDots.draft.practice.checked="stale check";
            Store.Group moreSaved=PhotoReview.save(moreDots,store);
            check(new PhotoReview(moreSaved).marked==4,"Outdated optional shot counts do not block saving corrected dots");
            check(moreSaved.practice.fired==3&&moreSaved.practice.misses==0,"Done preserves explicitly entered counts for later correction");
            check(moreSaved.practice.checked.isEmpty(),"Stale verification is cleared instead of carried forward");
            String diskBefore=new String(Files.readAllBytes(new File(store.directory(moreSaved),"group.json").toPath()),java.nio.charset.StandardCharsets.UTF_8);
            PhotoAssignments invalid=new PhotoAssignments(moreSaved,moreSaved.views.get(0).id);invalid.draft.practice.fired=-2;
            boolean invalidRejected=false;try{PhotoReview.save(invalid,store);}catch(IllegalArgumentException e){invalidRejected=true;}
            check(invalidRejected,"Quick save still rejects malformed metadata");
            check(new String(Files.readAllBytes(new File(store.directory(moreSaved),"group.json").toPath()),java.nio.charset.StandardCharsets.UTF_8).equals(diskBefore),"Failed quick save leaves the saved group unchanged");
            PhotoAssignments stale=new PhotoAssignments(moreSaved,moreSaved.views.get(0).id);
            Store.Group latest=store.read(new File(store.directory(moreSaved),"group.json"));latest.name="Newer edit";store.save(latest);
            boolean staleRejected=false;try{PhotoReview.save(stale,store);}catch(IOException e){staleRejected=true;}
            check(staleRejected&&store.read(new File(store.directory(latest),"group.json")).name.equals("Newer edit"),"Done cannot overwrite a newer saved edit");
            check(PhotoReview.count("")==-1&&PhotoReview.count(" 6 ")==6,"Optional count parsing");
            rejects(()->PhotoReview.count("-1"),"No negative count input");rejects(()->PhotoReview.count("6.5"),"Whole arrows only");rejects(()->PhotoReview.count("1001"),"Count limit enforced");
            System.out.println("Photo review checks passed: "+checks);
        }finally{try(java.util.stream.Stream<Path> paths=Files.walk(temporary)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new RuntimeException(e);}});}}
    }
}
