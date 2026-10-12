package org.redwoodarrows;

import android.content.Intent;
import android.net.Uri;
import android.os.Parcelable;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;

/** Small visual catalog; artwork browsing is separate from measured 2D plots. */
final class TargetGallery {
    private final MainActivity a;
    String query="";boolean otherDesigns=false;
    List<TargetDesigns.Match> matches=new ArrayList<>();
    EditText search;GridView grid;ListView variants;TextView count;Button clear,section;
    TargetDesigns.Match opened;TargetPreset preview;
    int gridIndex,gridTop,variantIndex,variantTop;
    Parcelable gridState,variantState;
    TargetGallery(MainActivity activity){a=activity;}
    void open(){showBrowse();}
    private void hideKeyboard(){
        View focus=a.getCurrentFocus();if(focus!=null)((InputMethodManager)a.getSystemService(MainActivity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(),0);
    }
    private void header(String title,String back,Runnable action){
        hideKeyboard();a.shell(title);a.root.setFocusableInTouchMode(true);
        a.add(a.root,a.button("‹  "+back,action));
    }
    private void rememberGrid(){if(grid!=null){gridIndex=grid.getFirstVisiblePosition();gridTop=grid.getChildCount()==0?0:grid.getChildAt(0).getTop();gridState=grid.onSaveInstanceState();}}
    private void rememberVariants(){if(variants!=null){variantIndex=variants.getFirstVisiblePosition();variantTop=variants.getChildCount()==0?0:variants.getChildAt(0).getTop();variantState=variants.onSaveInstanceState();}}
    private TextView compact(String value,int size,int color,int lines){TextView text=a.text(value,size,color);text.setMaxLines(lines);text.setEllipsize(TextUtils.TruncateAt.END);return text;}

    void showBrowse(){
        a.page="gallery";header("Target gallery","Back",a::closeCatalog);
        LinearLayout searchRow=a.row();
        search=new EditText(a);search.setTag("target-search");search.setSingleLine(true);search.setTextSize(16);search.setTextColor(MainActivity.FG);
        search.setHintTextColor(MainActivity.MUTED);search.setHint("Search all names or codes");search.setContentDescription("Search all target names or codes");
        search.setBackground(a.rounded(MainActivity.PANEL));search.setPadding(a.dp(12),a.dp(10),a.dp(12),a.dp(10));
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);search.setText(query);
        search.setOnEditorActionListener((v,action,event)->{hideKeyboard();return true;});
        searchRow.addView(search,new LinearLayout.LayoutParams(0,a.dp(50),1));
        clear=a.button("×",()->search.setText(""));clear.setContentDescription("Clear target search");
        LinearLayout.LayoutParams clearParams=new LinearLayout.LayoutParams(a.dp(48),a.dp(50));clearParams.setMargins(a.dp(6),0,0,0);searchRow.addView(clear,clearParams);a.add(a.root,searchRow);
        count=a.text("",13,MainActivity.MUTED);a.add(a.root,count);
        grid=new GridView(a);grid.setTag("target-grid");grid.setNumColumns(2);grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(a.dp(10));grid.setVerticalSpacing(a.dp(10));grid.setPadding(0,0,0,a.dp(8));grid.setClipToPadding(false);
        a.root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
        TextView empty=a.text("No targets found.\nTry Field, Vegas, bear, or a printed code.",17,MainActivity.MUTED);empty.setGravity(Gravity.CENTER);
        a.root.addView(empty,new LinearLayout.LayoutParams(-1,0,1));grid.setEmptyView(empty);
        section=a.button("",()->{otherDesigns=!otherDesigns;query="";gridIndex=0;gridTop=0;gridState=null;showBrowse();});a.add(a.root,section);
        a.add(a.root,a.text("Previews © Maple Leaf Press · available offline",11,MainActivity.MUTED));
        grid.setOnItemClickListener((parent,v,position,id)->{rememberGrid();opened=matches.get(position);variantIndex=0;variantTop=0;variantState=null;if(opened.variants.size()==1)showPreset(opened.variants.get(0));else showVariants();});
        refresh();
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int start,int before,int count){query=s.toString();gridIndex=0;gridTop=0;gridState=null;refresh();}});
        a.root.requestFocus();a.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        // Preserve native selected-row/sync state too: setSelectionFromTop alone
        // can jump when the newly attached grid receives keyboard focus.
        if(gridState!=null)grid.onRestoreInstanceState(gridState);else grid.setSelectionFromTop(gridIndex,gridTop);
    }
    private void refresh(){
        matches=TargetDesigns.search(query,otherDesigns);boolean searching=!query.trim().isEmpty();
        int sizes=0;for(TargetDesigns.Match m:matches)sizes+=m.variants.size();
        count.setText(searching?matches.size()+" design"+(matches.size()==1?"":"s")+" · "+sizes+" matching size"+(sizes==1?" / layout":"s / layouts"):
            otherDesigns?"Animal & game designs · search covers every target":"10 ring designs · tap for sizes & layouts");
        clear.setVisibility(searching?View.VISIBLE:View.GONE);
        section.setText(otherDesigns?"Browse ring designs":"Animals & games · 47 designs");section.setVisibility(searching?View.GONE:View.VISIBLE);
        grid.setAdapter(new BaseAdapter(){
            public int getCount(){return matches.size();}public Object getItem(int p){return matches.get(p);}public long getItemId(int p){return p;}
            public View getView(int position,View recycled,ViewGroup parent){
                TargetDesigns.Match m=matches.get(position);TargetPreset p=m.preview(a.selected.id);
                LinearLayout card=new LinearLayout(a);card.setTag("design:"+m.design.id);card.setOrientation(1);card.setPadding(a.dp(10),a.dp(10),a.dp(10),a.dp(9));card.setBackground(a.rounded(MainActivity.PANEL));
                card.addView(new TargetThumbnail(a,p),new LinearLayout.LayoutParams(-1,a.dp(132)));
                TextView name=compact(m.design.name,17,MainActivity.FG,2);name.setMinLines(2);name.setTypeface(null,1);card.addView(name);
                TextView summary=compact(m.design.summary,12,MainActivity.MUTED,2);summary.setMinLines(2);card.addView(summary);
                boolean selected=false;for(TargetPreset variant:m.variants)if(variant.id.equals(a.selected.id))selected=true;
                String label=selected?"Selected":m.variants.size()==1?p.sku.split(" / ")[0]:m.variants.size()+" sizes / layouts";
                card.addView(compact(label,12,MainActivity.ACCENT,1));return card;
            }
        });
    }
    void showVariants(){
        a.page="gallery-variants";header(opened.design.name,"Gallery",this::showBrowse);
        a.add(a.root,a.text(opened.design.summary+"\nChoose a size or layout"+(query.trim().isEmpty()?"":" · matches “"+query+"”"),14,MainActivity.MUTED));
        variants=new ListView(a);variants.setTag("target-variants");variants.setDivider(null);variants.setDividerHeight(a.dp(8));
        variants.setAdapter(new BaseAdapter(){
            public int getCount(){return opened.variants.size();}public Object getItem(int p){return opened.variants.get(p);}public long getItemId(int p){return p;}
            public View getView(int position,View recycled,ViewGroup parent){
                TargetPreset p=opened.variants.get(position);LinearLayout card=a.row();card.setTag("variant:"+p.id);card.setGravity(Gravity.CENTER_VERTICAL);card.setBackground(a.rounded(MainActivity.PANEL));card.setPadding(a.dp(10),a.dp(10),a.dp(10),a.dp(10));
                card.addView(new TargetThumbnail(a,p),new LinearLayout.LayoutParams(a.dp(92),a.dp(98)));
                LinearLayout words=new LinearLayout(a);words.setOrientation(1);words.setPadding(a.dp(12),0,0,0);card.addView(words,new LinearLayout.LayoutParams(0,-2,1));
                TextView name=compact(TargetDesigns.variantLabel(p),16,MainActivity.FG,3);name.setTypeface(null,1);words.addView(name);
                words.addView(compact(p.dimensions(),13,MainActivity.MUTED,2));
                words.addView(compact((p.id.equals(a.selected.id)?"Selected · ":"")+p.sku.split(" / ")[0],12,MainActivity.ACCENT,2));return card;
            }
        });
        variants.setOnItemClickListener((parent,v,position,id)->{rememberVariants();showPreset(opened.variants.get(position));});
        a.root.addView(variants,new LinearLayout.LayoutParams(-1,0,1));if(variantState!=null)variants.onRestoreInstanceState(variantState);else variants.setSelectionFromTop(variantIndex,variantTop);
    }
    void showPreset(TargetPreset p){
        preview=p;a.page="gallery-preset";header("Target preview",opened!=null&&opened.variants.size()>1?opened.design.name:"Gallery",this::back);
        ScrollView scroll=new ScrollView(a);LinearLayout body=new LinearLayout(a);body.setOrientation(1);scroll.addView(body);a.root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView name=a.text(p.name,21,MainActivity.FG);name.setTypeface(null,1);a.add(body,name);
        body.addView(new TargetThumbnail(a,p),new LinearLayout.LayoutParams(-1,a.dp(240)));
        a.add(body,a.text(p.dimensions()+"\n"+p.sku,15,MainActivity.ACCENT));
        a.add(body,a.text(p.circular()?"Measure the outer edge of ONE circle, then: "+p.innerHint:"Measure the four corners of the flat, untrimmed paper. Positions are relative to its center; animal vital areas and game scoring are not mapped.",14,MainActivity.MUTED));
        if(!p.note.isEmpty())a.add(body,a.text(p.note.replace("proprietary animal artwork and vital-area contours are not reproduced","the measurement diagram does not reproduce animal artwork or vital-area contours"),13,MainActivity.MUTED));
        a.add(body,a.text("Maker's illustration for identification only. Arrow recognition is not validated on every design.",12,MainActivity.MUTED));
        a.add(body,a.button("Maker's page ↗",()->{try{a.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(p.source)));}catch(Exception e){a.message("Source: "+p.source);}}));
        Button use=a.button("Use this target",()->a.selectPreset(p));use.setTag("use-target");use.setBackground(a.rounded(MainActivity.ACCENT));use.setTextColor(MainActivity.BG);a.add(a.root,use);
    }
    void back(){
        if(a.page.equals("gallery-preset")&&opened!=null&&opened.variants.size()>1)showVariants();
        else if(a.page.equals("gallery")){hideKeyboard();a.closeCatalog();}
        else showBrowse();
    }
}
