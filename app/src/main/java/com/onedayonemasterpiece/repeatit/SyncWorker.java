package com.onedayonemasterpiece.repeatit;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

public final class SyncWorker extends Worker {
    private static final Object SYNC_LOCK=new Object();
    public SyncWorker(@NonNull Context c,@NonNull WorkerParameters p){super(c,p);}
    public static void configure(Context c,boolean manual){
        WorkManager wm=WorkManager.getInstance(c);Constraints network=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        wm.enqueueUniquePeriodicWork("github-periodic",ExistingPeriodicWorkPolicy.KEEP,new PeriodicWorkRequest.Builder(SyncWorker.class,4,TimeUnit.HOURS).setConstraints(network).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build());
        if(manual){
            Store.get(c).put("sync_requested_at",Instant.now().toString());
            OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(SyncWorker.class).setConstraints(network).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();
            // A refresh requested while another is running must still get a later snapshot, not disappear behind KEEP.
            wm.enqueueUniqueWork("github-manual",ExistingWorkPolicy.APPEND_OR_REPLACE,request);
        }
    }
    private void changed(){getApplicationContext().sendBroadcast(new android.content.Intent(Delivery.CHANGED).setPackage(getApplicationContext().getPackageName()));}
    private void phaseFailure(Store store,String message){
        if("running".equals(store.value("library_sync_state",""))){store.put("library_sync_state","error");store.put("library_sync_error",message);}
        else if("running".equals(store.value("progress_sync_state",""))){store.put("progress_sync_state","error");store.put("progress_sync_error",message);}
        store.put("sync_error",message);
    }
    @NonNull @Override public Result doWork(){
        synchronized(SYNC_LOCK){Store store=Store.get(getApplicationContext());try{
            String token=new TokenVault(getApplicationContext()).read();if(token.isEmpty()){String message="PAT не настроен; локальные данные сохранены";store.put("library_sync_state","error");store.put("library_sync_error",message);store.put("sync_error",message);return Result.failure();}
            new GitHubSync(getApplicationContext(),token).run();Delivery.arm(getApplicationContext());return Result.success();
        }catch(GitHubSync.RemoteError e){phaseFailure(store,e.getMessage());if(e.status==401||e.status==403)return Result.failure();return getRunAttemptCount()<5?Result.retry():Result.failure();
        }catch(Exception e){String message="sync_failed:"+e.getClass().getSimpleName();phaseFailure(store,message);return getRunAttemptCount()<5?Result.retry():Result.failure();
        }finally{changed();}}
    }
}
