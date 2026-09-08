package com.onedayonemasterpiece.repeatit.core;

import java.time.*;
import java.util.*;

/** Deterministic spaced-learning scheduler with deadline-weighted fair deck rotation. */
public final class Engine {
    private Engine() {}

    /** Kept for compatibility with older checks; owner attention floor is applied by Store. */
    public static final long HUMAN_PACING_FLOOR_MILLIS=Duration.ofMinutes(3).toMillis();
    private static final double PRIOR_REMEMBERS=3.0;
    private static final double PRIOR_REPEATS=2.0;
    private static final double MIN_SUCCESS_PROBABILITY=0.20;
    private static final double MAX_SUCCESS_PROBABILITY=0.95;
    private static final double DEADLINE_UNCERTAINTY_RESERVE=1.30;

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
        /** contacts = successful spaced "remember" responses for the current meaning revision. */
        public int contacts, weakDebt, attempts;
        public Instant last, eligible;
        public String lastReaction = "";
        public long latencyMillis;
        public State copy(){State s=new State();s.contacts=contacts;s.weakDebt=weakDebt;s.attempts=attempts;s.last=last;s.eligible=eligible;s.lastReaction=lastReaction;s.latencyMillis=latencyMillis;return s;}
    }
    /** Daily device-local gate for NEW presentations; start may be later than end to cross midnight. */
    public static final class Window {
        public final LocalTime start,end;public final ZoneId zone;
        public Window(LocalTime start,LocalTime end,ZoneId zone){if(start.equals(end))throw new IllegalArgumentException("window cannot cover zero/full day");this.start=start;this.end=end;this.zone=zone;}
        private boolean overnight(){return start.isAfter(end);}
        public boolean allowed(Instant time){LocalTime t=time.atZone(zone).toLocalTime();return overnight()?(!t.isBefore(start)||t.isBefore(end)):(!t.isBefore(start)&&t.isBefore(end));}
        public Instant next(Instant time){ZonedDateTime z=time.atZone(zone);LocalTime t=z.toLocalTime();LocalDate d=z.toLocalDate();if(allowed(time))return time;if(overnight())return d.atTime(start).atZone(zone).toInstant();if(t.isBefore(start))return d.atTime(start).atZone(zone).toInstant();return d.plusDays(1).atTime(start).atZone(zone).toInstant();}
        public Instant close(Instant time){ZonedDateTime z=next(time).atZone(zone);LocalDate d=z.toLocalDate();LocalTime t=z.toLocalTime();if(overnight()&&!t.isBefore(start))d=d.plusDays(1);return d.atTime(end).atZone(zone).toInstant();}
        public long available(Instant from,Instant to){if(!to.isAfter(from))return 0;long total=0;LocalDate first=from.atZone(zone).toLocalDate().minusDays(1),last=to.atZone(zone).toLocalDate();for(LocalDate d=first;!d.isAfter(last);d=d.plusDays(1)){Instant a=d.atTime(start).atZone(zone).toInstant(),b=(overnight()?d.plusDays(1):d).atTime(end).atZone(zone).toInstant();if(a.isBefore(from))a=from;if(b.isAfter(to))b=to;if(b.isAfter(a))total=Math.addExact(total,Duration.between(a,b).toMillis());}return total;}
        public Instant add(Instant from,long activeMillis){Instant cursor=next(from);long left=Math.max(0,activeMillis);while(true){long room=Duration.between(cursor,close(cursor)).toMillis();if(left<room)return cursor.plusMillis(left);left-=room;cursor=next(close(cursor));if(left==0)return cursor;}}
    }
    public static final class Load {
        public Instant deadline;
        /** Successful remembers still required by this deadline prefix. */
        public int cumulative;
        public long availableMillis;
        /** Smoothed attempt workload, including observed repeat behavior and uncertainty reserve. */
        public double estimatedAttempts;
        public Double requiredPerAllowedHour;
    }
    public static final class Decision {
        public String cardKey;
        public Instant due;
        public int remaining,expired,withoutPlan;
        public long spacingMillis;
        public double estimatedRemainingAttempts;
        public String feasibility="preliminary_no_response_history";
        public final List<Load> loads=new ArrayList<>();
    }

    private static final class Item {
        Card c; Plan p; State s; int n; Instant eligible;
        Item(Card c,Plan p,State s,int n,Instant eligible){this.c=c;this.p=p;this.s=s;this.n=n;this.eligible=eligible;}
    }
    private static final class Lane {
        String deck; Plan p; final List<Item> items=new ArrayList<>();
        int remaining, evidenceAttempts, evidenceRemembers;
        double successProbability, estimatedAttempts, ratePerAllowedMillis;
        Instant lastService, earliestEligible;
    }
    private static final class Evidence {int attempts,remembers;}

    /**
     * Global policy:
     * 1) each deck is a persistent ring: among eligible cards, least-recently-served wins;
     * 2) "repeat" never moves a card to the front: it updates last/eligibility and therefore goes to the tail;
     * 3) decks earn turns continuously at a rate derived from remaining workload and deadline pressure;
     * 4) observed repeats increase estimated workload for the deck, not priority of the same card.
     */
    public static Decision next(List<Card> cards,List<Plan> plans,Map<String,State> states,Instant now,Window w,List<Long> responseLatencies){
        Decision result=new Decision();
        Map<String,Plan> byDeck=new HashMap<>();for(Plan p:plans)if(p.active)byDeck.put(p.deck,p);
        Map<String,List<Item>> grouped=new TreeMap<>();Map<String,Evidence> evidence=new HashMap<>();
        for(Card c:cards){
            if(!c.active)continue;Plan p=byDeck.get(c.deck);if(p==null){result.withoutPlan++;continue;}
            State s=states.getOrDefault(c.learningKey(),new State());Evidence e=evidence.computeIfAbsent(c.deck,k->new Evidence());
            e.remembers+=Math.max(0,s.contacts);e.attempts+=Math.max(Math.max(0,s.attempts),Math.max(0,s.contacts)+Math.max(0,s.weakDebt));
            int n=Math.max(0,p.minimum-s.contacts);if(n==0)continue;result.remaining+=n;if(p.deadline!=null&&!p.deadline.isAfter(now))result.expired+=n;
            Instant eligible=w.next(s.eligible==null||s.eligible.isBefore(now)?now:s.eligible);
            grouped.computeIfAbsent(c.deck,k->new ArrayList<>()).add(new Item(c,p,s,n,eligible));
        }
        if(grouped.isEmpty())return result;

        List<Lane> lanes=new ArrayList<>();
        for(Map.Entry<String,List<Item>> entry:grouped.entrySet()){
            Lane lane=new Lane();lane.deck=entry.getKey();lane.items.addAll(entry.getValue());lane.p=lane.items.get(0).p;
            Evidence ev=evidence.getOrDefault(lane.deck,new Evidence());lane.evidenceAttempts=ev.attempts;lane.evidenceRemembers=ev.remembers;
            for(Item x:lane.items){lane.remaining+=x.n;if(lane.lastService==null||(x.s.last!=null&&x.s.last.isAfter(lane.lastService)))lane.lastService=x.s.last;if(lane.earliestEligible==null||x.eligible.isBefore(lane.earliestEligible))lane.earliestEligible=x.eligible;}
            lane.successProbability=clamp((lane.evidenceRemembers+PRIOR_REMEMBERS)/(lane.evidenceAttempts+PRIOR_REMEMBERS+PRIOR_REPEATS),MIN_SUCCESS_PROBABILITY,MAX_SUCCESS_PROBABILITY);
            lane.estimatedAttempts=(lane.remaining/lane.successProbability)*DEADLINE_UNCERTAINTY_RESERVE;result.estimatedRemainingAttempts+=lane.estimatedAttempts;
            long horizonMillis;
            if(lane.p.deadline!=null&&lane.p.deadline.isAfter(now))horizonMillis=w.available(now,lane.p.deadline);
            else if(lane.p.deadline!=null)horizonMillis=w.available(now,now.plus(Duration.ofDays(1)));
            else horizonMillis=w.available(now,now.plus(Duration.ofDays(7)));
            lane.ratePerAllowedMillis=lane.estimatedAttempts/Math.max(1.0,horizonMillis);lanes.add(lane);
        }

        // Deadline-prefix capacity: later decks may use slack, but the scheduler knows how much total
        // attempt work must fit before every earlier deadline. This drives the global contact cadence.
        List<Instant> deadlines=new ArrayList<>();for(Lane lane:lanes)if(lane.p.deadline!=null&&lane.p.deadline.isAfter(now)&&!deadlines.contains(lane.p.deadline))deadlines.add(lane.p.deadline);Collections.sort(deadlines);
        double interval=Double.POSITIVE_INFINITY;
        for(Instant deadline:deadlines){
            int successes=0;double attempts=0;for(Lane lane:lanes)if(lane.p.deadline!=null&&!lane.p.deadline.isAfter(deadline)){successes+=lane.remaining;attempts+=lane.estimatedAttempts;}
            Load load=new Load();load.deadline=deadline;load.cumulative=successes;load.estimatedAttempts=attempts;load.availableMillis=w.available(now,deadline);load.requiredPerAllowedHour=load.availableMillis==0?null:attempts*3600000.0/load.availableMillis;result.loads.add(load);
            if(attempts>0&&load.availableMillis>0)interval=Math.min(interval,load.availableMillis/(attempts+1.0));
        }
        if(!Double.isInfinite(interval))result.spacingMillis=Math.max(1,(long)Math.floor(interval));
        else result.spacingMillis=0;

        if(responseLatencies.size()>=5){
            List<Long> sorted=new ArrayList<>(responseLatencies);Collections.sort(sorted);long median=sorted.get(sorted.size()/2);result.feasibility="observed_latency_and_response_rate_no_guarantee";
            for(Load load:result.loads)if((double)median*load.estimatedAttempts>load.availableMillis)result.feasibility="risk_from_observed_response_latency";
        }
        if(result.expired>0)result.feasibility=result.feasibility.startsWith("risk_")?"deadline_missed_and_observed_risk":"deadline_missed_continue_learning";

        Instant slot=result.spacingMillis>0?w.add(now,result.spacingMillis):w.next(now);
        List<Lane> ready=readyLanes(lanes,slot);
        if(ready.isEmpty()){
            slot=null;for(Lane lane:lanes)if(lane.earliestEligible!=null&&(slot==null||lane.earliestEligible.isBefore(slot)))slot=lane.earliestEligible;
            if(slot==null)return result;slot=w.next(slot);ready=readyLanes(lanes,slot);
        }
        if(ready.isEmpty())return result;

        long bootstrap=Math.max(1,result.spacingMillis>0?result.spacingMillis:Duration.ofMinutes(15).toMillis());
        Lane selected=null;double selectedScore=Double.NEGATIVE_INFINITY;
        for(Lane lane:ready){
            long age=lane.lastService==null?bootstrap:w.available(lane.lastService,slot);double score=lane.ratePerAllowedMillis*Math.max(1,age);
            if(selected==null||score>selectedScore+1e-12||(Math.abs(score-selectedScore)<=1e-12&&laneTie(lane,selected,slot)<0)){selected=lane;selectedScore=score;}
        }
        Item item=selectRingItem(selected,slot);if(item==null)return result;
        result.cardKey=item.c.key();result.due=item.eligible.isAfter(slot)?item.eligible:slot;return result;
    }

    private static List<Lane> readyLanes(List<Lane> lanes,Instant slot){List<Lane> out=new ArrayList<>();for(Lane lane:lanes)for(Item x:lane.items)if(!x.eligible.isAfter(slot)){out.add(lane);break;}return out;}
    private static Item selectRingItem(Lane lane,Instant slot){Item best=null;for(Item x:lane.items){if(x.eligible.isAfter(slot))continue;if(best==null||ringPriority(x,best)<0)best=x;}return best;}
    private static int ringPriority(Item a,Item b){
        if(a.s.last==null&&b.s.last!=null)return -1;if(a.s.last!=null&&b.s.last==null)return 1;
        if(a.s.last!=null){int d=a.s.last.compareTo(b.s.last);if(d!=0)return d;}
        int d=Double.compare(a.s.contacts/(double)Math.max(1,a.p.minimum),b.s.contacts/(double)Math.max(1,b.p.minimum));if(d!=0)return d;
        return Long.compareUnsigned(stableHash(a.c.key()),stableHash(b.c.key()));
    }
    private static int laneTie(Lane a,Lane b,Instant slot){
        int ar=deadlineRank(a.p.deadline,slot),br=deadlineRank(b.p.deadline,slot);if(ar!=br)return Integer.compare(ar,br);
        if(a.p.deadline!=null&&b.p.deadline!=null){int d=a.p.deadline.compareTo(b.p.deadline);if(d!=0)return d;}
        return Long.compareUnsigned(stableHash(a.deck),stableHash(b.deck));
    }
    private static int deadlineRank(Instant deadline,Instant now){if(deadline==null)return 2;return deadline.isAfter(now)?0:1;}
    private static double clamp(double value,double lo,double hi){return Math.max(lo,Math.min(hi,value));}
    private static long stableHash(String value){long h=0xcbf29ce484222325L;for(int i=0;i<value.length();i++){h^=value.charAt(i);h*=0x100000001b3L;}return h;}

    public static State retarget(State original,Plan p,Window w){State s=original.copy();if(s.last==null)return s;if(s.contacts>=p.minimum){s.eligible=null;return s;}if(p.deadline==null||!p.deadline.isAfter(s.last)){s.eligible=undatedEligible(s.last,s.contacts,s.lastReaction,s.weakDebt,w);return s;}int left=Math.max(0,p.minimum-s.contacts);double fraction=s.lastReaction.equals("repeat")?0.25:0.55;long gap=Math.max(1,(long)(w.available(s.last,p.deadline)/(double)(Math.max(1,left)+1)*fraction));s.eligible=w.add(s.last,gap);return s;}
    public static State answer(State old,Plan p,String reaction,Instant shown,Instant answered,Window w){
        if(!reaction.equals("remember")&&!reaction.equals("repeat"))throw new IllegalArgumentException("reaction");if(answered.isBefore(shown))throw new IllegalArgumentException("clock moved backwards");State s=old.copy();if(old.eligible!=null&&shown.isBefore(old.eligible))return s;
        s.attempts++;
        if(reaction.equals("remember")){s.contacts++;s.weakDebt=Math.max(0,s.weakDebt-1);}else s.weakDebt=Math.min(1000,s.weakDebt+1);s.last=answered;s.lastReaction=reaction;s.latencyMillis=Duration.between(shown,answered).toMillis();
        if(s.contacts>=p.minimum){s.eligible=null;return s;}if(p.deadline==null||!p.deadline.isAfter(answered)){s.eligible=undatedEligible(answered,s.contacts,reaction,s.weakDebt,w);return s;}
        int left=Math.max(0,p.minimum-s.contacts);long horizon=w.available(answered,p.deadline);double fraction=reaction.equals("repeat")?0.25:0.55;long gap=Math.max(1,(long)(horizon/(double)(Math.max(1,left)+1)*fraction));s.eligible=w.add(answered,gap);return s;
    }
    private static Instant undatedEligible(Instant from,int successes,String reaction,int weakDebt,Window w){Duration gap;if(reaction.equals("repeat")){long minutes=Math.max(15,60/Math.max(1,Math.min(4,weakDebt)));gap=Duration.ofMinutes(minutes);}else gap=switch(successes){case 0->Duration.ZERO;case 1->Duration.ofHours(4);case 2->Duration.ofDays(1);case 3->Duration.ofDays(3);default->Duration.ofDays(7);};return w.next(from.plus(gap));}
}
