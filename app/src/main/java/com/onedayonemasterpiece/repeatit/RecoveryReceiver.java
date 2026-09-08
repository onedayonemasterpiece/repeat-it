package com.onedayonemasterpiece.repeatit;

import android.content.*;
import java.time.Instant;

/** No direct-boot data, unrestricted activity launch, force-stop workaround or polling. */
public final class RecoveryReceiver extends BroadcastReceiver {
    private static final String DUE="com.onedayonemasterpiece.repeatit.DUE";
    @Override public void onReceive(Context context,Intent intent){
        Store s=Store.get(context);String action=intent.getAction();Store.Pending pending=s.pending();
        s.put("last_recovery_action",String.valueOf(action)+"@"+Instant.now());s.put("last_recovery_pending",pending==null?"":pending.id);
        if(Intent.ACTION_TIME_CHANGED.equals(action)||Intent.ACTION_TIMEZONE_CHANGED.equals(action))s.recompute();
        if(!DUE.equals(action))SyncWorker.configure(context,false);
        // Arm the next bounded recovery before attempting the service start so a denied/failed start cannot strand a pending card.
        Delivery.arm(context);Delivery.start(context);
    }
}
