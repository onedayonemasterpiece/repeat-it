package com.onedayonemasterpiece.repeatit.core;

import java.util.*;

/** Small schema regression for editorial card variants. */
public final class PresentationChecks {
    private static int checks;
    private static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    private static Map<String,Object> base(){
        Map<String,Object> c=new LinkedHashMap<>();c.put("card_id","presentation-card");c.put("revision",1);c.put("meaning_revision",1);c.put("status","active");c.put("mode","exposure");c.put("title","Presentation");c.put("text","Main fact");c.put("source_refs",List.of(Map.of("url","https://example.invalid/source")));return c;
    }
    private static Contract.Import load(Map<String,Object> card){Map<String,Object> d=new LinkedHashMap<>();d.put("schema_version",3);d.put("deck_id","presentation");d.put("revision",1);d.put("title","Presentation deck");d.put("language","en");d.put("cards",List.of(card));return Contract.deck(d);}
    public static void main(String[] args){
        Map<String,Object> thesis=base();thesis.put("detail","One or two useful explanatory sentences.");Contract.Import a=load(thesis);check(a.cards.size()==1,"thesis detail accepted");check("One or two useful explanatory sentences.".equals(a.cards.get(0).source.get("detail")),"detail preserved for renderer");

        Map<String,Object> metric=base();metric.put("presentation","metric");metric.put("metric","146 млн");Contract.Import b=load(metric);check(b.cards.size()==1,"metric accepted");check("146 млн".equals(b.cards.get(0).source.get("metric")),"metric value preserved");

        Map<String,Object> missingMetric=base();missingMetric.put("presentation","metric");Contract.Import c=load(missingMetric);check(c.cards.isEmpty()&&!c.issues.isEmpty(),"metric value required");

        Map<String,Object> missingImage=base();missingImage.put("presentation","image");Contract.Import d=load(missingImage);check(d.cards.isEmpty()&&!d.issues.isEmpty(),"image-first requires image object");

        Map<String,Object> image=base();image.put("presentation","image");image.put("image",Map.of("path","learning/assets/example.webp","alt","Diagram to remember","caption","Short image caption","sha256","0000000000000000000000000000000000000000000000000000000000000000","provenance",Map.of("url","https://example.invalid/image")));Contract.Import e=load(image);check(e.cards.size()==1&&!e.cards.get(0).image.isEmpty(),"image-first accepted with validated image");

        Map<String,Object> invalid=base();invalid.put("presentation","poster");Contract.Import f=load(invalid);check(f.cards.isEmpty()&&!f.issues.isEmpty(),"unknown presentation rejected");
        System.out.println("PASS "+checks+" presentation assertions");
    }
}
