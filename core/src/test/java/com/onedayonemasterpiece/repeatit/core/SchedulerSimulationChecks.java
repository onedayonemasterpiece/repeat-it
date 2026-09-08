package com.onedayonemasterpiece.repeatit.core;

import java.time.*;
import java.util.*;

/** Multi-day deterministic user-behavior simulations; no private/user data. */
public final class SchedulerSimulationChecks {
    private static int checks;
    private static final ZoneId Z=ZoneOffset.ofHours(2);
    private static final Engine.Window W=new Engine.Window(LocalTime.of(7,40),LocalTime.of(0,30),Z);
    private static final Instant START=ZonedDateTime.of(2026,9,8,16,56,0,0,Z).toInstant();
    private static final Instant D1=ZonedDateTime.of(2026,9,9,23,59,0,0,Z).toInstant();
    private static final Instant D2=ZonedDateTime.of(2026,9,11,23,59,0,0,Z).toInstant();
    private static final Instant D3=ZonedDateTime.of(2026,9,13,23,59,0,0,Z).toInstant();

    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static Engine.Card card(String deck,int i){Engine.Card c=new Engine.Card();c.deck=deck;c.id=String.format(Locale.ROOT,"c%02d",i);c.title="Synthetic";c.text="Synthetic public test only";return c;}
    private static Engine.Plan plan(String deck,Instant deadline){Engine.Plan p=new Engine.Plan();p.deck=deck;p.deadline=deadline;p.minimum=5;return p;}
    private static long attentionGap(Engine.Decision d){double rate=0;for(Engine.Load l:d.loads)if(l.requiredPerAllowedHour!=null)rate=Math.max(rate,l.requiredPerAllowedHour);if(rate<=4.0)return Duration.ofMinutes(15).toMillis();long calculated=(long)Math.floor(3600000.0/rate);return Math.min(Duration.ofMinutes(15).toMillis(),Math.max(Duration.ofMinutes(10).toMillis(),calculated));}
    private static Instant later(Instant a,Instant b){return a.isAfter(b)?a:b;}

    private static final class Profile {
        final Map<String,Integer> repeatBudget=new HashMap<>();
        final Map<String,Integer> repeatsUsed=new HashMap<>();
        final Map<String,Integer> stageRepeats=new HashMap<>();
        final boolean perStage;
        Profile(List<Engine.Card> cards,long seed,boolean perStage){this.perStage=perStage;Map<String,List<Engine.Card>> by=new TreeMap<>();for(Engine.Card c:cards)by.computeIfAbsent(c.deck,k->new ArrayList<>()).add(c);int deckIndex=0;for(List<Engine.Card> lane:by.values()){int n=lane.size();List<Integer> values=new ArrayList<>();int zero=n/5,hard=n/5;for(int i=0;i<zero;i++)values.add(0);for(int i=0;i<n-zero-hard;i++)values.add(3);for(int i=0;i<hard;i++)values.add(7);Collections.shuffle(values,new Random(seed+1000L*deckIndex++));lane.sort(Comparator.comparing(x->x.id));for(int i=0;i<n;i++)repeatBudget.put(lane.get(i).key(),values.get(i));}}
        String reaction(Engine.Card c,Engine.State s){int budget=repeatBudget.get(c.key());if(perStage){String key=c.key()+"#"+s.contacts;int used=stageRepeats.getOrDefault(key,0);if(used<budget){stageRepeats.put(key,used+1);return "repeat";}return "remember";}int used=repeatsUsed.getOrDefault(c.key(),0);if(used<budget){repeatsUsed.put(c.key(),used+1);return "repeat";}return "remember";}
    }
    private static final class Run {
        final List<Engine.Card> cards;final List<Engine.Plan> plans;final Map<String,Engine.State> states=new HashMap<>();final Map<String,Instant> firstSeen=new HashMap<>(),masteredAt=new HashMap<>();final List<String> order=new ArrayList<>();int events;
        Run(List<Engine.Card> cards,List<Engine.Plan> plans){this.cards=cards;this.plans=plans;}
    }
    private static Run simulate(List<Engine.Card> cards,List<Engine.Plan> plans,Profile profile,Instant end){
        Run run=new Run(cards,plans);Instant now=START,lastAnswer=null;List<Long> latency=new ArrayList<>();Map<String,Engine.Card> byKey=new HashMap<>();for(Engine.Card c:cards)byKey.put(c.key(),c);
        for(int guard=0;guard<20000&&now.isBefore(end);guard++){
            boolean done=true;for(Engine.Card c:cards){Engine.Plan p=plans.stream().filter(x->x.deck.equals(c.deck)&&x.active).findFirst().orElse(null);if(p!=null&&run.states.getOrDefault(c.learningKey(),new Engine.State()).contacts<p.minimum){done=false;break;}}if(done)break;
            Engine.Decision d=Engine.next(cards,plans,run.states,now,W,latency);if(d.cardKey==null||d.due==null){now=W.next(now.plus(Duration.ofMinutes(10)));continue;}Engine.Card c=byKey.get(d.cardKey);check(c!=null,"scheduled identity exists");
            Instant due=d.due;if(lastAnswer!=null)due=later(due,W.next(lastAnswer.plusMillis(attentionGap(d))));due=W.next(due);Engine.State before=run.states.getOrDefault(c.learningKey(),new Engine.State());String reaction=profile.reaction(c,before);run.firstSeen.putIfAbsent(c.key(),due);run.order.add(c.key());Instant answered=due.plusSeconds(20);Engine.State after=Engine.answer(before,plans.stream().filter(x->x.deck.equals(c.deck)).findFirst().orElseThrow(),reaction,due,answered,W);run.states.put(c.learningKey(),after);run.events++;latency.add(20000L);if(latency.size()>100)latency.remove(0);if(after.contacts>=5)run.masteredAt.putIfAbsent(c.key(),answered);lastAnswer=answered;now=answered;
        }
        return run;
    }

    private static void persistentRing(){
        List<Engine.Card> cards=new ArrayList<>();for(int i=0;i<4;i++)cards.add(card("ring",i));Engine.Plan p=plan("ring",D1);Map<String,Engine.State> states=new HashMap<>();
        for(int i=0;i<4;i++){Engine.State s=new Engine.State();s.last=START.minus(Duration.ofMinutes(30-10L*i));s.eligible=START;s.attempts=1;s.lastReaction="repeat";s.weakDebt=i==0?7:1;states.put(cards.get(i).learningKey(),s);}states.get(cards.get(0).learningKey()).last=START;states.get(cards.get(0).learningKey()).weakDebt=99;
        Engine.Decision d=Engine.next(cards,List.of(p),states,START,W,List.of());check(d.cardKey.equals(cards.get(1).key()),"repeat card goes to tail; oldest eligible sibling wins even with huge weak debt");
    }

    private static void oneDeckTwentyCoverage(){
        List<Engine.Card> cards=new ArrayList<>();for(int i=0;i<20;i++)cards.add(card("one",i));Run run=simulate(cards,List.of(plan("one",D1)),new Profile(cards,17,false),START.plus(Duration.ofDays(4)));Set<String> firstTwenty=new HashSet<>(run.order.subList(0,Math.min(20,run.order.size())));check(firstTwenty.size()==20,"twenty-card ring exposes twenty distinct cards in first twenty contacts despite repeats");
        long capacity=W.available(START,D1)/Duration.ofMinutes(10).toMillis()+1;long required=4L*0+12L*3+4L*7+20L*5;check(required>capacity,"20-card tomorrow profile is mathematically infeasible under 10-minute absolute attention floor");
    }

    private static void threeDeckDeadlineSimulation(){
        for(int seed=0;seed<100;seed++){
            List<Engine.Card> cards=new ArrayList<>();for(int i=0;i<10;i++){cards.add(card("d1",i));cards.add(card("d2",i));cards.add(card("d3",i));}List<Engine.Plan> plans=List.of(plan("d1",D1),plan("d2",D2),plan("d3",D3));Run run=simulate(cards,plans,new Profile(cards,seed,false),START.plus(Duration.ofDays(8)));
            for(Engine.Card c:cards){Instant deadline=c.deck.equals("d1")?D1:c.deck.equals("d2")?D2:D3;Instant mastered=run.masteredAt.get(c.key());check(mastered!=null&&!mastered.isAfter(deadline),"seed "+seed+" masters "+c.key()+" by its deadline");}
            for(Engine.Card c:cards){Instant deadline=c.deck.equals("d1")?D1:c.deck.equals("d2")?D2:D3;Instant first=run.firstSeen.get(c.key());check(first!=null&&first.isBefore(deadline.minus(Duration.ofHours(24))),"feasible profile introduces "+c.key()+" at least 24h before its own deadline");}
        }
    }

    private static void impossibleHeavyRepeatSimulation(){
        long perDeckRequired=2L*5+6L*(5L*4)+2L*(5L*8);long d1Capacity=W.available(START,D1)/Duration.ofMinutes(10).toMillis()+1;long d2Capacity=W.available(START,D2)/Duration.ofMinutes(10).toMillis()+1;long d3Capacity=W.available(START,D3)/Duration.ofMinutes(10).toMillis()+1;check(perDeckRequired>d1Capacity,"7x/3x repeat-per-stage workload cannot fit first deadline even at absolute floor");check(perDeckRequired*2>d2Capacity,"same workload cannot fit first two deadlines cumulatively");check(perDeckRequired*3>d3Capacity,"same workload cannot fit all three deadlines cumulatively");
        List<Engine.Card> cards=new ArrayList<>();for(int i=0;i<10;i++){cards.add(card("d1",i));cards.add(card("d2",i));cards.add(card("d3",i));}Run run=simulate(cards,List.of(plan("d1",D1),plan("d2",D2),plan("d3",D3)),new Profile(cards,23,true),START.plus(Duration.ofDays(10)));for(Engine.Card c:cards){Instant deadline=c.deck.equals("d1")?D1:c.deck.equals("d2")?D2:D3;Instant first=run.firstSeen.get(c.key());check(first!=null&&!first.isAfter(deadline),"in impossible workload every card is still exposed before its own deadline: "+c.key());}
    }

    public static void main(String[] args){persistentRing();oneDeckTwentyCoverage();threeDeckDeadlineSimulation();impossibleHeavyRepeatSimulation();System.out.println("PASS "+checks+" scheduler simulation assertions");}
}
