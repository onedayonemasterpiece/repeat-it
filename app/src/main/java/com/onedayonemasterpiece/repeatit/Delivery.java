package com.onedayonemasterpiece.repeatit;

import android.app.*;
import android.content.*;
import android.os.*;
import com.onedayonemasterpiece.repeatit.core.Engine;
import java.time.Instant;

public final class Delivery {
    private Delivery(){}
    public static final String CHANGED="com.onedayonemasterpiece.repeatit.CHANGED";
    public static PendingIntent alarmIntent(Context c){return PendingIntent.getBroadcast(c,41,new Intent(c,RecoveryReceiver.class).setAction("com.onedayonemasterpiece.repeatit.DUE"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    public static void arm(Context c){
        Store s=Store.get(c);AlarmManager alarms=c.getSystemService(AlarmManager.class);PendingIntent intent=alarmIntent(c);alarms.cancel(intent);
        if(!s.value("enabled","false").equals("true"))return;
        Instant now=Instant.now();Engine.Window w=s.window();Instant at=w.allowed(now)?w.close(now):w.next(now);
        Engine.Decision d=s.decision();
        if(s.pending()==null&&d.due!=null&&d.due.isAfter(now)&&d.due.isBefore(at))at=d.due;
        boolean exact=Build.VERSION.SDK_INT<31||alarms.canScheduleExactAlarms();
        if(exact)alarms.setExact(AlarmManager.RTC,at.toEpochMilli(),intent);else alarms.set(AlarmManager.RTC,at.toEpochMilli(),intent);
        s.put("alarm_precision",exact?"exact_non_wakeup":"inexact_non_wakeup");
    }
    public static void start(Context c){
        Store s=Store.get(c);if(!s.value("enabled","false").equals("true"))return;
        try{c.startForegroundService(new Intent(c,OverlayService.class));s.put("delivery_error","");}
        catch(IllegalStateException|SecurityException e){s.put("delivery_error","Откройте приложение для возобновления: "+e.getClass().getSimpleName());}
    }
}
