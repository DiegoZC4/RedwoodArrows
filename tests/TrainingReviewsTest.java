package org.redwoodarrows;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

public final class TrainingReviewsTest {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    interface Attempt {void run()throws Exception;}
    static void fails(Attempt action,String why)throws Exception {boolean failed=false;try{action.run();}catch(Exception expected){failed=true;}check(failed,why);}
    static PhotoAssignments edit(Store.Group group)throws Exception {return new PhotoAssignments(group,group.views.get(0).id);}
    static Store.Group reload(Store store,Store.Group group)throws Exception {return store.read(new File(store.directory(group),"group.json"));}
    static JSONObject json(File file)throws Exception{return new JSONObject(SyncFiles.read(file));}
    public static void main(String[] args)throws Exception {
        Path temp=Files.createTempDirectory("redwood-training-review-");
        try{
            Store store=new Store(temp.toFile());Store.Group group=PhotoAssignmentsTest.group(store);PhotoAssignments model=edit(group);
            TrainingReviews.Session session=TrainingReviews.open(store,model);File journal=session.file;
            check(!journal.exists(),"Opening a review does not write a label");check(!session.checked(model),"Existing manual/model dots are never automatically certified");
            String originalHash=TrainingReviews.imageHash(store,model);String baseline=session.record.getJSONObject("baseline").toString();
            session.save(store,model);check(!json(journal).getJSONObject("latest").getBoolean("complete"),"Partial autosave is not a training example");
            session.setChecked(model,true);check(session.dirty(model),"Checkbox-only change is autosavable");group=session.save(store,model);
            check(session.checked(model)&&!session.dirty(model),"Successful automatic save resets pending state");
            PhotoAssignments reopened=edit(reload(store,group));TrainingReviews.Session restored=TrainingReviews.open(store,reopened);
            check(restored.checked(reopened),"Completion survives reopening");
            Store.View view=reopened.reference();view.observations.get(0).a[0]+=1;reopened.revision++;
            check(!restored.checked(reopened)&&restored.dirty(reopened),"Moving a dot invalidates reviewed status");
            view.observations.get(0).a[0]-=1;reopened.revision++;
            check(!restored.checked(reopened),"Undoing an edit does not silently recertify the photo");
            restored.setChecked(reopened,true);group=restored.save(store,reopened);
            check(json(journal).getJSONObject("baseline").toString().equals(baseline),"First baseline remains frozen after corrections");
            PhotoAssignments alias=edit(group);TrainingReviews.Session aliasReview=TrainingReviews.open(store,alias);
            alias.assign(PhotoAssignmentsTest.ids(2,3),PhotoAssignmentsTest.cal(400));
            check(aliasReview.checked(alias),"Target calibration/assignment does not invalidate entry labels");
            alias.draft.practice.meters=13.716;alias.draft.shooter=new ShooterRoster.Person("iris","Iris");alias.revision++;
            check(aliasReview.checked(alias),"Shooter and distance do not affect photo labels");group=aliasReview.save(store,alias);
            check(TrainingReviews.imageHash(store,alias).equals(originalHash),"Aliases preserve original photo bytes");
            check(TrainingReviews.open(store,new PhotoAssignments(group,group.views.get(1).id)).file.equals(journal),"Every alias resolves to the same journal");
            SyncFiles.Snapshot wire=SyncFiles.snapshot(store,group);
            check(!wire.body.contains("training-reviews")&&!wire.body.contains("baseline")&&!wire.body.contains("fingerprint"),"Training labels never enter synced group metadata");
            for(int i=0;i<wire.photos.length();i++)check(wire.photos.getJSONObject(i).getString("file").endsWith(".jpg"),"Sync only contains photo blobs");

            PhotoAssignments empty=edit(group);TrainingReviews.Session emptyReview=TrainingReviews.open(store,empty);empty.remove(empty.ids());
            check(!emptyReview.checked(empty),"Removing dots clears completeness");emptyReview.setChecked(empty,true);group=emptyReview.save(store,empty);
            check(json(journal).getJSONObject("latest").getBoolean("complete")&&json(journal).getJSONObject("latest").getJSONArray("points").length()==0,"Explicit empty complete photo is a valid negative example");
            int added=empty.add(100,200);check(!emptyReview.checked(empty),"Adding a dot invalidates an empty review");
            emptyReview.save(store,empty);check(!json(journal).getJSONObject("latest").getBoolean("complete"),"Changed dots auto-save as incomplete");
            check(json(journal).getJSONObject("baseline").toString().equals(baseline),"Repeated autosaves retain the original detections");

            Store.Group concurrent=reload(store,empty.draft);PhotoAssignments first=edit(concurrent),second=edit(concurrent);
            TrainingReviews.Session firstReview=TrainingReviews.open(store,first),secondReview=TrainingReviews.open(store,second);
            firstReview.setChecked(first,true);firstReview.save(store,first);String currentJournal=SyncFiles.hash(journal);
            secondReview.setChecked(second,true);fails(()->secondReview.save(store,second),"Concurrent journal writes are rejected");
            check(SyncFiles.hash(journal).equals(currentJournal),"Stale review leaves newer journal unchanged");
            PhotoAssignments stale=edit(reload(store,first.draft));TrainingReviews.Session staleReview=TrainingReviews.open(store,stale);
            Store.Group newer=reload(store,first.draft);newer.name="Newer user edit";store.save(newer);stale.add(120,220);
            fails(()->staleReview.save(store,stale),"Stale group autosave is rejected");
            check(SyncFiles.hash(journal).equals(currentJournal)&&TrainingReviews.pending(journal).exists(),"Failed group save preserves journal but keeps export blocked until retry");

            PhotoAssignments interrupted=edit(newer);TrainingReviews.Session interruptedReview=TrainingReviews.open(store,interrupted);interruptedReview.setChecked(interrupted,false);
            File staging=new File(journal.getPath()+".new");check(staging.mkdir(),"Create deterministic journal-write failure");
            fails(()->interruptedReview.save(store,interrupted),"Journal failure reported after group save");
            check(TrainingReviews.pending(journal).exists(),"Interrupted review is blocked from export");
            check(!TrainingReviews.open(store,edit(reload(store,newer))).checked(edit(reload(store,newer))),"Pending old complete record is never shown as complete");
            check(staging.delete(),"Remove owned test obstruction");interruptedReview.save(store,interrupted);
            check(!TrainingReviews.pending(journal).exists()&&!json(journal).getJSONObject("latest").getBoolean("complete"),"Retry finishes safely without losing corrections");
            TrainingReviews.Session clean=TrainingReviews.open(store,edit(reload(store,newer)));
            JSONObject malformed=json(journal);malformed.getJSONObject("latest").put("complete","true");SyncFiles.atomic(journal,malformed.toString());
            check(!TrainingReviews.open(store,edit(reload(store,newer))).checked(edit(reload(store,newer))),"String true is not human confirmation");

            PhotoAssignments imageEdit=edit(reload(store,newer));TrainingReviews.Session imageReview=TrainingReviews.open(store,imageEdit);
            File photo=new File(store.directory(newer),imageEdit.reference().file);byte[] photoBytes=Files.readAllBytes(photo.toPath());Files.write(photo.toPath(),new byte[]{7,8,9});
            fails(()->imageReview.save(store,imageEdit),"Replaced image cannot inherit old correction coordinates");Files.write(photo.toPath(),photoBytes);
            PhotoAssignments conflict=edit(reload(store,newer));Store.Observation mark=conflict.family().stream().flatMap(v->v.observations.stream()).findFirst().get();
            Store.View familyMember=conflict.family().stream().filter(v->v.observations.isEmpty()).findFirst().get();familyMember.observations.add(new Store.Observation(mark.id,new double[]{mark.a[0]+10,mark.a[1]},null));
            fails(()->TrainingReviews.points(conflict),"Conflicting alias labels are rejected");familyMember.observations.clear();
            familyMember.observations.add(new Store.Observation(mark.id,mark.a.clone(),null));
            check(TrainingReviews.points(conflict).length()==1,"Identical same-ID alias labels deduplicate");familyMember.width++;
            fails(()->TrainingReviews.points(conflict),"Mismatching alias dimensions rejected");familyMember.width--;
            mark.line=true;fails(()->TrainingReviews.points(conflict),"Shaft annotations cannot become point labels");mark.line=false;mark.a[0]=Double.NaN;
            fails(()->TrainingReviews.points(conflict),"Nonfinite coordinates rejected");

            Store.Group detected=PhotoAssignmentsTest.group(store);Store.View detectedView=detected.views.get(0);String modelHash=String.join("",Collections.nCopies(64,"a"));
            TrainingReviews.recordDetection(store,detected,detectedView,Arrays.asList(new double[]{200,300,.7}),modelHash,.16);
            TrainingReviews.Session detectionReview=TrainingReviews.open(store,edit(detected));JSONObject auto=detectionReview.record.getJSONObject("baseline");
            check(auto.getString("kind").equals("detector-output")&&auto.getString("model_sha256").equals(modelHash)&&auto.getDouble("threshold")==.16,"Exact model provenance retained with actual inference");
            check(!detectionReview.checked(edit(detected)),"Automatic detection never claims human review");
            String autoBefore=auto.toString();TrainingReviews.recordDetection(store,detected,detectedView,Collections.emptyList(),modelHash,.1);
            check(json(detectionReview.file).getJSONObject("baseline").toString().equals(autoBefore),"Later inference does not overwrite baseline");
            Store.Group waiting=PhotoAssignmentsTest.group(store);Store.View waitingView=waiting.views.get(0);waitingView.observations.clear();waitingView.calibration=null;PhotoDetection.prepare(store,waitingView);store.save(waiting);
            PhotoDetection.Task waitingTask=new PhotoDetection.Task(store,waiting,waitingView);PhotoAssignments emptyPending=edit(waiting);TrainingReviews.Session acceptedEmpty=TrainingReviews.open(store,emptyPending);acceptedEmpty.setChecked(emptyPending,true);acceptedEmpty.save(store,emptyPending);
            check(!PhotoDetection.complete(store,waitingTask,Arrays.asList(new double[]{100,100,.9}),null,false),"Explicit empty review prevents late automatic detections from replacing it");
            Store.Group notifying=PhotoAssignmentsTest.group(store);PhotoAssignments notifyingEdit=edit(notifying);TrainingReviews.Session notifyingReview=TrainingReviews.open(store,notifyingEdit);notifyingReview.setChecked(notifyingEdit,true);notifyingReview.save(store,notifyingEdit);
            notifyingReview.setChecked(notifyingEdit,false);notifyingEdit.assign(PhotoAssignmentsTest.ids(2),PhotoAssignmentsTest.cal(400));
            Store.onChanged=()->{throw new IllegalStateException("Synthetic post-commit listener failure");};
            try{fails(()->notifyingReview.save(store,notifyingEdit),"Post-rename listener failure reaches caller");}finally{Store.onChanged=null;}
            check(TrainingReviews.pending(notifyingReview.file).exists(),"Post-commit failure cannot revive stale certification");
            for(Store.View aliasView:reload(store,notifying).views)check(new File(store.directory(notifying),aliasView.file).isFile(),"Post-commit failure preserves committed photo aliases");
            notifyingReview.save(store,notifyingEdit);check(!TrainingReviews.pending(notifyingReview.file).exists(),"Retry recovers a post-commit listener failure");
            JSONArray zeros=new JSONArray().put(new JSONObject().put("arrow",1).put("x",-0.0).put("y",12.5));
            String fp=TrainingReviews.fingerprint(modelHash,600,800,zeros);zeros.getJSONObject(0).put("x",0.0);
            check(fp.equals(TrainingReviews.fingerprint(modelHash,600,800,zeros)),"Negative zero normalized for cross-language fingerprints");
            Store.Group quick=PhotoAssignmentsTest.group(store);String quickPhoto=quick.views.get(0).id;
            check(TrainingReviews.checkedPhotos(store,quick).isEmpty(),"Gallery never treats existing detections as human approval");
            File quickJournal=TrainingReviews.file(store,quick.id,quickPhoto);
            check(!quickJournal.exists(),"Gallery status does not write training records");
            String quickPoints=TrainingReviews.points(edit(quick)).toString(),quickImage=TrainingReviews.imageHash(store,edit(quick));
            quick=TrainingReviews.confirm(store,quick,quickPhoto,true);
            check(TrainingReviews.checkedPhotos(store,quick).equals(Collections.singleton(quickPhoto)),"One-tap approval is visible in gallery status");
            Store.Group dotOnly=PhotoAssignmentsTest.group(store);String dotOnlyPhoto=dotOnly.views.get(0).id;
            for(Store.View dotView:dotOnly.views)dotView.calibration=null;store.save(dotOnly);
            int markedBefore=new PhotoReview(dotOnly).marked;
            dotOnly=TrainingReviews.confirm(store,dotOnly,dotOnlyPhoto,true);
            PhotoReview dotOnlySummary=new PhotoReview(dotOnly);
            check(TrainingReviews.checkedPhotos(store,dotOnly).contains(dotOnlyPhoto),"Uncalibrated photo dots can be checked as correct");
            check(dotOnlySummary.marked==markedBefore&&dotOnlySummary.located==0&&dotOnlySummary.needsCalibration==markedBefore,"Dots are correct never fabricates calibration or increases measured count");
            check(TrainingReviews.points(edit(quick)).toString().equals(quickPoints),"Quick approval does not move or renumber any dots");
            check(TrainingReviews.imageHash(store,edit(quick)).equals(quickImage),"Quick approval preserves the original JPEG");
            String quickBaseline=json(quickJournal).getJSONObject("baseline").toString(),quickHash=SyncFiles.hash(quickJournal);
            quick=TrainingReviews.confirm(store,quick,quickPhoto,true);
            check(SyncFiles.hash(quickJournal).equals(quickHash),"Repeated approval is idempotent");
            quick=TrainingReviews.confirm(store,quick,quickPhoto,false);
            check(TrainingReviews.checkedPhotos(store,quick).isEmpty(),"Unchecking immediately removes training eligibility");
            check(json(quickJournal).getJSONObject("baseline").toString().equals(quickBaseline),"Unchecking keeps the original baseline");
            quick=TrainingReviews.confirm(store,quick,quickPhoto,true);
            Store.Group displayed=quick,newerQuick=reload(store,quick);newerQuick.name="Concurrent edit";store.save(newerQuick);String protectedJournal=SyncFiles.hash(quickJournal);
            fails(()->TrainingReviews.confirm(store,displayed,quickPhoto,false),"Quick check cannot overwrite a stale displayed grouping");
            check(SyncFiles.hash(quickJournal).equals(protectedJournal)&&!TrainingReviews.pending(quickJournal).exists(),"Rejected quick check leaves existing certification untouched");
            PhotoAssignments moved=edit(newerQuick);moved.reference().observations.get(0).a[0]+=10;quick=moved.commit(store);
            check(TrainingReviews.checkedPhotos(store,quick).isEmpty(),"Gallery invalidates approval when a dot changes elsewhere");
            quick=TrainingReviews.confirm(store,quick,quickPhoto,true);
            File quickFile=new File(store.directory(quick),quick.views.get(0).file);byte[] originalBytes=Files.readAllBytes(quickFile.toPath());Files.write(quickFile.toPath(),new byte[]{9,8});
            check(TrainingReviews.checkedPhotos(store,quick).isEmpty(),"Replaced photo never inherits a gallery checkmark");Files.write(quickFile.toPath(),originalBytes);
            SyncFiles.atomic(TrainingReviews.pending(quickJournal),"pending");
            check(TrainingReviews.checkedPhotos(store,quick).isEmpty(),"Interrupted save is not checked in the gallery");
            quick=TrainingReviews.confirm(store,quick,quickPhoto,true);
            check(!TrainingReviews.pending(quickJournal).exists(),"Explicit quick approval can finish an interrupted review");
            Store.Group several=PhotoAssignmentsTest.group(store);Store.View otherPhoto=Store.View.from(several.views.get(0).json());otherPhoto.id=UUID.randomUUID().toString();otherPhoto.file=otherPhoto.id+".jpg";several.views.add(otherPhoto);Files.write(new File(store.directory(several),otherPhoto.file).toPath(),new byte[]{5,6,7});store.save(several);
            several=TrainingReviews.confirm(store,several,several.views.get(0).id,true);
            check(TrainingReviews.checkedPhotos(store,several).size()==1&&!TrainingReviews.file(store,several.id,otherPhoto.id).exists(),"Approving one photo does not approve another photo in the same grouping");
            PhotoAssignments multiAlias=edit(several);multiAlias.assign(PhotoAssignmentsTest.ids(2),PhotoAssignmentsTest.cal(400));several=multiAlias.commit(store);
            check(TrainingReviews.checkedPhotos(store,several).size()==1,"Target aliases count as one checked photo");
            Store.Group pendingQuick=PhotoAssignmentsTest.group(store);pendingQuick.views.get(0).detectionState="pending";store.save(pendingQuick);
            fails(()->TrainingReviews.confirm(store,pendingQuick,pendingQuick.views.get(0).id,true),"Quick approval waits for pending detection");
            check(!TrainingReviews.file(store,pendingQuick.id,pendingQuick.views.get(0).id).exists(),"Pending detection creates no approval record");
            System.out.println("Fingerprint parity fixture: "+fp);System.out.println("Training review checks passed: "+checks);
        }finally{try(java.util.stream.Stream<Path> paths=Files.walk(temp)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new RuntimeException(e);}});}}
    }
}
