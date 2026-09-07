package com.onedayonemasterpiece.repeatit.core;

import java.time.*;
import java.util.*;

/** Dependency-free executable regression suite: javac + java -ea. No private fixtures. */
public final class CoreChecks {
    private static int checks;
    private static void check(boolean result,String name){checks++;if(!result)throw new AssertionError(name);}
    private static void rejects(Runnable action,String name){boolean failed=false;try{action.run();}catch(RuntimeException e){failed=true;}check(failed,name);}
    private static final Instant NOW=Instant.parse("2026-09-07T08:00:00Z");
    private static final Engine.Window W=new Engine.Window(LocalTime.of(7,40),LocalTime.of(23,0),ZoneOffset.UTC);
    private static Engine.Card card(int i){Engine.Card c=new Engine.Card();c.deck="synthetic";c.id="card-"+i;c.title="Synthetic title";c.text="Synthetic public test only";return c;}
    private static Engine.Plan plan(int days){Engine.Plan p=new Engine.Plan();p.deck="synthetic";p.deadline=NOW.plus(Duration.ofDays(days));return p;}
    private static List<Engine.Card> cards(int n){List<Engine.Card>x=new ArrayList<>();for(int i=0;i<n;i++)x.add(card(i));return x;}
    private static Engine.Decision decision(int n,int days){return Engine.next(cards(n),List.of(plan(days)),Map.of(),NOW,W,List.of());}
    private static Map<String,Object> document(){Map<String,Object> c=new LinkedHashMap<>();c.put("schema_version",3);c.put("deck_id","synthetic");c.put("card_id","card-0");c.put("revision",1);c.put("meaning_revision",1);c.put("mode","exposure");c.put("title","Synthetic title");c.put("text","Synthetic public test only");c.put("source_refs",List.of(Map.of("url","https://example.invalid/source")));return c;}
    public static void main(String[] args){
        check(decision(10,2).spacingMillis<decision(10,30).spacingMillis,"closer deadline denser");
        check(decision(100,2).spacingMillis<decision(10,2).spacingMillis,"larger corpus denser");
        check(decision(1,365).spacingMillis>86400000L,"no hourly or daily minimum frequency");
        check(decision(500,1).spacingMillis<60000,"no hourly cooldown across cards");
        check(!W.allowed(Instant.parse("2026-09-07T07:39:59Z")),"07:39:59 blocked");
        check(W.allowed(Instant.parse("2026-09-07T07:40:00Z")),"07:40 allowed");
        check(W.allowed(Instant.parse("2026-09-07T22:59:59Z")),"22:59 allowed");
        check(!W.allowed(Instant.parse("2026-09-07T23:00:00Z")),"23:00 blocked");
        check(W.next(Instant.parse("2026-09-07T23:00:00Z")).equals(Instant.parse("2026-09-08T07:40:00Z")),"quiet gate next morning");
        check(W.add(Instant.parse("2026-09-07T22:59:00Z"),120000).equals(Instant.parse("2026-09-08T07:41:00Z")),"active time skips night");
        Engine.Window local=new Engine.Window(LocalTime.of(7,40),LocalTime.of(23,0),ZoneId.of("America/New_York"));
        check(local.next(Instant.parse("2026-11-01T06:00:00Z")).equals(Instant.parse("2026-11-01T12:40:00Z")),"DST uses device zone");
        Engine.Plan p=plan(2);Engine.State s=new Engine.State();
        Engine.State remember=Engine.answer(s,p,"remember",NOW,NOW.plusSeconds(10),W);
        Engine.State repeat=Engine.answer(s,p,"repeat",NOW,NOW.plusSeconds(10),W);
        check(repeat.eligible.isBefore(remember.eligible),"repeat returns earlier");
        check(remember.contacts==1 && remember.weakDebt==0,"remember does not finish minimum");
        check(repeat.contacts==1 && repeat.weakDebt==1,"repeat adds work");
        Engine.State rapid=Engine.answer(remember,p,"remember",NOW.plusSeconds(11),NOW.plusSeconds(12),W);
        check(rapid.contacts==1,"rapid taps cannot credit spacing");
        Engine.Card near=card(0),far=card(1);far.deck="far";Engine.Plan farP=plan(365);farP.deck="far";
        Engine.Decision mixed=Engine.next(List.of(near,far),List.of(p,farP),Map.of(),NOW,W,List.of());
        check(mixed.cardKey.equals(near.key())&&mixed.spacingMillis<=decision(1,2).spacingMillis,"far deadline cannot dilute near");
        check(mixed.loads.size()==2 && mixed.loads.get(0).cumulative==5 && mixed.loads.get(1).cumulative==10,"prefix deadline demand");
        check(Engine.next(cards(2),List.of(),Map.of(),NOW,W,List.of()).due==null,"no invented deadline");
        check(Engine.next(cards(2),List.of(p),Map.of(),p.deadline.plusSeconds(1),W,List.of()).expired==10,"expired workload explicit");
        Engine.Decision delayed=Engine.next(cards(10),List.of(p),Map.of(),NOW.plusSeconds(3600),W,List.of());
        check(delayed.spacingMillis<decision(10,2).spacingMillis,"missed time recalculates future density");
        check(decision(10,2).feasibility.startsWith("preliminary"),"no invented human capacity");
        check(Engine.next(cards(100),List.of(p),Map.of(),NOW,W,List.of(99999999L,99999999L,99999999L,99999999L,99999999L)).feasibility.startsWith("risk_from"),"empirical risk labelled separately");
        Map<String,Object> doc=document();check(Contract.deck(doc).cards.size()==1,"canonical card imports");
        doc.put("revision","2");check(Contract.deck(doc).cards.get(0).revision==2,"numeric-string safe recovery");
        doc.put("lockscreen_preview","true");check(!Contract.deck(doc).cards.get(0).preview,"string true never privacy consent");
        doc.put("image",Map.of("path","https://evil.invalid/file"));check(Contract.deck(doc).cards.get(0).image.isEmpty(),"bad image preserves text");
        doc.put("schema_version",999);rejects(()->Contract.deck(doc),"future major not guessed");
        rejects(()->Contract.readPath("learning/../private"),"traversal rejected");
        rejects(()->Contract.readPath("learning/%2e%2e/private"),"encoded traversal rejected");
        rejects(()->Contract.writePath("device-a","learning/progress/device-b/x.jsonl"),"write cannot cross device");
        rejects(()->Contract.writePath("device-a","learning/decks/x.json"),"write cannot mutate knowledge");
        Map<String,Object> bad=document();bad.remove("text");Map<String,Object> legacy=new LinkedHashMap<>();legacy.put("schema_version",2);legacy.put("deck_id","synthetic");legacy.put("cards",List.of(document(),bad));
        Contract.Import imported=Contract.deck(legacy);check(imported.cards.size()==1&&imported.issues.size()==1,"bad sibling isolated");
        legacy.put("cards",List.of(document(),document()));check(Contract.deck(legacy).cards.isEmpty(),"duplicate identity quarantined not last wins");
        List<Engine.Card> all=cards(40);Map<String,Engine.State> history=new HashMap<>();Instant now=NOW;int answered=0;
        for(int iteration=0;iteration<300;iteration++) {
            Engine.Decision d=Engine.next(all,List.of(p),history,now,W,List.of());
            if(d.due==null)break;
            Engine.Card selected=null;for(Engine.Card c:all)if(c.key().equals(d.cardKey))selected=c;
            check(selected!=null,"scheduled identity exists");check(W.allowed(d.due),"simulation stays within window");
            Engine.State old=history.getOrDefault(selected.learningKey(),new Engine.State());
            now=d.due.plusSeconds(1);history.put(selected.learningKey(),Engine.answer(old,p,"remember",d.due,now,W));answered++;
        }
        check(answered==200,"forty cards receive all five spaced contacts by deadline");
        for(Engine.Card c:all)check(history.get(c.learningKey()).contacts==5,"no starvation");
        System.out.println("PASS "+checks+" assertions; synthetic fixtures only");
    }
}
