package org.redwoodarrows;

import java.util.*;

/** Maple Leaf Press public target-design catalog, verified 2026-10-07.
 * Paper-stock / waterproof variants share geometry; dual faces have separate sides.
 * Non-target accessories (signs, replacement bonus stickers) are not target presets.
 * Sources and exclusions are documented in README.md (Target catalog).
 */
public final class TargetCatalog {
    public static final String DEFAULT_ID="mlp-nff-35";
    static final String BASE="https://www.mapleleafpress.com/";
    static final String WA_FLYER=BASE+"assets/mlp_wa-fita_official_targets_flyer.pdf";
    static final String NFAA_FLYER=BASE+"assets/mlp_nfaa_official_-_nasp_targets_flyer.pdf";
    private static final List<TargetPreset> ITEMS=new ArrayList<>();
    private static final Map<String,TargetPreset> BY_ID=new LinkedHashMap<>();
    static {
        // Put the exact photographed default first, followed by the other field sizes.
        for(int cm:new int[]{35,20,50,65})field(false,cm);
        for(int cm:new int[]{35,20,50,65})field(true,cm);
        for(int cm:new int[]{40,60,80,122})wa("wa-"+cm,"World Archery · "+cm+" cm", "WA / colored", "TA-"+cm+" / WP-"+cm+(cm<=60?" / FT-"+cm:""),"10-ring.html",cm,1,1,"combined","");
        wa("classic-92","NFAA Classic · 92 cm","NFAA / NASP","TA-92","10-ring.html",92,1,1,"combined","");
        wa("nasp-80","NASP · 80 cm","NFAA / NASP","NASP LT-80 / TA-80 / WP-80","10-ring.html",80,1,1,"combined","");
        for(int cm:new int[]{80,92,122})wa("center-"+cm,"Center spot · "+cm+" cm nominal", "WA / colored", "TA-1x"+cm+"C"+(cm==92?"":" / WP-1x"+cm+"C"),"10-ring.html",cm,.5,1,"combined","Inner five rings only; not a full "+cm+" cm face.");
        wa("center-80-six","80 cm nominal · six-ring center","WA / colored","TA-6x80C / WP-6x80C","10-ring.html",80,.6,1,"combined","Six visible rings; actual printed circle is 48 cm.");
        wa("nasp-center-80","NASP · 80 cm nominal center","NFAA / NASP","NASP TA-1x80C / WP-1x80C","10-ring.html",80,.5,1,"combined","Inner five rings; actual printed circle is 40 cm.");
        paper("patch-122","122 cm center patch","WA / colored","TA-122 Center Patch","10-ring.html",11.5,11.5,1,"Patch circle diameter is not published here. Calibrate its untrimmed paper; do not assume a 122 cm circle.");
        for(String layout:new String[]{"triangular","vertical"})for(String variant:new String[]{"combined","compound","recurve"}){
            String code=layout.equals("triangular")?"W":"V",suffix=variant.equals("combined")?"":variant.equals("compound")?"-C":"-R";
            wa("triple-40-"+layout+"-"+variant,"40 cm nominal · "+layout+" triple · "+variant,"WA multi-spot","TA-3x40"+code+suffix+(suffix.isEmpty()?" / FT-3x40"+code+" / WP-3x40"+code:""),"3-spot-5-spot.html",40,.5,3,variant,"Calibrate ONE 20 cm spot. Keep the same spot across all views.");
        }
        wa("triple-60-vertical","60 cm nominal · vertical triple","WA multi-spot","TA-3x60V / WP-3x60V","3-spot-5-spot.html",60,.5,3,"combined","Calibrate ONE 30 cm spot. The site's sheet width conflicts with its flyer; circle geometry does not use that width.");
        wa("vegas-triple","Vegas · triangular triple","Vegas","FT-3x40G / TA-3x40G / WP-3x40G","3-spot-5-spot.html",40,.5,3,"combined","Gray background. Calibrate ONE 20 cm spot.");
        wa("vegas-dual-single","Dual Vegas · single side","Vegas","TA-40/3x40G · single side","3-spot-5-spot.html",40,1,1,"combined","One side of the reversible target.");
        wa("vegas-dual-triple","Dual Vegas · triple side","Vegas","TA-40/3x40G · triple side","3-spot-5-spot.html",40,.5,3,"combined","Other side of the reversible target; one spot per measurement group.");
        wa("vegas-five","Vegas · five-spot","Vegas","TA-5x40G",NFAA_FLYER,40,.4,5,"combined","Only rings 7–10: each circle is 16 cm, not 40 cm. Manufacturer labels this a practice design.");
        paper("proam","Team Pro · ProAm","NFAA / NASP","NFI-3T",NFAA_FLYER,17,17,3,"Use sheet corners: the flyer's nominal size alone does not specify every spot boundary.");
        for(int cm:new int[]{20,40,60,80}){
            String sku=cm==20?"NAAF-TA-3x20 / NAAF-WP-3x20":"NAAF-"+cm+" / NAAFWP-"+cm;
            add(circle("fita-field-"+cm,"WA Field · "+cm+" cm"+(cm==20?" triple":""),"WA Field", "WA Field",sku,"fita-field.html",cm*10,cm==20?3:1,.2,"Outer edge of the YELLOW bull (20% of the circle radius).",new double[]{.1,.2,.4,.6,.8,1},new double[]{1,.2},new int[]{TargetPreset.BLACK,TargetPreset.GOLD},"Black and yellow. "+(cm==20?"One spot per measurement group.":"")));
        }
        add(circle("fita-field-20-single","WA Field · 20 cm single","WA Field","WA Field","NAAF-20 / NAAFWP-20","assets/mlp_authentic_animal_fita_flyer_09232.pdf",200,1,.2,"Outer edge of the YELLOW bull (20% of the circle radius).",new double[]{.1,.2,.4,.6,.8,1},new double[]{1,.2},new int[]{TargetPreset.BLACK,TargetPreset.GOLD},"The current manufacturer flyer also lists this single-spot version."));
        blue("indoor-single","NFAA Indoor · single","NFI-1P / NFI-1T",400,1,"nfaa-indoor.html","");
        blue("indoor-five","NFAA Indoor · five-spot","NFI-2P / NFI-2T",160,5,"nfaa-indoor.html","One spot per measurement group.");
        blue("indoor-dual-single","NFAA Indoor dual · single side","NFI-4P / NFI-4T · single side",400,1,"nfaa-indoor.html","");
        blue("indoor-dual-five","NFAA Indoor dual · five-spot side","NFI-4P / NFI-4T · five-spot side",160,5,"nfaa-indoor.html","One spot per measurement group.");
        blue("indoor-five-vertical","NFAA Indoor · vertical five-spot","NFI-5P / NFI-5T",160,5,"nfaa-indoor.html","Manufacturer lists this layout as non-official. One spot per measurement group.");
        wa("reduced-single","Half-size WA · single","Reduced","RTA-40 / RWP-40","reduced.html",20,1,1,"combined","50% scale: actual full face is 20 cm.");
        for(String layout:new String[]{"triangular","vertical"})wa("reduced-triple-"+layout,"Half-size WA · "+layout+" triple","Reduced","RTA-3x40"+(layout.equals("triangular")?"W / RWP-3x40W":"V"),"reduced.html",20,.5,3,"combined","50% scale: each visible spot is 10 cm.");
        blue("reduced-indoor-single","Half-size NFAA · single","RNFI-1T",200,1,"reduced.html","50% scale: actual face is 20 cm.");
        blue("reduced-indoor-five","Half-size NFAA · five-spot","RNFI-2T",80,5,"reduced.html","50% scale: each spot is 8 cm. One spot per group.");
        hitMiss("academic","Academic Hit/Miss",976,244,"A-H/M TA / WP");
        hitMiss("compound","Compound Hit/Miss",300,100,"C-H/M WP");
        String[] animals={"Bear","Grizzly","Deer","Moose","Elk","Caribou","Cougar","Deer","Wolf","Antelope","Ram","Mountain Goat","Raccoon","Javalina","Lynx","Turkey","Fox","Goose","Pheasant","Coyote","Rabbit","Duck","Grouse","Woodchuck","Crow","Squirrel","Prairie Dog","Muskrat"};
        for(int i=1;i<=28;i++){
            double w=i<=6?40:i<=12?28.5:i<=20?22.5:14.25,h=i<=6?28:i<=12?22.5:i<=20?14.25:11.25;
            if(Arrays.asList(8,10,16,18,23,24,27,28).contains(i)){double t=w;w=h;h=t;}
            String sku=String.format(Locale.US,"NFA-%02d",i);
            paper("animal-"+i,sku+" · "+animals[i-1],"NFAA Animals",sku,"nfaa-animals.html",w,h,1,"Sheet-plane positions only. Animal outline, vital area and bonus-sticker scoring are not digitized.");
        }
        String[][] authentic={{"01","Deer · ground level","38","25"},{"02","Deer · tree stand","25","38"},{"04","Elk","38","25"},{"05","Mule Deer","38","25"},{"06","Pronghorn","38","25"},{"07","Javalina","24","18"},{"08","Turkey","18","24"},{"09","Raccoon","12","18"},{"10","Rabbit","18","12"},{"11","Woodchuck","12","18"}};
        for(String[] a:authentic)paper("authentic-"+a[0],"AA"+a[0]+" · "+a[1],"Authentic Animals","AA"+a[0],"authentic-animals.html",Double.parseDouble(a[2]),Double.parseDouble(a[3]),1,"Sheet-plane positions only; proprietary animal artwork and vital-area contours are not reproduced.");
        paper("tic-tac-toe-small","Tic-Tac-Toe · small","Game / novelty","TTTSM","game-and-novelty.html",10.75,17,9,"");
        paper("tic-tac-toe-large","Tic-Tac-Toe · large","Game / novelty","TTTLG","game-and-novelty.html",17,17,9,"");
        paper("dart-40","Dart 40","Game / novelty","Dart 40","game-and-novelty.html",18.75,18.75,1,"Sheet-plane measurement; dart sectors are not scoring rings.");
        paper("bow-poker","Bow Poker","Game / novelty","Bow Poker","game-and-novelty.html",27,25,54,"Sheet-plane measurement; card positions and game scoring are not digitized.");
        String[] ra={"Coyote","Raccoon","Javalina","Fox","Goose","Five-spot animals"};
        for(int i=1;i<=6;i++)paper("ra-"+i,"RA0"+i+" · B&W "+ra[i-1],"Game / novelty","RA0"+i,"game-and-novelty.html",17,17,i==6?5:1,"The catalog does not publish the overlaid ring diameters. Use untrimmed paper corners, not guessed rings.");
    }
    private static String url(String path){return path.startsWith("https://")?path:BASE+path;}
    private static void add(TargetPreset p){if(BY_ID.put(p.id,p)!=null)throw new IllegalStateException("Duplicate preset "+p.id);ITEMS.add(p);}
    private static TargetPreset circle(String id,String name,String category,String family,String sku,String page,double mm,int spots,double inner,String hint,double[] rings,double[] fills,int[] colors,String note){
        return new TargetPreset("mlp-"+id,name,category,family,sku,url(page),note,mm,mm,mm,spots,inner,hint,rings,fills,colors);
    }
    private static void field(boolean hunter,int cm){String code=hunter?"NFH":"NFF",id=(hunter?"nfh-":"nff-")+cm,family=hunter?"Hunter":"Field";
        add(circle(id,"NFAA "+family+" · "+cm+" cm"+(cm==20?" four-up":""),"NFAA Field / Hunter",family,code+(cm==20?"-4-20":"-"+cm)+" / "+code+"WP"+(cm==20?"-4-20":"-"+cm),hunter?"nfaa-hunter.html":"nfaa-field.html",cm*10,cm==20?4:1,hunter?.2:.6,hunter?"Outer edge of the WHITE bull (20% of the circle radius).":"OUTER edge of the wide WHITE band (60% of the circle radius), not a thin scoring line.",hunter?new double[]{.1,.2,.6,1}:new double[]{.1,.2,.4,.6,.8,1},hunter?new double[]{1,.2}:new double[]{1,.6,.2},hunter?new int[]{TargetPreset.BLACK,TargetPreset.WHITE}:new int[]{TargetPreset.BLACK,TargetPreset.WHITE,TargetPreset.BLACK},(!hunter&&cm==35?"Matches today's NFF-35 photos. Black center, white band, black outer band. ":"")+(cm==20?"Four circles on a sheet: calibrate one, not the whole sheet.":"")));
    }
    private static void wa(String id,String name,String category,String sku,String page,double nominalCm,double outer,int spots,String variant,String note){
        List<Double> boundaries=new ArrayList<>();if(!variant.equals("recurve"))boundaries.add(.05/outer);
        for(int i=1;i<=Math.round(outer*10);i++)if(!(i==1&&variant.equals("compound")))boundaries.add(i*.1/outer);
        double[] rings=new double[boundaries.size()];for(int i=0;i<rings.length;i++)rings[i]=Math.min(1,boundaries.get(i));
        List<Double> radii=new ArrayList<>();List<Integer> colors=new ArrayList<>();
        double[] edges={1,.8,.6,.4,.2};int[] palette={TargetPreset.WHITE,TargetPreset.BLACK,TargetPreset.BLUE,TargetPreset.RED,TargetPreset.GOLD};
        for(int i=0;i<edges.length;i++)if(edges[i]<outer+1e-8||radii.isEmpty()&& (i==edges.length-1||edges[i+1]<outer-1e-8)){radii.add(Math.min(outer,edges[i])/outer);colors.add(palette[i]);}
        // The outer visible region may be a partial color zone (e.g. a five-ring face).
        if(radii.isEmpty()||radii.get(0)!=1)throw new IllegalStateException("Missing WA outer color");
        double[] fills=new double[radii.size()];int[] fillColors=new int[colors.size()];for(int i=0;i<fills.length;i++){fills[i]=radii.get(i);fillColors[i]=colors.get(i);}
        double calibration=outer>.8?.6:outer>.4?.4:.2;
        String hint=calibration==.6?"OUTER edge of the BLUE area (blue / black boundary).":calibration==.4?"OUTER edge of the RED area (red / blue boundary).":"OUTER edge of the YELLOW area (yellow / red boundary).";
        add(circle(id,name,category,"WA",sku,page,nominalCm*10*outer,spots,calibration/outer,hint,rings,fills,fillColors,note));
    }
    private static void blue(String id,String name,String sku,double mm,int spots,String page,String note){boolean five=spots==5;
        add(circle(id,name,page.equals("reduced.html")?"Reduced":"NFAA Indoor","Indoor",sku,page,mm,spots,five?.5:.2,"OUTER edge of the WHITE bull.",five?new double[]{.25,.5,.75,1}:new double[]{.1,.2,.4,.6,.8,1},new double[]{1,five?.5:.2},new int[]{TargetPreset.NAVY,TargetPreset.WHITE},note));
    }
    private static void hitMiss(String id,String name,double diameter,double bull,String sku){add(circle("hit-miss-"+id,name,"Hit / miss","Hit/Miss",sku,WA_FLYER,diameter,1,bull/diameter,"OUTER edge of the YELLOW hit area.",new double[]{bull/diameter,1},new double[]{1,bull/diameter},new int[]{TargetPreset.RED,TargetPreset.GOLD},"Positions only; no automatic hit/miss scoring."));}
    private static void paper(String id,String name,String category,String sku,String page,double widthIn,double heightIn,int spots,String note){add(new TargetPreset("mlp-"+id,name,category,"Paper",sku,url(page),note,0,widthIn*25.4,heightIn*25.4,spots,0,"",new double[0],new double[0],new int[0]));}
    public static List<TargetPreset> all(){return Collections.unmodifiableList(ITEMS);}
    public static TargetPreset find(String id){return BY_ID.get(id);}
    public static TargetPreset defaultPreset(){return BY_ID.get(DEFAULT_ID);}
    public static TargetPreset legacy(String type,int cm){
        TargetPreset p=BY_ID.get("mlp-"+(type.equals("Hunter")?"nfh-":"nff-")+cm);if(p!=null)return p;
        TargetPreset base=BY_ID.get(type.equals("Hunter")?"mlp-nfh-35":DEFAULT_ID);
        return new TargetPreset("legacy-"+type+"-"+cm,type+" · "+cm+" cm","Legacy",base.family,"",base.source,"Preserved legacy size",cm*10,cm*10,cm*10,1,base.innerRatio,base.innerHint,base.rings,base.fillRadii,base.fillColors);
    }
    public static List<TargetPreset> search(String query){String[] terms=query.toLowerCase(Locale.ROOT).trim().split("\\s+");List<TargetPreset> out=new ArrayList<>();for(TargetPreset p:ITEMS){String hay=(p.name+" "+p.category+" "+p.sku+" "+p.dimensions()+" "+p.note).toLowerCase(Locale.ROOT);boolean match=true;for(String term:terms)if(!hay.contains(term)){match=false;break;}if(match)out.add(p);}return out;}
}
