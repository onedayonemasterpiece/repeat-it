package com.onedayonemasterpiece.repeatit.core;

import java.time.*;
import java.util.*;

/** Dependency-free executable regression suite: javac + java -ea. No private fixtures. */
public final class CoreChecks {
    private static int checks;
    private static void check(boolean result,String name){checks++;if(!result)throw new AssertionError(name);}
    private static void rejects(Runnable action,String name){boolean failed=false;try{action.run();}catch(RuntimeException e){failed=true;}check(failed,name);}
    private static final Instant NOW=Instant.parse("2026-09-07T08:00:00Z");
    private static final Engine.Window W=new Engine.Window(LocalTime.of(7,40),LocalTime.of(0,30),ZoneOffset.UTC);
    private static Engine.Card card(int i){Engine.Card c=new Engine.Card();c.deck="synthetic";c.id="card-"+i;c.title="Synthetic title";c.text="Synthetic public test only";return c;}
    private static Engine.Plan plan(int days){Engine.Plan p=new Engine.Plan();p.deck="synthetic";p.deadline=NOW.plus(Duration.ofDays(days));return p;}
    private static Engine.Plan undatedPlan(){Engine.Plan p=new Engine.Plan();p.deck="synthetic";p.deadline=null;return p;}
    private static List<Engine.Card> cards(int n){List<Engine.Card>x=new ArrayList<>();for(int i=0;i<n;i++)x.add(card(i));return x;}
    private static Engine.Decision decision(int n,int days){return Engine.next(cards(n),List.of(plan(days)),Map.of(),NOW,W,List.of());}
    private static Map<String,Object> cardDoc(){Map<String,Object> c=new LinkedHashMap<>();c.put("card_id","card-0");c.put("revision",1);c.put("meaning_revision",1);c.put("mode","exposure");c.put("status","active");c.put("title","Synthetic title");c.put("text","Synthetic public test only");c.put("source_refs",List.of(Map.of("url","https://example.invalid/source")));return c;}
    private static Map<String,Object> document(){Map<String,Object> d=new LinkedHashMap<>();d.put("schema_version",3);d.put("deck_id","synthetic");d.put("revision",1);d.put("title","Synthetic deck");d.put("language","en");d.put("cards",List.of(cardDoc()));return d;}
    public static void main(String[] args){
        check(decision(10,2).spacingMillis<decision(10,30).spacingMillis,"closer deadline denser");
        check(decision(100,2).spacingMillis<decision(10,2).spacingMillis,"larger corpus denser");
        check(decision(1,365).spacingMillis>86400000L,"no hourly or daily minimum frequency");
        check(decision(500,1).spacingMillis<60000,"urgent corpus may require sub-minute calculated slots before human pacing floor");

        check(!W.allowed(Instant.parse("2026-09-07T07:39:59Z")),"07:39:59 blocked");
        check(W.allowed(Instant.parse("2026-09-07T07:40:00Z")),"07:40 allowed");
        check(W.allowed(Instant.parse("2026-09-07T23:59:59Z")),"23:59 allowed in overnight window");
        check(W.allowed(Instant.parse("2026-09-08T00:29:59Z")),"00:29 allowed");
        check(!W.allowed(Instant.parse("2026-09-08T00:30:00Z")),"00:30 blocks new presentation");
        check(W.next(Instant.parse("2026-09-08T00:30:00Z")).equals(Instant.parse("2026-09-08T07:40:00Z")),"00:30 defers new work to same morning");
        check(W.close(Instant.parse("2026-09-07T23:59:00Z")).equals(Instant.parse("2026-09-08T00:30:00Z")),"overnight window closes next calendar day");
        check(W.add(Instant.parse("2026-09-08T00:29:00Z"),120000).equals(Instant.parse("2026-09-08T07:41:00Z")),"active time skips blocked night");
        check(W.available(Instant.parse("2026-09-07T23:30:00Z"),Instant.parse("2026-09-08T08:00:00Z"))==Duration.ofMinutes(80).toMillis(),"available time spans midnight without double count");
        Engine.Window local=new Engine.Window(LocalTime.of(7,40),LocalTime.of(0,30),ZoneId.of("America/New_York"));
        check(local.next(Instant.parse("2026-11-01T06:00:00Z")).equals(Instant.parse("2026-11-01T12:40:00Z")),"DST uses device zone");

        Engine.Plan p=plan(2);Engine.State s=new Engine.State();
        Engine.State remember=Engine.answer(s,p,"remember",NOW,NOW.plusSeconds(10),W);Engine.State repeat=Engine.answer(s,p,"repeat",NOW,NOW.plusSeconds(10),W);
        check(repeat.eligible.isBefore(remember.eligible),"repeat eligibility is earlier");
        check(remember.contacts==1&&remember.weakDebt==0,"remember advances successful mastery count");
        check(repeat.contacts==0&&repeat.weakDebt==1,"repeat never advances mastery count");
        Engine.State rapid=Engine.answer(remember,p,"remember",NOW.plusSeconds(11),NOW.plusSeconds(12),W);check(rapid.contacts==1,"rapid taps cannot credit spacing");
        Engine.Decision afterRepeat=Engine.next(cards(1),List.of(p),Map.of(card(0).learningKey(),repeat),NOW.plusSeconds(11),W,List.of());
        Engine.Decision afterRemember=Engine.next(cards(1),List.of(p),Map.of(card(0).learningKey(),remember),NOW.plusSeconds(11),W,List.of());
        check(afterRepeat.remaining==5,"repeat does not reduce required successful remembers");check(afterRemember.remaining==4,"remember reduces required successful remembers by one");check(afterRepeat.due.isBefore(afterRemember.due),"repeat actually resurfaces deadline card earlier");

        Engine.Card near=card(0),far=card(1);far.deck="far";Engine.Plan farP=plan(365);farP.deck="far";Engine.Decision mixed=Engine.next(List.of(near,far),List.of(p,farP),Map.of(),NOW,W,List.of());
        check(mixed.cardKey.equals(near.key())&&mixed.spacingMillis<=decision(1,2).spacingMillis,"far deadline cannot dilute near");
        check(mixed.loads.size()==2&&mixed.loads.get(0).cumulative==5&&mixed.loads.get(1).cumulative==10,"prefix deadline demand");
        check(Engine.next(cards(2),List.of(),Map.of(),NOW,W,List.of()).due==null,"cards without active plan stay excluded");
        Engine.Decision overdue=Engine.next(cards(2),List.of(p),Map.of(),p.deadline.plusSeconds(1),W,List.of());
        check(overdue.expired==10&&overdue.remaining==10&&overdue.due!=null,"missed deadline reports overdue work but does not delete it");
        check(overdue.feasibility.startsWith("deadline_missed"),"missed deadline is explicit");
        Engine.State afterLateAnswer=Engine.answer(new Engine.State(),p,"remember",p.deadline.minusSeconds(10),p.deadline.plus(Duration.ofHours(2)),W);
        check(afterLateAnswer.contacts==1&&afterLateAnswer.eligible.isAfter(p.deadline),"response shown before deadline remains valid when answered later");
        Engine.Decision delayed=Engine.next(cards(10),List.of(p),Map.of(),NOW.plusSeconds(3600),W,List.of());check(delayed.spacingMillis<decision(10,2).spacingMillis,"missed time recalculates future density");
        check(decision(10,2).feasibility.startsWith("preliminary"),"no invented human capacity");
        check(Engine.next(cards(100),List.of(p),Map.of(),NOW,W,List.of(99999999L,99999999L,99999999L,99999999L,99999999L)).feasibility.startsWith("risk_from"),"empirical risk labelled separately");

        Engine.Plan undated=undatedPlan();Engine.Card timelessCard=card(0);Engine.Decision firstUndated=Engine.next(List.of(timelessCard),List.of(undated),Map.of(),NOW,W,List.of());
        check(firstUndated.due!=null&&firstUndated.remaining==5&&firstUndated.expired==0,"active undated plan schedules finite learning");
        Engine.State timelessState=new Engine.State();Instant timelessNow=firstUndated.due;
        for(int success=1;success<=5;success++){
            timelessState=Engine.answer(timelessState,undated,"remember",timelessNow,timelessNow.plusSeconds(1),W);check(timelessState.contacts==success,"undated remember increments success exactly once");
            Engine.Decision next=Engine.next(List.of(timelessCard),List.of(undated),Map.of(timelessCard.learningKey(),timelessState),timelessNow.plusSeconds(1),W,List.of());
            if(success<5){check(next.due!=null&&next.remaining==5-success,"undated card remains until required successes");timelessNow=next.due;}else check(next.due==null&&next.remaining==0,"undated card retires after fifth successful remember");
        }
        Engine.State undatedRepeat=Engine.answer(new Engine.State(),undated,"repeat",NOW,NOW.plusSeconds(1),W);check(undatedRepeat.contacts==0&&undatedRepeat.eligible.isAfter(NOW),"undated repeat returns sooner without success credit");

        Engine.Card deckA=card(0),deckB=card(1),deckC=card(2);deckA.deck="a";deckB.deck="b";deckC.deck="c";Engine.Plan a=undatedPlan(),b=undatedPlan(),c=undatedPlan();a.deck="a";b.deck="b";c.deck="c";
        Engine.Decision oneOfMany=Engine.next(List.of(deckA,deckB,deckC),List.of(a,b,c),Map.of(),NOW,W,List.of());check(oneOfMany.cardKey!=null&&oneOfMany.remaining==15,"many decks still produce exactly one next identity");
        Engine.Card oldCard=card(10),salvageable=card(11);oldCard.deck="old";salvageable.deck="salvage";Engine.Plan oldPlan=new Engine.Plan(),salvagePlan=new Engine.Plan();oldPlan.deck="old";oldPlan.deadline=NOW.minus(Duration.ofHours(1));salvagePlan.deck="salvage";salvagePlan.deadline=NOW.plus(Duration.ofMinutes(10));
        Engine.Decision protectFuture=Engine.next(List.of(oldCard,salvageable),List.of(oldPlan,salvagePlan),Map.of(),NOW,W,List.of());check(protectFuture.cardKey.equals(salvageable.key()),"salvageable deadline inside human gap beats already-missed backlog");
        salvagePlan.deadline=NOW.plus(Duration.ofDays(1));Engine.Decision fillSlack=Engine.next(List.of(oldCard,salvageable),List.of(oldPlan,salvagePlan),Map.of(),NOW,W,List.of());check(fillSlack.cardKey.equals(oldCard.key()),"overdue work fills slack when future deadline is not imminent");

        Map<String,Object> doc=document();check(Contract.deck(doc).cards.size()==1,"canonical deck imports");Map<String,Object> empty=document();empty.put("cards",List.of());rejects(()->Contract.deck(empty),"empty logical deck rejected");
        ((Map<String,Object>)((List<?>)doc.get("cards")).get(0)).put("revision","2");check(Contract.deck(doc).cards.get(0).revision==2,"numeric-string safe recovery");
        ((Map<String,Object>)((List<?>)doc.get("cards")).get(0)).put("lockscreen_preview","true");check(!Contract.deck(doc).cards.get(0).preview,"string true never privacy consent");
        ((Map<String,Object>)((List<?>)doc.get("cards")).get(0)).put("image",Map.of("path","https://evil.invalid/file"));check(Contract.deck(doc).cards.get(0).image.isEmpty(),"bad image preserves text");doc.put("schema_version",999);rejects(()->Contract.deck(doc),"future major not guessed");
        rejects(()->Contract.readPath("learning/../private"),"traversal rejected");rejects(()->Contract.readPath("learning/%2e%2e/private"),"encoded traversal rejected");rejects(()->Contract.writePath("device-a","learning/progress/device-b/x.jsonl"),"write cannot cross device");rejects(()->Contract.writePath("device-a","learning/decks/x.json"),"write cannot mutate knowledge");
        Map<String,Object> bad=cardDoc();bad.remove("text");Map<String,Object> legacy=new LinkedHashMap<>();legacy.put("schema_version",2);legacy.put("deck_id","synthetic");legacy.put("cards",List.of(cardDoc(),bad));Contract.Import imported=Contract.deck(legacy);check(imported.cards.size()==1&&imported.issues.size()==1,"bad sibling isolated");legacy.put("cards",List.of(cardDoc(),cardDoc()));check(Contract.deck(legacy).cards.isEmpty(),"duplicate identity quarantined not last wins");
        Engine.Plan noWindowPlan=plan(2);noWindowPlan.deadline=Instant.parse("2026-09-08T07:30:00Z");Engine.Decision noWindow=Engine.next(cards(1),List.of(noWindowPlan),Map.of(),Instant.parse("2026-09-08T00:31:00Z"),W,List.of());
        check(noWindow.due==null&&noWindow.loads.get(0).requiredPerAllowedHour==null,"deadline before next presentation window is JSON-safe and unscheduled");
        Map<String,Object> planDoc=new LinkedHashMap<>();planDoc.put("deck_id","synthetic");planDoc.put("minimum_contacts",5);planDoc.put("active","true");planDoc.put("deadline","2026-09-09T20:00:00Z");rejects(()->Contract.plans(Map.of("schema_version",1,"plans",List.of(planDoc))),"corrupt active flag cannot silently enable plan");planDoc.put("active",true);planDoc.put("deadline",null);List<Engine.Plan> parsedUndated=Contract.plans(Map.of("schema_version",1,"plans",List.of(planDoc)));check(parsedUndated.size()==1&&parsedUndated.get(0).active&&parsedUndated.get(0).deadline==null,"active null deadline is valid finite plan");planDoc.put("active",false);check(Contract.plans(Map.of("schema_version",1,"plans",List.of(planDoc))).size()==1,"inactive null deadline is valid");
        Map<String,Object> noStatus=document();((Map<String,Object>)((List<?>)noStatus.get("cards")).get(0)).remove("status");Contract.Import noStatusImport=Contract.deck(noStatus);check(noStatusImport.cards.isEmpty()&&!noStatusImport.issues.isEmpty(),"missing v3 status quarantines card instead of activating it");

        List<Engine.Card> all=cards(40);Map<String,Engine.State> history=new HashMap<>();Instant now=NOW;int answered=0;
        for(int iteration=0;iteration<300;iteration++){Engine.Decision d=Engine.next(all,List.of(p),history,now,W,List.of());if(d.due==null)break;Engine.Card selected=null;for(Engine.Card card:all)if(card.key().equals(d.cardKey))selected=card;check(selected!=null,"scheduled identity exists");check(W.allowed(d.due),"simulation stays within new-presentation window");Engine.State old=history.getOrDefault(selected.learningKey(),new Engine.State());now=d.due.plusSeconds(1);history.put(selected.learningKey(),Engine.answer(old,p,"remember",d.due,now,W));answered++;}
        check(answered==200,"forty cards receive all five successful spaced remembers by deadline");for(Engine.Card card:all)check(history.get(card.learningKey()).contacts==5,"no starvation and finite completion");
        System.out.println("PASS "+checks+" assertions; synthetic fixtures only");
    }
}
