package com.onedayonemasterpiece.repeatit.core;

import java.time.*;
import java.util.*;

/** Deterministic, deadline-driven heuristic. No hourly/daily card budget. */
public final class Engine {
    private Engine() {}
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
        public int contacts, weakDebt;
        public Instant last, eligible;
        public String lastReaction = "";
        public long latencyMillis;
        public State copy() {
            State s = new State(); s.contacts=contacts; s.weakDebt=weakDebt; s.last=last;
            s.eligible=eligible; s.lastReaction=lastReaction; s.latencyMillis=latencyMillis; return s;
        }
    }
    public static final class Window {
        public final LocalTime start, end;
        public final ZoneId zone;
        public Window(LocalTime start, LocalTime end, ZoneId zone) {
            if (!start.isBefore(end)) throw new IllegalArgumentException("window must be same-day");
            this.start=start; this.end=end; this.zone=zone;
        }
        public boolean allowed(Instant time) {
            LocalTime t=time.atZone(zone).toLocalTime();
            return !t.isBefore(start) && t.isBefore(end);
        }
        public Instant next(Instant time) {
            ZonedDateTime z=time.atZone(zone);
            if (z.toLocalTime().isBefore(start)) return z.toLocalDate().atTime(start).atZone(zone).toInstant();
            if (!z.toLocalTime().isBefore(end)) return z.toLocalDate().plusDays(1).atTime(start).atZone(zone).toInstant();
            return time;
        }
        public Instant close(Instant time) {
            return next(time).atZone(zone).toLocalDate().atTime(end).atZone(zone).toInstant();
        }
        public long available(Instant from, Instant to) {
            if (!to.isAfter(from)) return 0;
            long total=0;
            for (LocalDate d=from.atZone(zone).toLocalDate(), last=to.atZone(zone).toLocalDate(); !d.isAfter(last); d=d.plusDays(1)) {
                Instant a=d.atTime(start).atZone(zone).toInstant(), b=d.atTime(end).atZone(zone).toInstant();
                if (a.isBefore(from)) a=from;
                if (b.isAfter(to)) b=to;
                if (b.isAfter(a)) total=Math.addExact(total, Duration.between(a,b).toMillis());
            }
            return total;
        }
        public Instant add(Instant from, long activeMillis) {
            Instant cursor=next(from);
            long left=Math.max(0, activeMillis);
            while (true) {
                long room=Duration.between(cursor, close(cursor)).toMillis();
                if (left<room) return cursor.plusMillis(left);
                left-=room;
                cursor=next(close(cursor));
                if (left==0) return cursor;
            }
        }
    }
    public static final class Load {
        public Instant deadline;
        public int cumulative;
        public long availableMillis;
        public double requiredPerAllowedHour;
    }
    public static final class Decision {
        public String cardKey;
        public Instant due;
        public int remaining, expired, withoutPlan;
        public long spacingMillis;
        public String feasibility="preliminary_no_response_history";
        public final List<Load> loads=new ArrayList<>();
    }
    private static final class Item {
        Card c; Plan p; State s; int n; Instant eligible;
        Item(Card c,Plan p,State s,int n,Instant eligible){this.c=c;this.p=p;this.s=s;this.n=n;this.eligible=eligible;}
    }
    public static Decision next(List<Card> cards, List<Plan> plans, Map<String,State> states,
                                Instant now, Window w, List<Long> responseLatencies) {
        Decision result=new Decision();
        Map<String,Plan> byDeck=new HashMap<>();
        for(Plan p:plans) if(p.active && p.deadline!=null) byDeck.put(p.deck,p);
        List<Item> items=new ArrayList<>();
        for(Card c:cards) {
            if(!c.active) continue;
            Plan p=byDeck.get(c.deck);
            if(p==null){result.withoutPlan++;continue;}
            State s=states.getOrDefault(c.learningKey(),new State());
            int n=Math.max(0,p.minimum-s.contacts)+s.weakDebt;
            if(n==0) continue;
            if(!p.deadline.isAfter(now)){result.expired+=n;continue;}
            result.remaining+=n;
            items.add(new Item(c,p,s,n,w.next(s.eligible==null||s.eligible.isBefore(now)?now:s.eligible)));
        }
        items.sort(Comparator.comparing((Item x)->x.p.deadline).thenComparing(x->x.c.key()));
        double interval=Double.POSITIVE_INFINITY;
        int count=0;
        for(int i=0;i<items.size();) {
            Instant deadline=items.get(i).p.deadline;
            do { count+=items.get(i++).n; } while(i<items.size()&&items.get(i).p.deadline.equals(deadline));
            Load l=new Load(); l.deadline=deadline; l.cumulative=count; l.availableMillis=w.available(now,deadline);
            l.requiredPerAllowedHour=l.availableMillis==0?Double.POSITIVE_INFINITY:count*3600000.0/l.availableMillis;
            result.loads.add(l);
            // +1 leaves a slot before the boundary; it is not a frequency cap.
            interval=Math.min(interval, l.availableMillis/(double)(count+1));
        }
        if(items.isEmpty()) return result;
        result.spacingMillis=Math.max(1,(long)Math.floor(interval));
        if(responseLatencies.size()>=5) {
            List<Long> sorted=new ArrayList<>(responseLatencies); Collections.sort(sorted);
            long median=sorted.get(sorted.size()/2);
            result.feasibility="observed_latency_no_guarantee";
            for(Load l:result.loads) if((double)median*l.cumulative>l.availableMillis) result.feasibility="risk_from_observed_response_latency";
        }
        Instant base=w.add(now,result.spacingMillis);
        Item selected=null; Instant bestDue=null;
        for(Item x:items) {
            Instant due=x.eligible.isAfter(base)?x.eligible:base;
            if(!due.isBefore(x.p.deadline)) {result.feasibility="insufficient_window_under_spacing_heuristic";continue;}
            // Earliest eligible deadline, then least-covered/oldest card. A blocked card never blocks other cards.
            if(selected==null || due.isBefore(bestDue) || (due.equals(bestDue) && priority(x,selected)<0)) {
                selected=x; bestDue=due;
            }
        }
        if(selected!=null){result.cardKey=selected.c.key();result.due=bestDue;}
        return result;
    }
    private static int priority(Item a,Item b) {
        int d=a.p.deadline.compareTo(b.p.deadline); if(d!=0)return d;
        d=Double.compare(a.s.contacts/(double)a.p.minimum,b.s.contacts/(double)b.p.minimum); if(d!=0)return d;
        d=Integer.compare(b.s.weakDebt,a.s.weakDebt); if(d!=0)return d;
        return a.c.key().compareTo(b.c.key());
    }
    /** Explicit deadline edits change only the derived future eligibility, never historical events. */
    public static State retarget(State original,Plan p,Window w){
        State s=original.copy();if(s.last==null)return s;
        int left=Math.max(0,p.minimum-s.contacts)+s.weakDebt;
        double fraction=s.lastReaction.equals("repeat")?0.25:0.55;
        long gap=Math.max(1,(long)(w.available(s.last,p.deadline)/(double)(Math.max(1,left)+1)*fraction));
        s.eligible=w.add(s.last,gap);return s;
    }
    /** Credit only an actual fresh presentation response, additionally checked against frozen eligibility. */
    public static State answer(State old, Plan p, String reaction, Instant shown, Instant answered, Window w) {
        if(!reaction.equals("remember")&&!reaction.equals("repeat"))throw new IllegalArgumentException("reaction");
        if(answered.isBefore(shown))throw new IllegalArgumentException("clock moved backwards");
        State s=old.copy();
        if(old.eligible!=null && shown.isBefore(old.eligible)) return s;
        s.contacts++;
        // Successful self-report pays one extra weak contact; repeat adds one, never removes the minimum.
        s.weakDebt=reaction.equals("repeat")?s.weakDebt+1:Math.max(0,s.weakDebt-1);
        s.last=answered; s.lastReaction=reaction;
        s.latencyMillis=Duration.between(shown,answered).toMillis();
        int left=Math.max(0,p.minimum-s.contacts)+s.weakDebt;
        long horizon=w.available(answered,p.deadline);
        double fraction=reaction.equals("repeat")?0.25:0.55;
        long gap=Math.max(1,(long)(horizon/(double)(Math.max(1,left)+1)*fraction));
        s.eligible=w.add(answered,gap); // frozen until a real answer, not shrunk on every clock tick
        return s;
    }
}
