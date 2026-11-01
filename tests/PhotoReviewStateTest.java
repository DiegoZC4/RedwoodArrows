package org.redwoodarrows;

import java.util.*;

public final class PhotoReviewStateTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static Geometry.Calibration target(double center){Geometry.Calibration c=new Geometry.Calibration(new double[]{.005,0,-1,0,-.005,center*.005,0,0,1},.001,"fixture");c.reviewed=true;return c;}
    static Store.Group fixture(){
        Store.Group g=Store.Group.start(TargetCatalog.find("mlp-nfh-35"),ShooterRoster.UNASSIGNED);g.id="test-photo-label";
        Store.View loose=new Store.View(),known=new Store.View();loose.id="original";known.id="alias";loose.file="original.jpg";known.file="alias.jpg";
        for(Store.View v:Arrays.asList(loose,known)){v.width=600;v.height=800;v.session="photo-assign:original";g.views.add(v);}
        known.calibration=target(200);for(int i=1;i<=7;i++)(i<=4?loose:known).observations.add(new Store.Observation(i,new double[]{100+i*30,300+i*20},null));return g;
    }
    public static void main(String[] args)throws Exception {
        Store.Group group=fixture();PhotoAssignments model=new PhotoAssignments(group,"original");PhotoReviewState state=new PhotoReviewState();String original=model.snapshot();state.sync(model);
        check(state.selected.isEmpty(),"Opening review does not silently select all arrows");
        check(state.targets.size()==1&&state.preview==-1,"Saved targets are available without an applied preview");
        state.toggle(1,model);state.toggle(2,model);state.toggle(3,model);check(state.selected.equals(new TreeSet<>(Arrays.asList(1,2,3))),"Arrows toggle into a multi-selection");
        state.toggle(2,model);check(state.selected.equals(new TreeSet<>(Arrays.asList(1,3))),"Tapping again removes only that arrow");
        state.toggle(99,model);check(state.selected.size()==2,"Unknown arrow IDs cannot enter selection");
        check(!state.canMove(),"Multi-selection cannot accidentally move several arrows");state.selected.clear();state.toggle(1,model);check(state.canMove(),"A single explicit selection enables Move");
        state.selectAll(model);check(state.selected.size()==7,"Select all is explicit");state.selectUnassigned(model);check(state.selected.equals(new TreeSet<>(Arrays.asList(1,2,3,4))),"Unassigned scope leaves already matched arrows alone");
        check(model.snapshot().equals(original)&&model.revision==0,"Selection changes are not photo edits or autosaves");
        int lower=state.addTarget(target(600));state.choose(lower);check(lower==1&&state.pending()!=null,"Lower target can be previewed");
        check(model.snapshot().equals(original)&&model.revision==0,"Target preview never assigns or measures anything");
        check(state.addTarget(target(600))==lower&&state.targets.size()==2,"Repeated target discovery keeps labels stable");
        state.selected.remove(4);String label=PhotoReviewState.photoLabel(model.draft,"original");int applied=state.apply(model);
        check(applied==3&&model.revision==1,"Only the explicit Assign action changes the draft");
        check(state.labels(model).get(1).equals("B")&&state.labels(model).get(5).equals("A"),"Newly assigned target does not reorder existing A/B labels");
        check(state.unassigned(model).equals(Collections.singleton(4)),"The unselected fourth arrow remains unassigned");
        check(new PhotoReview(model.draft).located==6&&new PhotoReview(model.draft).marked==7,"Assignment preserves marks and measures only its selected subset");
        check(state.preview==-1,"Applied preview is no longer shown as pending");
        check(label.equals(PhotoReviewState.photoLabel(model.draft,model.draft.views.get(2).id)),"Photo label stays identical across target aliases");
        Store.Group reloaded=Store.Group.from(model.draft.json());check(label.equals(PhotoReviewState.photoLabel(reloaded,"original")),"Photo label survives serialization");
        PhotoReviewState reopened=new PhotoReviewState();PhotoAssignments reloadModel=new PhotoAssignments(reloaded,"original");reopened.sync(reloadModel);check(reopened.labels(reloadModel).get(1).equals("B"),"Assigned target letter survives reopening");
        Geometry.Calibration savedD=target(1000);savedD.reviewLabel="D";PhotoReviewState gaps=new PhotoReviewState();gaps.addTarget(target(200));gaps.addTarget(savedD);gaps.addTarget(target(600));check(gaps.targetName(1).equals("D")&&gaps.targetName(2).equals("B"),"Saved target letter remains stable when unsaved candidates disappear");
        check(label.matches("Photo [0-9A-F]{4}-[0-9A-F]{4}"),"Photo label is short and readable");
        check(group.views.size()==2&&group.views.get(0).observations.size()==4,"Draft changes do not mutate source group");
        state.selected.clear();state.choose(1);String before=model.snapshot();boolean blocked=false;try{state.apply(model);}catch(IllegalArgumentException expected){blocked=true;}check(blocked&&before.equals(model.snapshot()),"No selected arrows means no implicit mass assignment");
        Geometry.Calibration bad=target(600);bad.h[0]=Double.NaN;check(state.addTarget(bad)==-1,"Invalid proposal cannot become an assignable target");
        check(PhotoReviewState.name(0).equals("A")&&PhotoReviewState.name(25).equals("Z")&&PhotoReviewState.name(26).equals("AA"),"Target labels remain readable beyond Z");
        PhotoAssignments empty=new PhotoAssignments(fixture(),"original");for(Store.View view:empty.family())view.observations.clear();PhotoReviewState emptyState=new PhotoReviewState();emptyState.sync(empty);emptyState.choose(emptyState.addTarget(target(600)));emptyState.apply(empty);
        check(empty.ids().isEmpty()&&empty.singleConfirmedTarget()!=null,"An explicit target can be saved before the first dot");
        check(PhotoReviewState.photoLabel(empty.draft,"original").equals(label),"Empty and assigned variants keep the same photo identity");
        System.out.println("Photo review selection checks passed: "+checks);
    }
}
