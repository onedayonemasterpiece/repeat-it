package com.onedayonemasterpiece.repeatit.core;

import java.time.*;
import java.util.*;

/** Seven-day deterministic production-scheduler simulations. Synthetic data only. */
public final class SchedulerSimulationChecks {
    private static int checks;
    private static final ZoneId Z=ZoneOffset.ofHours(2);
    private static final Engine.Window W=new Engine.Window(LocalTime.of(7,40),LocalTime.of(0,30),Z);
    private static final Instant START=ZonedDateTime.of(2026,9,8,7,40,0,0,Z).toInstant();
    private static final Instant D1=ZonedDateTime.of(2026,9,9,23,59,0,0,Z).toInstant();
    private static final Instant D3=ZonedDateTime.of(2026,9,11,23,59,0,0,Z).toInstant();
    private static final Instant D5=ZonedDateTime.of(2026,9,13,23,59,0,0,Z).toInstant();
    private static final Instant END7=START.plus(Duration.ofDays(7));
    private static final long TEN=Duration.ofMinutes(10).toMillis();
    private static final long FIFTEEN=Duration.ofMinutes(15).toMillis();

    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static Engine.Card card(String deck,int i){Engine.Card c=new Engine.Card();c.deck=deck;c.id=String.format(Locale.ROOT,"c%02d",i);c.title="Synthetic";c.text="Synthetic public scheduler test";return c;}
    private static Engine.Plan plan(String deck,Instant deadline){Engine.Plan p=new Engine.Plan();p.deck=deck;p.deadline=deadline;p.minimum=5;return p;}
    private static List<Engine.Card> threeDeckCards(){List<Engine.Card> cards=new ArrayList<>();for(int i=0;i<10;i++){cards.add(card("A",i));cards.add(card("B",i));cards.add(card("C",i));}return cards;}
    private static List<Engine.Plan> threePlans(){return List.of(plan("A",D1),plan("B",D3),plan("C",D5));}
    private static Engine.Plan planFor(List<Engine.Plan> plans,String deck){for(Engine.Plan p:plans)if(p.deck.equals(deck))return p;throw new AssertionError("missing plan "+deck);}
    private static Engine.Card cardByKey(List<Engine.Card> cards,String key){for(Engine.Card c:cards)if(c.key().equals(key))return c;throw new AssertionError("missing card "+key);}
    private static long attentionGap(Engine.Decision d){double rate=0;for(Engine.Load l:d.loads)if(l.requiredPerAllowedHour!=null)rate=Math.max(rate,l.requiredPerAllowedHour);if(rate<=4.0)return FIFTEEN;long calculated=(long)Math.floor(3600000.0/rate);return Math.min(FIFTEEN,Math.max(TEN,calculated));}
    private static Instant later(Instant a,Instant b){return a.isAfter(b)?a:b;}

    private interface Behavior {
        String reaction(Engine.Card c,Engine.State s,int event);
        default long latency(Engine.Card c,Engine.State s,int event){return 20_000L;}
        default long presentationDelay(Engine.Card c,Engine.State s,int event,Instant due){return 0;}
    }

    private static final class BudgetBehavior implements Behavior {
        final Map<String,Integer> budget=new HashMap<>(),used=new HashMap<>(),usedStage=new HashMap<>();final boolean perStage;
        BudgetBehavior(List<Engine.Card> cards,long seed,boolean perStage){
            this.perStage=perStage;Map<String,List<Engine.Card>> by=new TreeMap<>();for(Engine.Card c:cards)by.computeIfAbsent(c.deck,k->new ArrayList<>()).add(c);int deckIndex=0;
            for(List<Engine.Card> lane:by.values()){
                lane.sort(Comparator.comparing(x->x.id));int n=lane.size();List<Integer> values=new ArrayList<>();int easy=n/5,hard=n/5;
                for(int i=0;i<easy;i++)values.add(0);for(int i=0;i<n-easy-hard;i++)values.add(3);for(int i=0;i<hard;i++)values.add(7);
                Collections.shuffle(values,new Random(seed+1000L*deckIndex++));for(int i=0;i<n;i++)budget.put(lane.get(i).key(),values.get(i));
            }
        }
        public String reaction(Engine.Card c,Engine.State s,int event){int n=budget.get(c.key());if(perStage){String k=c.key()+"#"+s.contacts;int u=usedStage.getOrDefault(k,0);if(u<n){usedStage.put(k,u+1);return "repeat";}return "remember";}int u=used.getOrDefault(c.key(),0);if(u<n){used.put(c.key(),u+1);return "repeat";}return "remember";}
    }

    private static final class Metrics {
        final Map<String,Integer> cardCount=new TreeMap<>(),deckCount=new TreeMap<>();
        final Map<String,Instant> firstSeen=new HashMap<>(),masteredAt=new HashMap<>(),lastSeen=new HashMap<>();
        final Map<String,Long> shortestRevisit=new HashMap<>();
        final Map<String,Integer> maxOtherBetween=new HashMap<>(),lastIndex=new HashMap<>(),currentSiblingWait=new HashMap<>(),maxSiblingWait=new HashMap<>();
        int maxCardRun,maxDeckRun,maxCompetitiveDeckRun;String lastCard="",lastDeck="";int cardRun,deckRun,competitiveDeckRun;
    }

    private static final class Run {
        final List<Engine.Card> cards;final List<Engine.Plan> plans;final Map<String,Engine.State> states=new HashMap<>();Engine.FairState fair=new Engine.FairState();
        final List<String> order=new ArrayList<>(),reactions=new ArrayList<>(),feasibility=new ArrayList<>();final List<Instant> shownAt=new ArrayList<>();final Metrics m=new Metrics();int events;
        Run(List<Engine.Card> cards,List<Engine.Plan> plans){this.cards=cards;this.plans=plans;}
    }

    private static Set<String> readyDecks(Run run,Instant slot){Set<String> out=new HashSet<>();for(Engine.Card c:run.cards){Engine.Plan p=planFor(run.plans,c.deck);Engine.State s=run.states.getOrDefault(c.learningKey(),new Engine.State());if(!c.active||!p.active||s.contacts>=p.minimum)continue;Instant e=W.next(s.eligible==null||s.eligible.isBefore(slot)?slot:s.eligible);if(!e.isAfter(slot))out.add(c.deck);}return out;}
    private static int eligibleSiblingCount(Run run,String deck,Instant slot){int n=0;for(Engine.Card c:run.cards)if(c.deck.equals(deck)){Engine.Plan p=planFor(run.plans,c.deck);Engine.State s=run.states.getOrDefault(c.learningKey(),new Engine.State());if(s.contacts>=p.minimum)continue;Instant e=W.next(s.eligible==null||s.eligible.isBefore(slot)?slot:s.eligible);if(!e.isAfter(slot))n++;}return n;}

    private static void recordMetrics(Run run,Engine.Card selected,Instant shown,int eligibleSiblings,Set<String> ready){
        Metrics m=run.m;String key=selected.key(),deck=selected.deck;int index=run.order.size();m.cardCount.put(key,m.cardCount.getOrDefault(key,0)+1);m.deckCount.put(deck,m.deckCount.getOrDefault(deck,0)+1);m.firstSeen.putIfAbsent(key,shown);
        if(key.equals(m.lastCard))m.cardRun++;else{m.lastCard=key;m.cardRun=1;}m.maxCardRun=Math.max(m.maxCardRun,m.cardRun);
        String previousDeck=m.lastDeck;if(deck.equals(previousDeck))m.deckRun++;else{m.lastDeck=deck;m.deckRun=1;}m.maxDeckRun=Math.max(m.maxDeckRun,m.deckRun);
        if(ready.size()>1){if(deck.equals(previousDeck))m.competitiveDeckRun++;else m.competitiveDeckRun=1;m.maxCompetitiveDeckRun=Math.max(m.maxCompetitiveDeckRun,m.competitiveDeckRun);}else m.competitiveDeckRun=0;
        Instant prev=m.lastSeen.put(key,shown);if(prev!=null){long dt=Duration.between(prev,shown).toMillis();m.shortestRevisit.merge(key,dt,Math::min);Integer li=m.lastIndex.get(key);if(li!=null)m.maxOtherBetween.merge(key,index-li-1,Math::max);}m.lastIndex.put(key,index);
        for(Engine.Card c:run.cards)if(c.deck.equals(deck)){Engine.Plan p=planFor(run.plans,deck);Engine.State s=run.states.getOrDefault(c.learningKey(),new Engine.State());if(s.contacts>=p.minimum)continue;Instant e=W.next(s.eligible==null||s.eligible.isBefore(shown)?shown:s.eligible);if(e.isAfter(shown))continue;String ck=c.key();if(ck.equals(key))m.currentSiblingWait.put(ck,0);else{int wait=m.currentSiblingWait.getOrDefault(ck,0)+1;m.currentSiblingWait.put(ck,wait);m.maxSiblingWait.merge(ck,wait,Math::max);}}
        if(eligibleSiblings>1&&!run.order.isEmpty())check(!run.order.get(run.order.size()-1).equals(key),"eligible sibling ring must prevent immediate same-card bounce: "+key);
    }

    private static Run simulate(List<Engine.Card> cards,List<Engine.Plan> plans,Behavior behavior,Instant end){
        Run run=new Run(cards,plans);Instant now=START,lastAnswer=null;List<Long> latencies=new ArrayList<>();
        for(int guard=0;guard<50000&&now.isBefore(end);guard++){
            Engine.Decision d=Engine.next(cards,plans,run.states,now,W,latencies,run.fair);run.feasibility.add(d.feasibility);
            if(d.cardKey==null||d.due==null){now=W.next(now.plus(Duration.ofHours(1)));continue;}
            Engine.Card c=cardByKey(cards,d.cardKey);Engine.State before=run.states.getOrDefault(c.learningKey(),new Engine.State());Instant due=d.due;
            if(lastAnswer!=null){Instant floor=W.next(lastAnswer.plusMillis(attentionGap(d)));due=later(due,floor);check(!due.isBefore(W.next(lastAnswer.plusMillis(TEN))),"normal attention floor never below 10 minutes");}
            due=W.next(due);long blocked=Math.max(0,behavior.presentationDelay(c,before,run.events,due));Instant shown=blocked==0?due:W.next(due.plusMillis(blocked));Set<String> ready=readyDecks(run,shown);int siblings=eligibleSiblingCount(run,c.deck,shown);recordMetrics(run,c,shown,siblings,ready);
            String reaction=behavior.reaction(c,before,run.events);long latency=Math.max(0,behavior.latency(c,before,run.events));Instant answered=shown.plusMillis(latency);Engine.State after=Engine.answer(before,planFor(plans,c.deck),reaction,shown,answered,W);run.states.put(c.learningKey(),after);if(d.fairAfter!=null)run.fair=d.fairAfter.copy();
            run.order.add(c.key());run.reactions.add(reaction);run.shownAt.add(shown);run.events++;latencies.add(latency);if(latencies.size()>100)latencies.remove(0);if(after.contacts>=planFor(plans,c.deck).minimum)run.m.masteredAt.putIfAbsent(c.key(),answered);lastAnswer=answered;now=answered;
        }
        return run;
    }

    private static boolean mastered(Run r,String deck){for(Engine.Card c:r.cards)if(c.deck.equals(deck))if(r.states.getOrDefault(c.learningKey(),new Engine.State()).contacts<planFor(r.plans,deck).minimum)return false;return true;}
    private static Instant deckCompletion(Run r,String deck){Instant max=null;for(Engine.Card c:r.cards)if(c.deck.equals(deck)){Instant t=r.m.masteredAt.get(c.key());if(t==null)return null;if(max==null||t.isAfter(max))max=t;}return max;}
    private static int deckTurnsBefore(Run r,String deck,Instant t){int n=0;for(int i=0;i<r.order.size();i++)if(r.order.get(i).startsWith(deck+"/")&&r.shownAt.get(i).isBefore(t))n++;return n;}
    private static int distinct(List<String> order,int n){return new HashSet<>(order.subList(0,Math.min(n,order.size()))).size();}
    private static int topFive(List<String> order,int n){Map<String,Integer>x=new HashMap<>();for(String k:order.subList(0,Math.min(n,order.size())))x.put(k,x.getOrDefault(k,0)+1);List<Integer> v=new ArrayList<>(x.values());v.sort(Comparator.reverseOrder());int sum=0;for(int i=0;i<Math.min(5,v.size());i++)sum+=v.get(i);return sum;}

    private static void persistentRingAndFairState(){
        List<Engine.Card> cards=new ArrayList<>();for(int i=0;i<4;i++)cards.add(card("ring",i));Engine.Plan p=plan("ring",D1);Map<String,Engine.State> states=new HashMap<>();
        for(int i=0;i<4;i++){Engine.State s=new Engine.State();s.last=START.minus(Duration.ofMinutes(40-10L*i));s.eligible=START;s.attempts=1;s.lastReaction="repeat";s.weakDebt=i==0?99:1;states.put(cards.get(i).learningKey(),s);}states.get(cards.get(0).learningKey()).last=START;
        Engine.FairState fair=new Engine.FairState();fair.credit.put("ring",0.25);fair.committedTurns=9;Engine.Decision d=Engine.next(cards,List.of(p),states,START,W,List.of(),fair);
        check(d.cardKey.equals(cards.get(1).key()),"repeat card goes to tail; oldest eligible sibling wins");check(fair.committedTurns==9&&Math.abs(fair.credit.get("ring")-0.25)<1e-9,"Engine.next never mutates persistent fair state");check(d.fairAfter!=null&&d.fairAfter.committedTurns==10,"selected turn is proposed, not implicitly committed");
    }

    private static final class LegacyState {int contacts,weak;Instant eligible;String reaction="";}
    private static List<String> legacyFirstTurns(int turns,long seed){
        List<Engine.Card> cards=new ArrayList<>();for(int i=0;i<20;i++)cards.add(card("legacy",i));List<Integer> difficulty=new ArrayList<>();for(int i=0;i<4;i++)difficulty.add(0);for(int i=0;i<12;i++)difficulty.add(3);for(int i=0;i<4;i++)difficulty.add(7);Collections.shuffle(difficulty,new Random(seed));Map<String,Integer> budgets=new HashMap<>(),used=new HashMap<>();for(int i=0;i<20;i++)budgets.put(cards.get(i).key(),difficulty.get(i));Map<String,LegacyState> states=new HashMap<>();for(Engine.Card c:cards)states.put(c.key(),new LegacyState());List<String> order=new ArrayList<>();Instant now=START,lastAnswer=null;
        for(int event=0;event<turns;event++){
            int remaining=0;for(LegacyState s:states.values())remaining+=5-s.contacts;long available=W.available(now,D1);if(available<=0)break;long spacing=Math.max(1,(long)Math.floor(available/(double)(remaining+1)));Instant base=W.add(now,spacing);Engine.Card selected=null;Instant bestDue=null;
            for(Engine.Card c:cards){LegacyState s=states.get(c.key());if(s.contacts>=5)continue;Instant eligible=W.next(s.eligible==null||s.eligible.isBefore(now)?now:s.eligible);Instant due="repeat".equals(s.reaction)?eligible:(eligible.isAfter(base)?eligible:base);if(!due.isBefore(D1))continue;if(selected==null||due.isBefore(bestDue)||(due.equals(bestDue)&&legacyPriority(c,s,selected,states.get(selected.key()))<0)){selected=c;bestDue=due;}}
            if(selected==null)break;double rate=remaining*3600000.0/available;long gap=rate<=4?FIFTEEN:Math.min(FIFTEEN,Math.max(TEN,(long)Math.floor(3600000.0/rate)));if(lastAnswer!=null)bestDue=later(bestDue,W.next(lastAnswer.plusMillis(gap)));bestDue=W.next(bestDue);
            LegacyState s=states.get(selected.key());int u=used.getOrDefault(selected.key(),0);String reaction=u<budgets.get(selected.key())?"repeat":"remember";if(reaction.equals("repeat"))used.put(selected.key(),u+1);if(reaction.equals("remember")){s.contacts++;s.weak=Math.max(0,s.weak-1);}else s.weak++;s.reaction=reaction;Instant answered=bestDue.plusSeconds(20);if(s.contacts<5){int left=5-s.contacts;long horizon=W.available(answered,D1);double fraction=reaction.equals("repeat")?0.25:0.55;s.eligible=W.add(answered,Math.max(1,(long)(horizon/(double)(left+1)*fraction)));}else s.eligible=null;order.add(selected.key());now=answered;lastAnswer=answered;
        }
        return order;
    }
    private static int legacyPriority(Engine.Card a,LegacyState as,Engine.Card b,LegacyState bs){int d=Double.compare(as.contacts/5.0,bs.contacts/5.0);if(d!=0)return d;d=Integer.compare(bs.weak,as.weak);if(d!=0)return d;return a.key().compareTo(b.key());}

    private static void beforeAfter(){
        List<String> before=legacyFirstTurns(50,17);List<Engine.Card> cards=new ArrayList<>();for(int i=0;i<20;i++)cards.add(card("legacy",i));Run after=simulate(cards,List.of(plan("legacy",D1)),new BudgetBehavior(cards,17,false),END7);
        int b20=distinct(before,20),b30=distinct(before,30),b50=distinct(before,50),bt5=topFive(before,50);int a20=distinct(after.order,20),a30=distinct(after.order,30),a50=distinct(after.order,50),at5=topFive(after.order,50);
        System.out.println("BEFORE_AFTER v0.1.63 distinct20="+b20+" distinct30="+b30+" distinct50="+b50+" top5_50="+bt5+" -> new distinct20="+a20+" distinct30="+a30+" distinct50="+a50+" top5_50="+at5);
        check(b20<=8,"legacy production reproduces small-card cycling");check(a20==20&&a30==20&&a50==20,"new persistent ring exposes all 20 before cycling");check(at5<bt5,"top-five concentration falls after scheduler replacement");
    }

    private static void profile0(){
        List<Engine.Card> cards=threeDeckCards();Run r=simulate(cards,threePlans(),(c,s,e)->"remember",END7);check(mastered(r,"A")&&mastered(r,"B")&&mastered(r,"C"),"P0 all decks master");check(!deckCompletion(r,"A").isAfter(D1)&&!deckCompletion(r,"B").isAfter(D3)&&!deckCompletion(r,"C").isAfter(D5),"P0 deadlines met");check(deckTurnsBefore(r,"B",deckCompletion(r,"A"))>0&&deckTurnsBefore(r,"C",deckCompletion(r,"A"))>0,"P0 later decks start before A completes");for(Engine.Card c:cards)check(r.m.cardCount.getOrDefault(c.key(),0)==5,"P0 exactly five remembers, no spam "+c.key());System.out.println("P0 turns="+r.events+" shares="+r.m.deckCount);
    }

    private static void profile1(){
        long turns=0;for(int seed=0;seed<100;seed++){List<Engine.Card> cards=threeDeckCards();Run r=simulate(cards,threePlans(),new BudgetBehavior(cards,seed,false),END7);turns+=r.events;check(mastered(r,"A")&&mastered(r,"B")&&mastered(r,"C"),"P1 seed "+seed+" completes all");check(!deckCompletion(r,"A").isAfter(D1),"P1 seed "+seed+" A deadline");check(!deckCompletion(r,"B").isAfter(D3),"P1 seed "+seed+" B deadline");check(!deckCompletion(r,"C").isAfter(D5),"P1 seed "+seed+" C deadline");check(deckTurnsBefore(r,"B",deckCompletion(r,"A"))>0&&deckTurnsBefore(r,"C",deckCompletion(r,"A"))>0,"P1 later deck preparation seed "+seed);for(Engine.Card c:cards)check(r.m.maxSiblingWait.getOrDefault(c.key(),0)<=10,"P1 sibling wait bounded "+seed+" "+c.key());}System.out.println("P1 seeds=100 avgTurns="+(turns/100.0));
    }

    private static void profile2(){
        List<Engine.Card> cards=threeDeckCards();Run r=simulate(cards,threePlans(),new BudgetBehavior(cards,23,true),END7);long required=2L*5+6L*(5L*4)+2L*(5L*8);long capacity=W.available(START,D1)/TEN+1;check(required>capacity,"P2 first deadline mathematically impossible at 10-minute floor");check(r.feasibility.stream().anyMatch(x->x.contains("risk")),"P2 explicit risk");for(Engine.Card c:cards)check(r.m.cardCount.getOrDefault(c.key(),0)>0,"P2 every card gets opportunity "+c.key());check(r.m.deckCount.getOrDefault("A",0)>0&&r.m.deckCount.getOrDefault("B",0)>0&&r.m.deckCount.getOrDefault("C",0)>0,"P2 all decks receive service");check(r.m.maxCompetitiveDeckRun<=12,"P2 no competitive deck monopoly; run="+r.m.maxCompetitiveDeckRun);System.out.println("P2 impossible requiredA="+required+" capacityA="+capacity+" turns="+r.events+" shares="+r.m.deckCount+" competitiveRun="+r.m.maxCompetitiveDeckRun);
    }

    private static void profile3(){
        List<Engine.Card> cards=threeDeckCards();Set<String> forever=new HashSet<>();for(String deck:List.of("A","B","C"))for(int i=0;i<8;i++)forever.add(deck+"/"+String.format(Locale.ROOT,"c%02d",i));Behavior b=(c,s,e)->forever.contains(c.key())?"repeat":"remember";Run r=simulate(cards,threePlans(),b,END7);for(Engine.Card c:cards)if(!forever.contains(c.key()))check(r.states.get(c.learningKey()).contacts>=5,"P3 easy 20% masters "+c.key());for(String key:forever)check(r.m.cardCount.getOrDefault(key,0)>=2,"P3 forever-repeat card keeps cycling "+key);for(String deck:List.of("A","B","C"))check(r.m.deckCount.getOrDefault(deck,0)>20,"P3 every deck continues service "+deck);int tailStart=Math.max(0,r.order.size()-60);int tailDistinct=new HashSet<>(r.order.subList(tailStart,r.order.size())).size();check(tailDistinct>=18,"P3 no 2-5 card closed ring; tail distinct="+tailDistinct);check(r.m.maxCardRun==1,"P3 no immediate same-card bounce");System.out.println("P3 turns="+r.events+" tailDistinct60="+tailDistinct+" shares="+r.m.deckCount);
    }

    private static void profile4(){
        List<Engine.Card> cards=threeDeckCards();BudgetBehavior base=new BudgetBehavior(cards,41,false);Behavior mixed=new Behavior(){public String reaction(Engine.Card c,Engine.State s,int e){return base.reaction(c,s,e);}public long latency(Engine.Card c,Engine.State s,int e){int x=Math.floorMod(e*37,100);if(x<50)return Duration.ofSeconds(10+(e*7)%21).toMillis();if(x<75)return Duration.ofMinutes(2+(e*3)%4).toMillis();if(x<90)return Duration.ofMinutes(15+(e*5)%16).toMillis();return Duration.ofMinutes(60+(e*29)%181).toMillis();}public long presentationDelay(Engine.Card c,Engine.State s,int e,Instant due){return (e==19||e==58||e==91)?Duration.ofHours(3).toMillis():0;}};Run r=simulate(cards,threePlans(),mixed,END7);check(r.m.deckCount.getOrDefault("A",0)>0&&r.m.deckCount.getOrDefault("B",0)>0&&r.m.deckCount.getOrDefault("C",0)>0,"P4 delays never starve deck");check(r.feasibility.stream().anyMatch(x->x.contains("observed")||x.contains("risk")),"P4 latency reflected in feasibility");check(r.m.maxCompetitiveDeckRun<=12,"P4 mixed delays preserve fair deck service");System.out.println("P4 turns="+r.events+" shares="+r.m.deckCount+" risk="+r.feasibility.stream().anyMatch(x->x.contains("risk")));
    }

    private static void profile5(){
        List<Engine.Card> cards=new ArrayList<>();for(int i=0;i<10;i++)cards.add(card("night",i));Engine.Plan p=plan("night",D3);Map<String,Engine.State> states=new HashMap<>();Engine.FairState fair=new Engine.FairState();Instant late=ZonedDateTime.of(2026,9,8,23,50,0,0,Z).toInstant();Engine.Decision d=Engine.next(cards,List.of(p),states,late,W,List.of(),fair);check(d.cardKey!=null&&d.fairAfter!=null,"P5 due decision exists");Engine.Card c=cardByKey(cards,d.cardKey);Engine.State before=new Engine.State();Instant unlock=ZonedDateTime.of(2026,9,9,8,40,0,0,Z).toInstant();check(fair.committedTurns==0,"P5 sleep alone spends no virtual turn");Engine.State after=Engine.answer(before,p,"remember",unlock,unlock.plusSeconds(20),W);states.put(c.learningKey(),after);fair=d.fairAfter.copy();Engine.Decision next=Engine.next(cards,List.of(p),states,unlock.plusSeconds(20),W,List.of(20_000L,20_000L,20_000L,20_000L,20_000L),fair);check(fair.committedTurns==1&&next.fairAfter.committedTurns==2,"P5 exactly one turn commits after actual response");check(!next.cardKey.equals(c.key()),"P5 unlock response returns previous card to ring tail");System.out.println("P5 lockedPendingHours="+Duration.between(late,unlock).toMinutes()/60.0);
    }

    private static void profile6(){
        List<Engine.Card> cards=new ArrayList<>();for(int i=0;i<10;i++)cards.add(card("one",i));Map<String,Integer> used=new HashMap<>();String hard="one/c00";Behavior b=(c,s,e)->{int u=used.getOrDefault(c.key(),0);if(c.key().equals(hard)&&u<25){used.put(c.key(),u+1);return "repeat";}return "remember";};Run r=simulate(cards,List.of(plan("one",D3)),b,END7);int hardFirst50=0;for(String k:r.order.subList(0,Math.min(50,r.order.size())))if(k.equals(hard))hardFirst50++;check(hardFirst50<=8,"P6 25-repeat card cannot occupy every second presentation; first50="+hardFirst50);check(distinct(r.order,20)==10,"P6 all siblings appear despite extreme hard card");System.out.println("P6 hardCardFirst50="+hardFirst50+" totalHard="+r.m.cardCount.getOrDefault(hard,0));
    }

    private static void profile7(){
        List<Engine.Card> cards=threeDeckCards();Map<String,Integer> stage=new HashMap<>();Behavior b=(c,s,e)->{int budget=c.deck.equals("A")?3:(c.deck.equals("B")?1:0);String k=c.key()+"#"+s.contacts;int u=stage.getOrDefault(k,0);if(u<budget){stage.put(k,u+1);return "repeat";}return "remember";};Run r=simulate(cards,threePlans(),b,END7);int a=deckTurnsBefore(r,"A",D1),bb=deckTurnsBefore(r,"B",D1),cc=deckTurnsBefore(r,"C",D1);check(a>bb&&a>cc,"P7 hard nearest deck receives larger bandwidth");check(bb>0&&cc>0,"P7 B/C still learn before A deadline");check(r.m.maxCompetitiveDeckRun<=12,"P7 hard A does not monopolize fair queue");System.out.println("P7 beforeD1 A/B/C="+a+"/"+bb+"/"+cc+" competitiveRun="+r.m.maxCompetitiveDeckRun);
    }

    private static void profile8(){
        List<Engine.Card> cards=threeDeckCards();Behavior b=(c,s,e)->c.deck.equals("A")?"repeat":"remember";Run r=simulate(cards,threePlans(),b,END7);long capacity=W.available(START,D1)/TEN+1;long impossible=10L*80L;check(impossible>capacity,"P8 A has explicit impossible workload witness");check(r.feasibility.stream().anyMatch(x->x.contains("risk")||x.contains("deadline_missed")),"P8 risk/missed explicit");check(mastered(r,"B")&&mastered(r,"C"),"P8 impossible A never sacrifices salvageable B/C");check(!deckCompletion(r,"B").isAfter(D3)&&!deckCompletion(r,"C").isAfter(D5),"P8 B/C meet deadlines");check(r.m.deckCount.getOrDefault("A",0)>0,"P8 A continues learning despite impossibility");check(r.m.maxCompetitiveDeckRun<=12,"P8 impossible A has no rescue monopoly");System.out.println("P8 witnessA="+impossible+" capacityA="+capacity+" shares="+r.m.deckCount+" competitiveRun="+r.m.maxCompetitiveDeckRun);
    }

    public static void main(String[] args){persistentRingAndFairState();beforeAfter();profile0();profile1();profile2();profile3();profile4();profile5();profile6();profile7();profile8();System.out.println("PASS "+checks+" scheduler simulation assertions; horizon >= 7 simulated days");}
}
