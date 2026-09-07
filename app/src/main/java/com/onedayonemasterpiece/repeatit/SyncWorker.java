package com.onedayonemasterpiece.repeatit;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.util.concurrent.TimeUnit;

public final class SyncWorker extends Worker {
    private static final Object SYNC_LOCK=new Object();
    public SyncWorker(@NonNull Context c,@NonNull WorkerParameters p){super(c,p);}
    public static void configure(Context c,boolean manual){
        WorkManager wm=WorkManager.getInstance(c);
        Constraints network=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        wm.enqueueUniquePeriodicWork("github-periodic",ExistingPeriodicWorkPolicy.KEEP,new PeriodicWorkRequest.Builder(SyncWorker.class,4,TimeUnit.HOURS).setConstraints(network).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build());
        if(manual)wm.enqueueUniqueWork("github-manual",ExistingWorkPolicy.KEEP,new OneTimeWorkRequest.Builder(SyncWorker.class).setConstraints(network).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build());
    }
    @NonNull @Override public Result doWork(){
        synchronized(SYNC_LOCK) {
            Store store=Store.get(getApplicationContext());
            try{
                String token=new TokenVault(getApplicationContext()).read();
                if(token.isEmpty()){store.put("sync_error","PAT не настроен; локальные данные сохранены");return Result.failure();}
                new GitHubSync(getApplicationContext(),token).run();
                Delivery.arm(getApplicationContext());
                getApplicationContext().sendBroadcast(new android.content.Intent(Delivery.CHANGED).setPackage(getApplicationContext().getPackageName()));
                return Result.success();
            }catch(GitHubSync.RemoteError e){
                store.put("sync_error",e.getMessage());
                if(e.status==401||e.status==403)return Result.failure();
                return getRunAttemptCount()<5?Result.retry():Result.failure();
            }catch(Exception e){store.put("sync_error","sync_failed:"+e.getClass().getSimpleName());return getRunAttemptCount()<5?Result.retry():Result.failure();}
        }
    }
}
