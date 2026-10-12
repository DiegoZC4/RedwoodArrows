package org.redwoodarrows;

import android.app.AlertDialog;
import android.content.*;
import android.text.InputType;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Optional account controls; photography and review never require a login. */
final class SyncUi {
    private final MainActivity a;
    SyncUi(MainActivity a){this.a=a;}
    interface RemoteAction {void run()throws Exception;}
    void task(RemoteAction action,Runnable done){
        SyncRuntime.network.execute(()->{try{action.run();a.runOnUiThread(()->{if(!a.isDestroyed()){done.run();a.refreshSync();}});}catch(Exception e){a.runOnUiThread(()->{if(!a.isDestroyed())a.message(e.getMessage()==null?"Could not connect. Your local groupings are safe.":e.getMessage());});}});
    }
    void show(){
        if(!new SyncSession(a).present()||SyncRuntime.status.contains("sign in")){signIn();return;}
        task(()->{},()->account());
    }
    private LinearLayout body(){LinearLayout b=new LinearLayout(a);b.setOrientation(1);b.setPadding(a.dp(20),a.dp(8),a.dp(20),a.dp(8));return b;}
    private EditText input(LinearLayout body,String hint,boolean secret){EditText v=new EditText(a);v.setHint(hint);v.setContentDescription(hint);v.setSingleLine(true);v.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);v.setImportantForAutofill(secret?android.view.View.IMPORTANT_FOR_AUTOFILL_YES:android.view.View.IMPORTANT_FOR_AUTOFILL_AUTO);v.setAutofillHints(secret?android.view.View.AUTOFILL_HINT_PASSWORD:android.view.View.AUTOFILL_HINT_USERNAME);body.addView(v,new LinearLayout.LayoutParams(-1,-2));return v;}
    void signIn(){
        LinearLayout b=body();final boolean[] create={false};EditText user=input(b,"Username",false),password=input(b,"Password (12+ characters)",true),invite=input(b,"Invitation code",false);invite.setAutofillHints((String[])null);invite.setVisibility(android.view.View.GONE);
        TwoShooterSwitch choice=new TwoShooterSwitch(a,"Sign in","Join library",0,which->{create[0]=which==1;invite.setVisibility(create[0]?android.view.View.VISIBLE:android.view.View.GONE);return true;});b.addView(choice,0);
        TextView explanation=new TextView(a);explanation.setText("diegozc.com · private shared library\nSigning in syncs this phone’s saved groupings and retained photos with everyone invited to the library. Local copies remain available offline.");explanation.setPadding(0,a.dp(12),0,0);b.addView(explanation);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Shared library").setView(b).setPositiveButton("Continue",null).setNegativeButton("Not now",null).create();dialog.show();
        dialog.getButton(-1).setOnClickListener(v->{
            String name=user.getText().toString().trim(),pass=password.getText().toString(),code=invite.getText().toString().trim();if(name.isEmpty()||pass.isEmpty()){password.setError("Enter your username and password");return;}
            final boolean joining=create[0];dialog.getButton(-1).setEnabled(false);dialog.getButton(-2).setEnabled(false);dialog.setCancelable(false);choice.setEnabled(false);user.setEnabled(false);password.setEnabled(false);invite.setEnabled(false);dialog.getButton(-1).setText("Connecting…");SyncRuntime.network.execute(()->{
                try{
                    JSONObject request=new JSONObject().put("username",name).put("password",pass);if(joining)request.put("invite",code);
                    JSONObject session=new SyncHttp("").json(joining?"signup":"login",request);
                    SyncEngine engine=SyncRuntime.engine(a,session);engine.bind(session.getString("library"));new SyncSession(a).write(session);SyncJobs.schedule(a);
                    a.runOnUiThread(()->{if(!a.isDestroyed()){password.setText("");invite.setText("");dialog.dismiss();SyncRuntime.request(a,null);a.refreshSync();}});
                }catch(Exception e){a.runOnUiThread(()->{if(!a.isDestroyed()){dialog.getButton(-1).setEnabled(true);dialog.getButton(-1).setText("Continue");dialog.getButton(-2).setEnabled(true);dialog.setCancelable(true);choice.setEnabled(true);user.setEnabled(true);password.setEnabled(true);invite.setEnabled(true);password.setError(e.getMessage()==null?"Could not connect; try again online":e.getMessage());}});}
            });
        });
    }
    private void account(){
        final JSONObject session;try{session=new SyncSession(a).read();if(session==null){signIn();return;}}catch(Exception e){a.message("This phone cannot read its sign-in. Your groupings are safe. Sign in again.");signIn();return;}
        List<String> labels=new ArrayList<>(Arrays.asList("Sync now","Review edit conflicts","Change password","Sign out"));if("owner".equals(session.optString("role")))labels.add("Invite Iris / another phone");
        new AlertDialog.Builder(a).setTitle(session.optString("username")+" · shared library").setItems(labels.toArray(new String[0]),(d,which)->{
            if(which==0){SyncRuntime.request(a,null);a.refreshSync();}
            else if(which==1)conflicts(session);
            else if(which==2)password(session);
            else if(which==3)new AlertDialog.Builder(a).setMessage("Sign out of sync? Groupings and downloaded photos stay on this phone. Anyone using this unlocked phone can still review them.").setPositiveButton("Sign out",(x,w)->task(()->{try{new SyncHttp(session.getString("token")).json("logout",new JSONObject());}catch(SyncHttp.Failure e){if(e.status!=401)throw e;}new SyncSession(a).clear();SyncJobs.cancel(a);SyncRuntime.status="Saved on this phone";},a::refreshSync)).setNegativeButton("Cancel",null).show();
            else task(()->{JSONObject result=new SyncHttp(session.getString("token")).json("invite",new JSONObject());String code=result.getString("invite");a.runOnUiThread(()->new AlertDialog.Builder(a).setTitle("One-use invitation · expires in 7 days").setMessage("Share this privately. It grants access to all shared groupings and photos.\n\n"+code).setPositiveButton("Copy code",(x,w)->{ClipData clip=ClipData.newPlainText("Private Redwood invitation",code);android.os.PersistableBundle flags=new android.os.PersistableBundle();flags.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(flags);((ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(clip);}).setNegativeButton("Close",null).show());},()->{});
        }).setNegativeButton("Close",null).show();
    }
    private void password(JSONObject session){LinearLayout b=body();EditText old=input(b,"Current password",true),next=input(b,"New password (12+ characters)",true);
        new AlertDialog.Builder(a).setTitle("Change password · signs out other devices").setView(b).setPositiveButton("Save",(d,w)->{String before=old.getText().toString(),after=next.getText().toString();task(()->{JSONObject response=new SyncHttp(session.getString("token")).json("password",new JSONObject().put("old_password",before).put("password",after));new SyncSession(a).write(response);},()->a.message("Password updated. Other devices need to sign in again."));}).setNegativeButton("Cancel",null).show();}
    private void conflicts(JSONObject session){
        task(()->{SyncEngine engine=SyncRuntime.engine(a,session);List<String> ids=engine.conflicts();List<String> labels=new ArrayList<>();for(String id:ids){Store.Group g=a.store.read(new java.io.File(new java.io.File(a.store.root,id),"group.json"));labels.add(g.title()+" · "+g.shooter.label());}
            a.runOnUiThread(()->{if(ids.isEmpty()){a.message("No conflicting edits. Photos of different groupings sync automatically.");return;}
                new AlertDialog.Builder(a).setTitle("Both versions are safe").setItems(labels.toArray(new String[0]),(d,i)->compare(session,ids.get(i))).setNegativeButton("Close",null).show();});},()->{});
    }
    private void compare(JSONObject session,String id){task(()->{
        Store.Group local=a.store.read(new java.io.File(new java.io.File(a.store.root,SyncFiles.id(id)),"group.json"));
        JSONObject saved=new JSONObject(SyncFiles.read(new java.io.File(SyncRuntime.directory(a),"conflict-"+id+".json")));
        Store.Group shared=Store.Group.from(new JSONObject(saved.getString("body")));long reviewedRevision=saved.getLong("revision");
        a.runOnUiThread(()->{LinearLayout b=body(),plots=a.row();b.setBackgroundColor(MainActivity.BG);
            for(int i=0;i<2;i++){Store.Group g=i==0?local:shared;LinearLayout column=new LinearLayout(a);column.setOrientation(1);column.addView(a.text(i==0?"This phone":"Shared",14,MainActivity.FG));column.addView(new TargetCanvas(a,g).thumbnail(),new LinearLayout.LayoutParams(-1,a.dp(150)));column.addView(a.text(g.shooter.label()+" · "+Store.results(g).size()+" arrows\n"+g.title(),12,MainActivity.MUTED));plots.addView(column,new LinearLayout.LayoutParams(0,-2,1));}
            b.addView(plots);a.add(b,a.text("Both versions are retained for recovery. This choice does not delete photos.",12,MainActivity.MUTED));
            new AlertDialog.Builder(a).setTitle("Choose this grouping’s version").setView(b).setPositiveButton("Keep this phone",(d,w)->resolve(session,id,true,reviewedRevision)).setNeutralButton("Use shared",(d,w)->resolve(session,id,false,reviewedRevision)).setNegativeButton("Later",null).show();});
    },()->{});}
    private void resolve(JSONObject session,String id,boolean keep,long reviewedRevision){task(()->{SyncRuntime.engine(a,session).resolve(id,keep,reviewedRevision);},()->{SyncRuntime.request(a,null);if(a.page.equals("home"))a.home();});}
}
