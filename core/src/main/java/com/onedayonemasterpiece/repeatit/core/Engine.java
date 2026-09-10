package com.onedayonemasterpiece.repeatit.core;

import java.time.*;
import java.util.*;

/**
 * Deterministic spaced-learning scheduler.
 *
 * Card identity is a persistent per-deck ring (least recently served eligible card).
 * Deck identity is a persistent weighted fair queue whose state is committed only
 * with a real normal response by the Android Store.
 */
public final class Engine {
    private Engine() {}

    /** Legacy compatibility constant; Store owns the real normal attention policy. */
    public static final long HUMAN_PACING_FLOOR_MILLIS=Duration.ofMinutes(3).toMillis();
    /** Product absolute floor for normal presentation starts after the previous answer. */
    public static final long NORMAL_ATTENTION_FLOOR_MILLIS=Duration.ofMinutes(10).toMillis();

    private static final double PRIOR_REMEMBERS=4.0;
    private static final double PRIOR_REPEATS=2.0;
    private static final double MIN_SUCCESS_PROBABILITY=0.20;
    private static final double MAX_SUCCESS_PROBABILITY=0.95;
    private static final double DEADLINE_UNCERTAINTY_RESERVE=1.18;
    private static final double FAIR_BASE_SHARE=0.20;
    private static final double CREDIT_BOUND=2.0;
    private static final long UNDATED_HORIZON_DAYS=7;
    private static final double RESCUE_FEASIBILITY_TOLERANCE=1.08;
    private static final double RESCUE_MAX_VIRTUAL_BORROW=0.70;

    public static final class Card {
        public String deck, id, title, text, image = "", alt = "", caption = "", imageHash = "";
        public int revision = 1, meaning = 1;
        public boolean active = true, preview = false;
        public Map<String, Object> source = new LinkedHashMap<>();
        public String key() { return deck + "/" + id; }
        public String learningKey() { return key() + "@" + meaning; }
    }

    public static final class Plan {
        public String deck;
        public Instant deadline;
        public int minimum = 5;
        public boolean active = true;
    }

    public static final class State {
        /** Successful spaced "remember" responses for the current meaning revision. */
        public int contacts, weakDebt, attempts;
        public Instant last, eligible;
        public String lastReaction = "";
        public long latencyMillis;
        public State copy(){
            State s=new State();
            s.contacts=contacts;s.weakDebt=weakDebt;s.attempts=attempts;
            s.last=last;s.eligible=eligible;s.lastReaction=lastReaction;s.latencyMillis=latencyMillis;
            return s;
        }
    }

    /** Persistent deck-service state. It is deliberately independent from lifetime attempts. */
    public static final class FairState {
        public int schemaVersion=1;
        public long committedTurns;
        public String lastDeck="";
        public Map<String,Double> credit=new TreeMap<>();
        public FairState copy(){
            FairState x=new FairState();
            x.schemaVersion=schemaVersion<=0?1:schemaVersion;
            x.committedTurns=Math.max(0,committedTurns);
            x.lastDeck=lastDeck==null?"":lastDeck;
            if(credit!=null)for(Map.Entry<String,Double> e:credit.entrySet())
                if(e.getKey()!=null&&e.getValue()!=null&&Double.isFinite(e.getValue()))
                    x.credit.put(e.getKey(),clamp(e.getValue(),-CREDIT_BOUND,CREDIT_BOUND));
            return x;
        }
    }

    /** Daily device-local gate for NEW presentations; start may cross midnight. */
    public static final class Window {
        public final LocalTime start,end;public final ZoneId zone;
        public Window(LocalTime start,LocalTime end,ZoneId zone){
            if(start.equals(end))throw new IllegalArgumentException("window cannot cover zero/full day");
            this.start=start;this.end=end;this.zone=zone;
        }
        private boolean overnight(){return start.isAfter(end);}
        public boolean allowed(Instant time){
            LocalTime t=time.atZone(zone).toLocalTime();
            return overnight()?(!t.isBefore(start)||t.isBefore(end)):(!t.isBefore(start)&&t.isBefore(end));
        }
        public Instant next(Instant time){
            ZonedDateTime z=time.atZone(zone);LocalTime t=z.toLocalTime();LocalDate d=z.toLocalDate();
            if(allowed(time))return time;
            if(overnight())return d.atTime(start).atZone(zone).toInstant();
            if(t.isBefore(start))return d.atTime(start).atZone(zone).toInstant();
            return d.plusDays(1).atTime(start).atZone(zone).toInstant();
        }
        public Instant close(Instant time){
            ZonedDateTime z=next(time).atZone(zone);LocalDate d=z.toLocalDate();LocalTime t=z.toLocalTime();
            if(overnight()&&!t.isBefore(start))d=d.plusDays(1);
            return d.atTime(end).atZone(zone).toInstant();
        }
        public long available(Instant from,Instant to){
            if(!to.isAfter(from))return 0;
            long total=0;
            LocalDate first=from.atZone(zone).toLocalDate().minusDays(1),last=to.atZone(zone).toLocalDate();
            for(LocalDate d=first;!d.isAfter(last);d=d.plusDays(1)){
                Instant a=d.atTime(start).atZone(zone).toInstant();
                Instant b=(overnight()?d.plusDays(1):d).atTime(end).atZone(zone).toInstant();
                if(a.isBefore(from))a=from;if(b.isAfter(to))b=to;
                if(b.isAfter(a))total=Math.addExact(total,Duration.between(a,b).toMillis());
            }
            return total;
        }
        public Instant add(Instant from,long activeMillis){
            Instant cursor=next(from);long left=Math.max(0,activeMillis);
            while(true){
                long room=Duration.between(cursor,close(cursor)).toMillis();
                if(left<room)return cursor.plusMillis(left);
                left-=room;cursor=next(close(cursor));
                if(left==0)return cursor;
            }
        }
    }

    public static final class Load {
        public Instant deadline;
        /** Successful remembers still required by this deadline prefix. */
        public int cumulative;
        public long availableMillis;
        /** Smoothed expected attempts, including repeat behavior. */
        public double estimatedAttempts;
        public Double requiredPerAllowedHour;
        public long capacityAtAttentionFloor;
        public boolean feasibleAtAttentionFloor;
    }

    public static final class Decision {
        public String cardKey;
        public String deck;
        public Instant due;
        public int remaining,expired,withoutPlan;
        public long spacingMillis;
        public long expectedResponseMillis;
        public double estimatedRemainingAttempts;
        public String feasibility="preliminary_no_response_history";
        public FairState fairAfter;
        public final Map<String,Double> deckWeights=new TreeMap<>();
        public final List<Load> loads=new ArrayList<>();
    }

    private static final class Item {
        Card c;Plan p;State s;int n;Instant eligible;
        Item(Card c,Plan p,State s,int n,Instant eligible){this.c=c;this.p=p;this.s=s;this.n=n;this.eligible=eligible;}
    }
    private static final class Lane {
        String deck;Plan p;final List<Item> items=new ArrayList<>();
        int remaining,evidenceAttempts,evidenceRemembers;
        double successProbability,estimatedAttempts,pressure,weight;
        Instant earliestEligible;
    }
    private static final class Evidence {int attempts,remembers;}

    /** Backward-compatible stateless call used by old checks and one-deck code. */
    public static Decision next(List<Card> cards,List<Plan> plans,Map<String,State> states,
                                Instant now,Window w,List<Long> responseLatencies){
        return next(cards,plans,states,now,w,responseLatencies,new FairState());
    }

    /**
     * Pure scheduler decision. fairState is never mutated. The returned fairAfter is
     * a proposed service-state transition and must be committed only with the actual response.
     */
    public static Decision next(List<Card> cards,List<Plan> plans,Map<String,State> states,
                                Instant now,Window w,List<Long> responseLatencies,FairState fairState){
        Decision result=new Decision();
        result.expectedResponseMillis=expectedResponseMillis(responseLatencies);

        Map<String,Plan> byDeck=new HashMap<>();
        for(Plan p:plans)if(p.active)byDeck.put(p.deck,p);

        Map<String,List<Item>> grouped=new TreeMap<>();
        Map<String,Evidence> evidence=new HashMap<>();
        for(Card c:cards){
            if(!c.active)continue;
            Plan p=byDeck.get(c.deck);
            if(p==null){result.withoutPlan++;continue;}
            State s=states.getOrDefault(c.learningKey(),new State());
            Evidence e=evidence.computeIfAbsent(c.deck,k->new Evidence());
            int inferredAttempts=Math.max(Math.max(0,s.attempts),Math.max(0,s.contacts)+Math.max(0,s.weakDebt));
            e.remembers+=Math.max(0,s.contacts);e.attempts+=inferredAttempts;
            int n=Math.max(0,p.minimum-s.contacts);
            if(n==0)continue;
            result.remaining+=n;
            if(p.deadline!=null&&!p.deadline.isAfter(now))result.expired+=n;
            Instant eligible=w.next(s.eligible==null||s.eligible.isBefore(now)?now:s.eligible);
            grouped.computeIfAbsent(c.deck,k->new ArrayList<>()).add(new Item(c,p,s,n,eligible));
        }
        if(grouped.isEmpty())return result;

        List<Lane> lanes=new ArrayList<>();
        for(Map.Entry<String,List<Item>> entry:grouped.entrySet()){
            Lane lane=new Lane();lane.deck=entry.getKey();lane.items.addAll(entry.getValue());lane.p=lane.items.get(0).p;
            Evidence ev=evidence.getOrDefault(lane.deck,new Evidence());
            lane.evidenceAttempts=ev.attempts;lane.evidenceRemembers=ev.remembers;
            for(Item x:lane.items){
                lane.remaining+=x.n;
                if(lane.earliestEligible==null||x.eligible.isBefore(lane.earliestEligible))lane.earliestEligible=x.eligible;
            }
            lane.successProbability=clamp(
                (lane.evidenceRemembers+PRIOR_REMEMBERS)/
                    (lane.evidenceAttempts+PRIOR_REMEMBERS+PRIOR_REPEATS),
                MIN_SUCCESS_PROBABILITY,MAX_SUCCESS_PROBABILITY);
            lane.estimatedAttempts=(lane.remaining/lane.successProbability)*DEADLINE_UNCERTAINTY_RESERVE;
            result.estimatedRemainingAttempts+=lane.estimatedAttempts;

            long horizon;
            if(lane.p.deadline!=null&&lane.p.deadline.isAfter(now))horizon=w.available(now,lane.p.deadline);
            else horizon=w.available(now,now.plus(Duration.ofDays(UNDATED_HORIZON_DAYS)));
            double raw=lane.estimatedAttempts/Math.max(1.0,horizon);
            double maxRate=1.0/Math.max(1.0,NORMAL_ATTENTION_FLOOR_MILLIS+result.expectedResponseMillis);
            lane.pressure=Math.min(raw,maxRate);
            lanes.add(lane);
        }

        // Aggregate deadline-prefix capacity: all decks share one human channel.
        List<Instant> deadlines=new ArrayList<>();
        for(Lane lane:lanes)if(lane.p.deadline!=null&&lane.p.deadline.isAfter(now)&&!deadlines.contains(lane.p.deadline))
            deadlines.add(lane.p.deadline);
        Collections.sort(deadlines);

        double interval=Double.POSITIVE_INFINITY;
        for(Instant deadline:deadlines){
            int successes=0;double attempts=0;
            for(Lane lane:lanes)if(lane.p.deadline!=null&&!lane.p.deadline.isAfter(deadline)){
                successes+=lane.remaining;attempts+=lane.estimatedAttempts;
            }
            Load load=new Load();load.deadline=deadline;load.cumulative=successes;load.estimatedAttempts=attempts;
            load.availableMillis=w.available(now,deadline);
            load.requiredPerAllowedHour=load.availableMillis==0?null:attempts*3600000.0/load.availableMillis;
            long cycle=Math.max(1,NORMAL_ATTENTION_FLOOR_MILLIS+result.expectedResponseMillis);
            load.capacityAtAttentionFloor=load.availableMillis==0?0:(load.availableMillis/cycle)+1;
            load.feasibleAtAttentionFloor=attempts<=load.capacityAtAttentionFloor+1e-9;
            result.loads.add(load);

            if(attempts>0&&load.availableMillis==0){
                result.feasibility="risk_no_allowed_window_before_deadline";
            }else if(attempts>load.capacityAtAttentionFloor+1e-9){
                result.feasibility="risk_from_attention_floor";
            }
            if(attempts>0&&load.availableMillis>0){
                double cycleInterval=load.availableMillis/(attempts+1.0);
                double presentationGap=Math.max(1.0,cycleInterval-result.expectedResponseMillis);
                interval=Math.min(interval,presentationGap);
            }
        }
        result.spacingMillis=Double.isInfinite(interval)?0:Math.max(1,(long)Math.floor(interval));

        if(responseLatencies!=null&&responseLatencies.size()>=5){
            if(!result.feasibility.startsWith("risk_"))result.feasibility="observed_latency_and_response_rate_no_guarantee";
            for(Load load:result.loads){
                if(load.availableMillis>0&&
                   result.expectedResponseMillis*load.estimatedAttempts>load.availableMillis)
                    result.feasibility="risk_from_observed_response_latency";
            }
        }
        if(result.expired>0)
            result.feasibility=result.feasibility.startsWith("risk_")?
                "deadline_missed_and_observed_risk":"deadline_missed_continue_learning";

        Instant slot=result.spacingMillis>0?w.add(now,result.spacingMillis):w.next(now);
        List<Lane> ready=readyLanes(lanes,slot);
        if(ready.isEmpty()){
            slot=null;
            for(Lane lane:lanes)if(lane.earliestEligible!=null&&(slot==null||lane.earliestEligible.isBefore(slot)))
                slot=lane.earliestEligible;
            if(slot==null)return result;
            slot=w.next(slot);ready=readyLanes(lanes,slot);
        }
        if(ready.isEmpty())return result;

        // Permanent service floor plus deadline/workload pressure.
        double pressureTotal=0;
        for(Lane lane:ready)pressureTotal+=Math.max(0,lane.pressure);
        for(Lane lane:ready){
            double proportional=pressureTotal<=0?1.0/ready.size():Math.max(0,lane.pressure)/pressureTotal;
            lane.weight=FAIR_BASE_SHARE/ready.size()+(1.0-FAIR_BASE_SHARE)*proportional;
            result.deckWeights.put(lane.deck,lane.weight);
        }

        FairState proposed=normalizedFairState(fairState,lanes);
        Lane selected=null;double selectedCredit=Double.NEGATIVE_INFINITY;
        for(Lane lane:ready){
            double c=clamp(proposed.credit.getOrDefault(lane.deck,0.0),-CREDIT_BOUND,CREDIT_BOUND)+lane.weight;
            proposed.credit.put(lane.deck,c);
            if(selected==null||c>selectedCredit+1e-12||
               (Math.abs(c-selectedCredit)<=1e-12&&laneTie(lane,selected,slot)<0)){
                selected=lane;selectedCredit=c;
            }
        }

        // Bounded deadline-prefix reservation. Rescue only if the predicted prefix is still
        // mathematically salvageable at the product attention floor; impossible work cannot monopolize.
        Lane rescue=deadlineRescue(ready,lanes,slot,w,result.expectedResponseMillis);
        if(rescue!=null&&selected!=null&&!selected.deck.equals(rescue.deck)){
            double rescueCredit=proposed.credit.getOrDefault(rescue.deck,0.0);
            if(rescueCredit+RESCUE_MAX_VIRTUAL_BORROW>=selectedCredit)selected=rescue;
        }

        if(selected==null)return result;
        double totalReadyWeight=0;for(Lane lane:ready)totalReadyWeight+=lane.weight;
        proposed.credit.put(selected.deck,
            clamp(proposed.credit.getOrDefault(selected.deck,0.0)-Math.max(1e-9,totalReadyWeight),
                  -CREDIT_BOUND,CREDIT_BOUND));
        proposed.committedTurns=Math.max(0,proposed.committedTurns)+1;
        proposed.lastDeck=selected.deck;

        Item item=selectRingItem(selected,slot);
        if(item==null)return result;
        result.cardKey=item.c.key();result.deck=selected.deck;
        result.due=item.eligible.isAfter(slot)?item.eligible:slot;
        result.fairAfter=proposed;
        return result;
    }

    private static FairState normalizedFairState(FairState input,List<Lane> lanes){
        FairState out=input==null?new FairState():input.copy();
        Set<String> active=new HashSet<>();for(Lane lane:lanes)active.add(lane.deck);
        out.credit.keySet().removeIf(k->!active.contains(k));
        for(String deck:active)out.credit.putIfAbsent(deck,0.0);
        return out;
    }

    private static Lane deadlineRescue(List<Lane> ready,List<Lane> all,Instant slot,Window w,long expectedResponse){
        List<Instant> deadlines=new ArrayList<>();
        for(Lane lane:all)if(lane.p.deadline!=null&&lane.p.deadline.isAfter(slot)&&!deadlines.contains(lane.p.deadline))
            deadlines.add(lane.p.deadline);
        Collections.sort(deadlines);
        long cycle=Math.max(1,NORMAL_ATTENTION_FLOOR_MILLIS+expectedResponse);
        for(Instant deadline:deadlines){
            double attempts=0;
            for(Lane lane:all)if(lane.p.deadline!=null&&!lane.p.deadline.isAfter(deadline))attempts+=lane.estimatedAttempts;
            long capacity=(w.available(slot,deadline)/cycle)+1;
            boolean salvageable=attempts<=capacity*RESCUE_FEASIBILITY_TOLERANCE+1.0;
            boolean tight=attempts>=Math.max(1,capacity-1);
            if(!salvageable||!tight)continue;
            Lane best=null;
            for(Lane lane:ready){
                if(lane.p.deadline==null||lane.p.deadline.isAfter(deadline))continue;
                if(best==null||laneTie(lane,best,slot)<0)best=lane;
            }
            if(best!=null)return best;
        }
        return null;
    }

    private static List<Lane> readyLanes(List<Lane> lanes,Instant slot){
        List<Lane> out=new ArrayList<>();
        for(Lane lane:lanes)for(Item x:lane.items)if(!x.eligible.isAfter(slot)){out.add(lane);break;}
        return out;
    }

    private static Item selectRingItem(Lane lane,Instant slot){
        Item best=null;
        for(Item x:lane.items){
            if(x.eligible.isAfter(slot))continue;
            if(best==null||ringPriority(x,best)<0)best=x;
        }
        return best;
    }

    private static int ringPriority(Item a,Item b){
        if(a.s.last==null&&b.s.last!=null)return -1;
        if(a.s.last!=null&&b.s.last==null)return 1;
        if(a.s.last!=null){
            int d=a.s.last.compareTo(b.s.last);if(d!=0)return d;
        }
        int d=Double.compare(a.s.contacts/(double)Math.max(1,a.p.minimum),
                             b.s.contacts/(double)Math.max(1,b.p.minimum));
        if(d!=0)return d;
        return Long.compareUnsigned(stableHash(a.c.key()),stableHash(b.c.key()));
    }

    private static int laneTie(Lane a,Lane b,Instant slot){
        int ar=deadlineRank(a.p.deadline,slot),br=deadlineRank(b.p.deadline,slot);
        if(ar!=br)return Integer.compare(ar,br);
        if(a.p.deadline!=null&&b.p.deadline!=null){
            int d=a.p.deadline.compareTo(b.p.deadline);if(d!=0)return d;
        }
        return Long.compareUnsigned(stableHash(a.deck),stableHash(b.deck));
    }

    private static int deadlineRank(Instant deadline,Instant now){
        if(deadline==null)return 2;
        return deadline.isAfter(now)?0:1;
    }

    private static long expectedResponseMillis(List<Long> values){
        if(values==null||values.size()<5)return 0;
        List<Long> sorted=new ArrayList<>();
        for(Long v:values)if(v!=null&&v>=0&&v<=Duration.ofDays(2).toMillis())sorted.add(v);
        if(sorted.size()<5)return 0;
        Collections.sort(sorted);
        int index=(int)Math.floor((sorted.size()-1)*0.60);
        return sorted.get(Math.max(0,Math.min(sorted.size()-1,index)));
    }

    private static double clamp(double value,double lo,double hi){return Math.max(lo,Math.min(hi,value));}
    private static long stableHash(String value){
        long h=0xcbf29ce484222325L;
        for(int i=0;i<value.length();i++){h^=value.charAt(i);h*=0x100000001b3L;}
        return h;
    }

    public static State retarget(State original,Plan p,Window w){
        State s=original.copy();
        if(s.last==null)return s;
        if(s.contacts>=p.minimum){s.eligible=null;return s;}
        if(p.deadline==null||!p.deadline.isAfter(s.last)){
            s.eligible=undatedEligible(s.last,s.contacts,s.lastReaction,s.weakDebt,w);return s;
        }
        int left=Math.max(0,p.minimum-s.contacts);
        double fraction=s.lastReaction.equals("repeat")?0.25:0.55;
        long gap=Math.max(1,(long)(w.available(s.last,p.deadline)/(double)(Math.max(1,left)+1)*fraction));
        s.eligible=w.add(s.last,gap);return s;
    }

    public static State answer(State old,Plan p,String reaction,Instant shown,Instant answered,Window w){
        if(!reaction.equals("remember")&&!reaction.equals("repeat"))throw new IllegalArgumentException("reaction");
        if(answered.isBefore(shown))throw new IllegalArgumentException("clock moved backwards");
        State s=old.copy();
        if(old.eligible!=null&&shown.isBefore(old.eligible))return s;
        s.attempts++;
        if(reaction.equals("remember")){
            s.contacts++;s.weakDebt=Math.max(0,s.weakDebt-1);
        }else s.weakDebt=Math.min(1000,s.weakDebt+1);
        s.last=answered;s.lastReaction=reaction;s.latencyMillis=Duration.between(shown,answered).toMillis();
        if(s.contacts>=p.minimum){s.eligible=null;return s;}
        if(p.deadline==null||!p.deadline.isAfter(answered)){
            s.eligible=undatedEligible(answered,s.contacts,s.lastReaction,s.weakDebt,w);return s;
        }
        int left=Math.max(0,p.minimum-s.contacts);long horizon=w.available(answered,p.deadline);
        double fraction=reaction.equals("repeat")?0.25:0.55;
        long gap=Math.max(1,(long)(horizon/(double)(Math.max(1,left)+1)*fraction));
        s.eligible=w.add(answered,gap);return s;
    }

    private static Instant undatedEligible(Instant from,int successes,String reaction,int weakDebt,Window w){
        Duration gap;
        if(reaction.equals("repeat")){
            long minutes=Math.max(15,60/Math.max(1,Math.min(4,weakDebt)));gap=Duration.ofMinutes(minutes);
        }else gap=switch(successes){
            case 0->Duration.ZERO;
            case 1->Duration.ofHours(4);
            case 2->Duration.ofDays(1);
            case 3->Duration.ofDays(3);
            default->Duration.ofDays(7);
        };
        return w.next(from.plus(gap));
    }
}
