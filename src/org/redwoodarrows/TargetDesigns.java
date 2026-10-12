package org.redwoodarrows;

import java.text.Normalizer;
import java.util.*;

/** Presentation-only grouping. Measurement IDs, dimensions and saved groups stay unchanged. */
public final class TargetDesigns {
    public static final class Design {
        public final String id,name,kind,summary,aliases;
        public final List<TargetPreset> variants;
        private Design(String id,String name,String kind,String summary,String aliases,List<TargetPreset> variants){
            this.id=id;this.name=name;this.kind=kind;this.summary=summary;this.aliases=aliases;
            this.variants=Collections.unmodifiableList(new ArrayList<>(variants));
        }
        public boolean rings(){return kind.equals("Rings");}
    }
    public static final class Match {
        public final Design design;
        public final List<TargetPreset> variants;
        Match(Design design,List<TargetPreset> variants){this.design=design;this.variants=Collections.unmodifiableList(new ArrayList<>(variants));}
        public TargetPreset preview(String selectedId){for(TargetPreset p:variants)if(p.id.equals(selectedId))return p;return variants.get(0);}
    }
    private static final List<Design> ALL=new ArrayList<>();
    private static final Map<String,Design> BY_PRESET=new LinkedHashMap<>();
    static {
        add("field","NFAA Field","Rings","Black · white · black","black white black bullseye three bands 3 bands three ring 3 ring",
            "nff-35 nff-20 nff-50 nff-65");
        add("hunter","NFAA Hunter","Rings","Black with a white bull","black white bullseye",
            "nfh-35 nfh-20 nfh-50 nfh-65");
        add("wa","World Archery","Rings","Full-color single face","world archery fita colored colourful color multicolor yellow gold red blue black white bullseye",
            "wa-40 wa-60 wa-80 wa-122 classic-92 nasp-80 reduced-single");
        add("wa-triple","World Archery triple","Rings","Triangle or vertical spots","world archery fita colored color multicolor three spot 3 spot yellow gold red blue",
            "triple-40-triangular-combined triple-40-vertical-combined triple-40-triangular-compound triple-40-vertical-compound triple-40-triangular-recurve triple-40-vertical-recurve triple-60-vertical reduced-triple-triangular reduced-triple-vertical");
        add("vegas","Vegas","Rings","Gray-backed color spots","grey gray colored color multicolor yellow gold red blue",
            "vegas-triple vegas-dual-single vegas-dual-triple vegas-five");
        add("indoor","NFAA Indoor","Rings","Blue with a white bull","nfaa indoor blue white bullseye",
            "indoor-single indoor-five indoor-dual-single indoor-dual-five indoor-five-vertical reduced-indoor-single reduced-indoor-five");
        add("wa-field","World Archery Field","Rings","Black with a yellow bull","world archery fita black yellow gold bullseye",
            "fita-field-40 fita-field-20 fita-field-60 fita-field-80 fita-field-20-single");
        add("centers","Center spots & patch","Rings","Inner colored rings only","fita colored color yellow gold red blue",
            "center-80 center-80-six center-92 center-122 nasp-center-80 patch-122");
        add("hit-miss","Hit / Miss","Rings","Red with a yellow bull","hit miss yellow gold red bullseye",
            "hit-miss-academic hit-miss-compound");
        add("proam","Team Pro · ProAm","Rings","Black-and-white triple","black white black three spot 3 spot triangle triangular",
            "proam");
        // These illustrations really are different designs; don't collapse a bear
        // and a deer into an indistinguishable generic animal thumbnail.
        for(TargetPreset p:TargetCatalog.all())if(p.id.startsWith("mlp-animal-")||p.id.startsWith("mlp-authentic-")||p.id.startsWith("mlp-ra-")){
            String name=p.name.substring(p.name.indexOf(" · ")+3);
            String aliases=name.contains("Javalina")?"javelina peccary":name.contains("Grizzly")?"bear":"";
            add(p.id.substring(4),name,"Animals",p.category+" · "+p.sku,aliases,p.id.substring(4));
        }
        add("tic-tac-toe","Tic-Tac-Toe","Games","Nine colored spots","noughts crosses game novelty","tic-tac-toe-small tic-tac-toe-large");
        add("dart","Dart 40","Games","Dartboard face","darts dart board game novelty","dart-40");
        add("poker","Bow Poker","Games","Playing-card sheet","cards game novelty","bow-poker");
        if(BY_PRESET.size()!=TargetCatalog.all().size())throw new IllegalStateException("Ungrouped target presets");
    }
    private static void add(String id,String name,String kind,String summary,String aliases,String ids){
        List<TargetPreset> variants=new ArrayList<>();
        for(String suffix:ids.split(" ")){TargetPreset p=TargetCatalog.find("mlp-"+suffix);if(p==null)throw new IllegalStateException("Unknown preset "+suffix);variants.add(p);}
        Design design=new Design(id,name,kind,summary,aliases,variants);
        for(TargetPreset p:variants)if(BY_PRESET.put(p.id,design)!=null)throw new IllegalStateException("Preset grouped twice "+p.id);
        ALL.add(design);
    }
    public static List<Design> all(){return Collections.unmodifiableList(ALL);}
    public static Design forPreset(String id){return BY_PRESET.get(id);}
    public static String asset(TargetPreset p){return "target-previews/"+p.id+(p.id.equals("mlp-proam")||p.id.equals("mlp-fita-field-20-single")?".png":".jpg");}
    public static String variantLabel(TargetPreset p){
        Design d=forPreset(p.id);String name=p.name;
        if(d!=null&&name.startsWith(d.name+" · "))name=name.substring(d.name.length()+3);
        return name;
    }
    private static String normalize(String text){return Normalizer.normalize(text,Normalizer.Form.NFKD).toLowerCase(Locale.ROOT).replace("colour","color").replace("triangle","triangular").replaceAll("\\p{M}","").replaceAll("[^a-z0-9]+"," ").trim();}
    private static boolean matches(Design d,TargetPreset p,String query){
        // Family summaries/categories may mention several alternatives (e.g.
        // triangle OR vertical, center spots AND patch, NFAA / NASP). They must
        // not make a different size/layout satisfy a specific variant query.
        if(normalize(query).equals(normalize(d.name)))return true;
        String hay=normalize(d.aliases+" "+p.name+" "+p.sku+" "+p.dimensions()+" "+p.spots+" spot");
        String compact=hay.replace(" ","");
        for(String term:normalize(query).split(" +"))if(!hay.contains(term)&&!compact.contains(term))return false;
        return true;
    }
    /** Empty query browses the requested section; any query searches the entire catalog. */
    public static List<Match> search(String query,boolean otherDesigns){
        boolean searching=!normalize(query).isEmpty();List<Match> result=new ArrayList<>();
        for(Design d:ALL){
            if(!searching&&d.rings()==otherDesigns)continue;
            List<TargetPreset> variants=new ArrayList<>();for(TargetPreset p:d.variants)if(!searching||matches(d,p,query))variants.add(p);
            if(!variants.isEmpty())result.add(new Match(d,variants));
        }
        return result;
    }
}
