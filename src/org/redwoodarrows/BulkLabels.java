package org.redwoodarrows;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/** One local transaction and durable undo for context only. No photos are touched. */
final class BulkLabels {
    static final class Change {
        boolean shooter,distance;ShooterRoster.Person person=ShooterRoster.UNASSIGNED;
        double meters=Double.NaN;String unit="yd";
        Store.Group apply(Store.Group original)throws Exception {
            if(!shooter&&!distance)throw new IOException("Choose shooter or distance to change");
            Store.Group g=Store.copy(original);
            if(shooter)Store.confirmShooter(g,person);
            if(distance){g.practice.meters=meters;g.practice.unit=unit;g.practice.validate();}
            return g;
        }
    }
    static File journal(Store store){return new File(store.root.getParentFile(),"bulk-label-undo.json");}
    static String read(File file)throws IOException{return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);}
    static void write(File file,String value)throws IOException {
        File tmp=new File(file.getParentFile(),file.getName()+".bulk-new");
        try(FileOutputStream out=new FileOutputStream(tmp)){out.write(value.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        if(!tmp.renameTo(file))throw new IOException("Could not commit labels; saved versions are preserved");
    }
    static File target(Store store,JSONObject row)throws Exception {return new File(store.root,SyncFiles.id(row.getString("id"))+"/group.json");}
    static boolean same(String a,String b)throws Exception{return SyncFiles.canonical(new JSONObject(a)).equals(SyncFiles.canonical(new JSONObject(b)));}
    static void recover(Store store)throws Exception {
        synchronized(Store.FILE_LOCK){
            File file=journal(store);if(!file.isFile())return;JSONObject j=new JSONObject(read(file));
            if(!j.getString("state").equals("pending"))return;
            JSONArray rows=j.getJSONArray("rows");
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String current=read(target(store,row));if(!same(current,row.getString("before"))&&!same(current,row.getString("after")))throw new IOException("Interrupted label edit conflicts with newer data; all versions retained");}
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!same(read(target(store,row)),row.getString("before")))write(target(store,row),row.getString("before"));}
            j.put("state","recovered");write(file,j.toString());
        }
    }
    static int apply(Store store,List<Store.Group> groups,Change change)throws Exception {
        synchronized(Store.FILE_LOCK){
            recover(store);if(groups.isEmpty())throw new IOException("Select at least one grouping");
            JSONArray rows=new JSONArray();Set<String> ids=new HashSet<>();
            for(Store.Group original:groups){
                if(!ids.add(original.id))continue;
                File file=new File(store.directory(original),"group.json");String before=read(file);
                if(!same(before,original.json().toString()))throw new IOException("A selected grouping changed. Reopen the selection; no labels were changed.");
                Store.Group next=change.apply(Store.Group.from(new JSONObject(before)));String after=next.json().toString(2);
                if(!same(before,after))rows.put(new JSONObject().put("id",original.id).put("before",before).put("after",after));
            }
            if(rows.length()==0)return 0;
            JSONObject j=new JSONObject().put("state","pending").put("rows",rows);write(journal(store),j.toString());
            try{
                for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);write(target(store,row),row.getString("after"));}
                j.put("state","applied");write(journal(store),j.toString());
            }catch(Exception e){try{recover(store);}catch(Exception recovery){e.addSuppressed(recovery);}throw e;}
            Store.changed();return rows.length();
        }
    }
    static int undoCount(Store store)throws Exception {
        synchronized(Store.FILE_LOCK){
            File file=journal(store);if(!file.isFile())return 0;JSONObject j=new JSONObject(read(file));if(!j.getString("state").equals("applied"))return 0;
            JSONArray rows=j.getJSONArray("rows");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!same(read(target(store,row)),row.getString("after")))return 0;}return rows.length();
        }
    }
    static int undo(Store store)throws Exception {
        synchronized(Store.FILE_LOCK){
            int count=undoCount(store);if(count==0)throw new IOException("These groupings changed after labeling; their newer data was kept");
            JSONObject j=new JSONObject(read(journal(store)));JSONArray original=j.getJSONArray("rows"),reverse=new JSONArray();
            for(int i=0;i<original.length();i++){JSONObject row=original.getJSONObject(i);reverse.put(new JSONObject().put("id",row.getString("id")).put("before",row.getString("after")).put("after",row.getString("before")));}
            JSONObject undo=new JSONObject().put("state","pending").put("rows",reverse);write(journal(store),undo.toString());
            try{for(int i=0;i<reverse.length();i++){JSONObject row=reverse.getJSONObject(i);write(target(store,row),row.getString("after"));}undo.put("state","undone");write(journal(store),undo.toString());}
            catch(Exception e){try{recover(store);}catch(Exception recovery){e.addSuppressed(recovery);}throw e;}
            Store.changed();return count;
        }
    }
}
