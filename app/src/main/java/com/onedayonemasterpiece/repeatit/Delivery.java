package com.onedayonemasterpiece.repeatit;

import android.app.*;
import android.content.*;
import android.os.*;
import com.onedayonemasterpiece.repeatit.core.Engine;
import java.time.Instant;

/** Autonomous delivery. There is no separate hidden "enabled" gate: only an explicit user pause stops work. */
public final class Delivery {
    private Delivery(){}
    public static final String CHANGED="com.onedayonemasterpiece.repeatit.CHANGED";
    public static PendingIntent alarmIntent(Context c){return PendingIntent.getBroadcast(c,41,new Intent(c,RecoveryReceiver.class).setAction("com.onedayonemasterpiece.repeatit.DUE"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    public static void arm(Context c){
        Store s=Store.get(c);AlarmManager alarms=c.getSystemService(AlarmManager.class);PendingIntent intent=alarmIntent(c);alarms.cancel(intent);
        if(s.value("paused","false").equals("true"))return;
        Instant now=Instant.now();Engine.Decision d=s.decision();Instant at=null;
        if(!s.mode().equals("normal")){
            // A due test alarm gets one attempt. If the device is not surface-ready, screen/app activity will retry it; never spin every second.
            if(s.pending()==null&&d.due!=null&&d.due.isAfter(now))at=d.due;
        }else{
            Engine.Window w=s.window();Store.Pending pending=s.pending();
            if(pending!=null)at=w.allowed(now)?w.close(now):w.next(now); // recovery boundary only; pending itself owns the slot
            else if(w.allowed(now)){
                at=w.close(now);
                if(d.due!=null&&d.due.isAfter(now)&&d.due.isBefore(at))at=d.due;
                // If due is already past, OverlayService gets the current attempt. Do not schedule a one-second battery loop.
            }else at=w.next(now);
        }
        if(at==null)return;boolean exact=Build.VERSION.SDK_INT<31||alarms.canScheduleExactAlarms();
        if(exact)alarms.setExact(AlarmManager.RTC,at.toEpochMilli(),intent);else alarms.set(AlarmManager.RTC,at.toEpochMilli(),intent);
        s.put("alarm_precision",exact?"exact_non_wakeup":"inexact_non_wakeup");
    }
    public static void start(Context c){
        Store s=Store.get(c);
        if(s.value("paused","false").equals("true")){c.stopService(new Intent(c,OverlayService.class));return;}
        try{c.startForegroundService(new Intent(c,OverlayService.class));s.put("delivery_error","");}
        catch(IllegalStateException|SecurityException e){s.put("delivery_error","Android запретил фоновый запуск; откройте Repeat It один раз: "+e.getClass().getSimpleName());}
    }
}
