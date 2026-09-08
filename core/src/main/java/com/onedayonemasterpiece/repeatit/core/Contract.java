package com.onedayonemasterpiece.repeatit.core;

import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Pure validation layer. Runtime accepts harmless numeric strings; never invents IDs, dates or consent. */
public final class Contract {
    private Contract() {}
    public static final int MAX_DOCUMENT_BYTES=1024*1024, MAX_CARDS=20000;
    private static final Pattern ID=Pattern.compile("[a-z0-9][a-z0-9_-]{0,95}");
    public static String id(Object value) {
        String s=text(value,96);
        if(!ID.matcher(s).matches())throw new IllegalArgumentException("invalid_id");return s;
    }
    public static String text(Object value,int limit) {
        if(!(value instanceof String))throw new IllegalArgumentException("expected_text");
        String s=((String)value).trim();
        if(s.isEmpty()||s.length()>limit||s.indexOf('\0')>=0)throw new IllegalArgumentException("text_bounds");
        return s;
    }
    public static int positive(Object value,int limit) {
        String s=String.valueOf(value);
        if(!s.matches("[0-9]{1,9}"))throw new IllegalArgumentException("expected_positive_integer");
        int n=Integer.parseInt(s);if(n<1||n>limit)throw new IllegalArgumentException("integer_bounds");return n;
    }
    public static String readPath(String path) {
        if(path==null||!path.startsWith("learning/")||path.contains("..")||path.contains("\\")||path.contains("%")||path.contains("?")||path.contains("#")||path.contains("//")||!path.matches("[a-zA-Z0-9_./-]+"))throw new IllegalArgumentException("unsafe_learning_path");
        return path;
    }
    public static String writePath(String device,String path) {
        readPath(path);id(device);
        if(!path.startsWith("learning/progress/"+device+"/"))throw new IllegalArgumentException("write_scope");
        return path;
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object value) {
        if(!(value instanceof Map))throw new IllegalArgumentException("expected_object");
        for(Object k:((Map<?,?>)value).keySet()) if(!(k instanceof String))throw new IllegalArgumentException("expected_string_key");
        return (Map<String,Object>)value;
    }
    public static final class Import {
        public final List<Engine.Card> cards=new ArrayList<>();
        public final List<String> issues=new ArrayList<>();
    }
    public static Import deck(Map<String,Object> root) {
        int version=positive(root.get("schema_version"),1000);
        if(version!=2 && version!=3)throw new IllegalArgumentException("unsupported_schema");
        String deck=id(root.get("deck_id"));
        Import out=new Import();
        Object list=root.get("cards");
        if(version==3) {
            if(!root.containsKey("revision")||!root.containsKey("title")||!root.containsKey("language"))
                throw new IllegalArgumentException("invalid_deck_metadata");
        }
        if(!(list instanceof List)||((List<?>)list).isEmpty()||((List<?>)list).size()>MAX_CARDS)throw new IllegalArgumentException("invalid_cards");
        Map<String,List<Engine.Card>> groups=new LinkedHashMap<>();
        int i=0;
        for(Object raw:(List<?>)list) {
            try {Engine.Card c=card(object(raw),deck,version);groups.computeIfAbsent(c.key(),k->new ArrayList<>()).add(c);}
            catch(RuntimeException e){out.issues.add("card["+i+"]:"+e.getMessage());}
            i++;
        }
        for(Map.Entry<String,List<Engine.Card>> e:groups.entrySet()) {
            if(e.getValue().size()==1)out.cards.add(e.getValue().get(0));
            else out.issues.add("duplicate_card_id");
        }
        return out;
    }
    private static Engine.Card card(Map<String,Object> m,String deck,int version) {
        Engine.Card c=new Engine.Card(); c.deck=deck;c.id=id(m.get("card_id"));
        if(!"exposure".equals(m.get("mode")))throw new IllegalArgumentException("not_exposure");
        c.revision=positive(m.get("revision"),1000000);
        // The legacy v2 revision was explicitly presentation_only, not a reset of meaning.
        c.meaning=version==2?1:positive(m.get("meaning_revision"),1000000);
        c.title=text(m.get("title"),512);c.text=text(m.get("text"),24000);
        if(m.containsKey("detail")&&m.get("detail")!=null)text(m.get("detail"),2048);
        String presentation=m.containsKey("presentation")?text(m.get("presentation"),32):"thesis";
        if(!Set.of("thesis","metric","image").contains(presentation))throw new IllegalArgumentException("invalid_presentation");
        if(presentation.equals("metric")){
            if(!m.containsKey("metric"))throw new IllegalArgumentException("metric_required");
            text(m.get("metric"),64);
        }else if(m.containsKey("metric")&&m.get("metric")!=null)throw new IllegalArgumentException("metric_without_metric_presentation");
        if(presentation.equals("image")&&m.get("image")==null)throw new IllegalArgumentException("image_required");
        if(version==3&&!m.containsKey("status"))throw new IllegalArgumentException("status_required");
        c.active=!"archived".equals(m.get("status"));
        if(m.containsKey("status")&&!Set.of("active","archived").contains(m.get("status")))throw new IllegalArgumentException("invalid_status");
        // Missing/invalid preview consent is private, never permissive coercion.
        c.preview=Boolean.TRUE.equals(m.get("lockscreen_preview"));
        Object sources=m.get("source_refs");
        if(!(sources instanceof List)||((List<?>)sources).isEmpty())throw new IllegalArgumentException("source_refs_required");
        for(Object r:(List<?>)sources) {
            Map<String,Object> ref=object(r);
            if(!ref.containsKey("path")&&!ref.containsKey("url"))throw new IllegalArgumentException("source_locator_required");
        }
        c.source=new LinkedHashMap<>(m); // preserve provenance and presentation metadata, never promote it
        if(m.get("image")!=null) {
            try {
                Map<String,Object> image=object(m.get("image"));
                c.image=readPath(text(image.get("path"),512));
                if(!c.image.startsWith("learning/assets/")||!c.image.matches(".*\\.(png|jpg|jpeg|webp)"))throw new IllegalArgumentException("image_path");
                c.alt=text(image.get("alt"),1024);c.caption=text(image.get("caption"),2048);
                c.imageHash=text(image.get("sha256"),64);
                if(!c.imageHash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("image_hash");
                object(image.get("provenance"));
            } catch(RuntimeException e) {c.image="";c.alt="Изображение недоступно; текст сохранён";c.caption="";c.imageHash="";}
        }
        return c;
    }
    public static List<Engine.Plan> plans(Map<String,Object> root) {
        if(positive(root.get("schema_version"),100)!=1)throw new IllegalArgumentException("unsupported_plan_schema");
        Object entries=root.get("plans");if(!(entries instanceof List))throw new IllegalArgumentException("plans_required");
        List<Engine.Plan> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Object raw:(List<?>)entries) {
            Map<String,Object> m=object(raw); Engine.Plan p=new Engine.Plan();p.deck=id(m.get("deck_id"));
            if(!seen.add(p.deck))throw new IllegalArgumentException("duplicate_plan");
            p.minimum=positive(m.get("minimum_contacts"),1000);if(p.minimum<5)throw new IllegalArgumentException("minimum_contacts_below_five");
            if(!(m.get("active") instanceof Boolean))throw new IllegalArgumentException("plan_active_must_be_boolean");
            if(!m.containsKey("deadline"))throw new IllegalArgumentException("deadline_required_explicit_null_allowed");
            p.active=Boolean.TRUE.equals(m.get("active"));
            // Active + null deadline is a normal finite learning plan: finish after minimum successful «Помню» responses.
            if(m.get("deadline")!=null) {
                p.deadline=OffsetDateTime.parse(text(m.get("deadline"),64)).toInstant();
                if(Duration.between(Instant.now(),p.deadline).abs().toDays()>36525)throw new IllegalArgumentException("deadline_bounds");
            }
            result.add(p);
        }
        return result;
    }
}
