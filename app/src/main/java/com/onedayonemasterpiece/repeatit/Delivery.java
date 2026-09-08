package com.onedayonemasterpiece.repeatit;

import android.app.*;
import android.content.*;
import android.os.*;
import com.onedayonemasterpiece.repeatit.core.Engine;
import java.time.*;

/** Autonomous delivery. There is no separate hidden "enabled" gate: only an explicit user pause stops work. */
public final class Delivery {
    private Delivery(){}
    public static final String CHANGED="com.onedayonemasterpiece.repeatit.CHANGED";
    /** Conditional watchdog only while one unresolved pending exists. It is non-wakeup and never creates another card. */
    static final long PENDING_RECOVERY_MS=Duration.ofMinutes(5).toMillis();
    public static PendingIntent alarmIntent(Context c){return PendingIntent.getBroadcast(c,41,new Intent(c,RecoveryReceiver.class).setAction("com.onedayonemasterpiece.repeatit.DUE"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}

    /** Pure scheduling decision used by instrumentation tests as well as AlarmManager. */
    static Instant recoveryAt(Store s,Instant now){
        if(s.value("paused","false").equals("true"))return null;
        Engine.Decision d=s.decision();Store.Pending pending=s.pending();
        if(pending!=null){
            // A shown pending may be restored at any hour: it is the same learning contact, not a new presentation.
            if(!s.mode().equals("normal")||pending.shown>0)return now.plusMillis(PENDING_RECOVERY_MS);
            Engine.Window w=s.window();
            // An unshown normal pending still obeys the new-presentation window. Inside the window retry soon;
            // if the retry would cross the close boundary, wait for the next allowed morning instead.
            if(!w.allowed(now))return w.next(now);
            Instant retry=now.plusMillis(PENDING_RECOVERY_MS);return w.allowed(retry)?retry:w.next(retry);
        }
        if(!s.mode().equals("normal"))return d.due!=null&&d.due.isAfter(now)?d.due:null;
        Engine.Window w=s.window();
        if(w.allowed(now)){
            Instant at=w.close(now);
            if(d.due!=null&&d.due.isAfter(now)&&d.due.isBefore(at))at=d.due;
            return at;
        }
        return w.next(now);
    }

    public static void arm(Context c){
        Store s=Store.get(c);AlarmManager alarms=c.getSystemService(AlarmManager.class);PendingIntent intent=alarmIntent(c);alarms.cancel(intent);
        Instant now=Instant.now(),at=recoveryAt(s,now);if(at==null){s.put("alarm_reason","none");s.put("pending_retry_at","");return;}
        boolean exact=Build.VERSION.SDK_INT<31||alarms.canScheduleExactAlarms();
        if(exact)alarms.setExact(AlarmManager.RTC,at.toEpochMilli(),intent);else alarms.set(AlarmManager.RTC,at.toEpochMilli(),intent);
        Store.Pending pending=s.pending();String reason=pending!=null?"pending_recovery":(s.mode().equals("normal")?"normal_due_or_window":"test_due");
        s.put("alarm_reason",reason);s.put("pending_retry_at",pending==null?"":at.toString());s.put("alarm_precision",exact?"exact_non_wakeup":"inexact_non_wakeup");
    }
    public static void start(Context c){
        Store s=Store.get(c);
        if(s.value("paused","false").equals("true")){c.stopService(new Intent(c,OverlayService.class));return;}
        try{c.startForegroundService(new Intent(c,OverlayService.class));s.put("delivery_error","");}
        catch(IllegalStateException|SecurityException e){s.put("delivery_error","Android запретил фоновый запуск; recovery alarm сохранён: "+e.getClass().getSimpleName());arm(c);}
    }
}
