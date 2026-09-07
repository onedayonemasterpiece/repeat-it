package com.onedayonemasterpiece.repeatit;

import android.content.*;

/** No direct-boot data, unrestricted activity launch, force-stop workaround or polling. */
public final class RecoveryReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        Store s=Store.get(context);String action=intent.getAction();
        if(Intent.ACTION_TIME_CHANGED.equals(action)||Intent.ACTION_TIMEZONE_CHANGED.equals(action))s.recompute();
        SyncWorker.configure(context,false);Delivery.arm(context);
        Delivery.start(context);
    }
}
