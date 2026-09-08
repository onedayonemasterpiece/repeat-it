package com.onedayonemasterpiece.repeatit;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;

/** Binder UID gate, not an exported secret-bearing broadcast. Only USB-authorized Android shell UID 2000. */
public final class AdminProvider extends ContentProvider {
    private void shell(){if(Binder.getCallingUid()!=2000)throw new SecurityException("ADB shell only");}
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){
        shell();Context c=getContext();Store s=Store.get(c);Bundle reply=new Bundle();boolean mutating=!method.equals("status");
        if(method.equals("mode"))s.setMode(arg);
        else if(method.equals("exit_test"))s.exitTestMode();
        else if(method.equals("due"))s.forceDue();
        else if(method.equals("sync"))SyncWorker.configure(c,true);
        else if(method.equals("pause")){s.put("paused",String.valueOf("true".equals(arg)));s.put("last_ui_action","admin_pause_"+arg+"@"+Instant.now());}
        else if(method.equals("widget_verified")){s.put("widget_verified",String.valueOf("true".equals(arg)));PreviewWidget.refresh(c);}
        else if(!method.equals("status"))throw new IllegalArgumentException("unknown_command");
        Delivery.arm(c);if(mutating&&!s.value("paused","false").equals("true"))Delivery.start(c);c.sendBroadcast(new Intent(Delivery.CHANGED).setPackage(c.getPackageName()));Store.Pending pending=s.pending();com.onedayonemasterpiece.repeatit.core.Engine.Decision d=s.decision();
        Instant now=Instant.now();long pendingWait=pending==null?0:Math.max(0,now.toEpochMilli()-pending.created);long scheduledLate=d.due==null?0:Math.max(0,Duration.between(d.due,now).toMillis());
        reply.putString("mode",s.mode());reply.putBoolean("paused",s.value("paused","false").equals("true"));reply.putBoolean("overlay_visible",s.value("overlay_visible","false").equals("true"));reply.putString("last_ui_action",s.value("last_ui_action",""));reply.putString("pending_id",pending==null?"":pending.id);reply.putLong("pending_created_at",pending==null?0:pending.created);reply.putLong("pending_shown_at",pending==null?0:pending.shown);reply.putLong("pending_wait_ms",pendingWait);reply.putLong("scheduled_due_late_ms",scheduledLate);reply.putInt("cards",s.cards().size());reply.putInt("events",s.eventCount(false));reply.putInt("verified_events",s.eventCount(true));reply.putString("next_due",String.valueOf(d.due));reply.putInt("remaining",d.remaining);reply.putInt("overdue",d.expired);reply.putInt("deadline_overdue",d.expired);reply.putString("feasibility",d.feasibility);reply.putString("overlay_geometry",s.value("overlay_geometry",""));
        reply.putString("alarm_reason",s.value("alarm_reason",""));reply.putString("pending_retry_at",s.value("pending_retry_at",""));reply.putString("alarm_precision",s.value("alarm_precision",""));reply.putString("recovery_warning",s.value("recovery_warning",""));reply.putString("last_recovery_action",s.value("last_recovery_action",""));reply.putString("last_recovery_pending",s.value("last_recovery_pending",""));
        reply.putString("library_sync_state",s.value("library_sync_state",""));reply.putString("library_sync_error",s.value("library_sync_error",""));reply.putString("last_library_sync",s.value("last_library_sync",""));
        reply.putString("progress_sync_state",s.value("progress_sync_state",""));reply.putString("progress_sync_error",s.value("progress_sync_error",""));reply.putString("last_progress_sync",s.value("last_progress_sync",""));
        reply.putString("sync_error",s.value("sync_error",""));reply.putString("sync_requested_at",s.value("sync_requested_at",""));reply.putString("last_sync",s.value("last_sync",""));reply.putString("last_sync_source_sha",s.value("last_sync_source_sha",""));reply.putString("delivery_error",s.value("delivery_error",""));reply.putString("device_id",s.device());return reply;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        shell();if(!"/provision".equals(uri.getPath())||!"w".equals(mode))throw new FileNotFoundException("write-only provisioning");
        try{ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();Context c=getContext();new Thread(()->{
            try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[128];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>1024)throw new IOException("token_length");out.write(buffer,0,n);}byte[] raw=out.toByteArray();try{new TokenVault(c).save(new String(raw,StandardCharsets.UTF_8));}finally{java.util.Arrays.fill(raw,(byte)0);}Store.get(c).put("provision_status","stored");SyncWorker.configure(c,true);
            }catch(Exception e){Store.get(c).put("provision_status","failed:"+e.getClass().getSimpleName());}
        },"token-provision").start();return pipe[1];}catch(IOException e){throw new FileNotFoundException("pipe_unavailable");}
    }
    @Override public Cursor query(Uri u,String[]p,String s,String[]a,String o){shell();throw new UnsupportedOperationException();}
    @Override public String getType(Uri u){shell();return "application/octet-stream";}
    @Override public Uri insert(Uri u,ContentValues v){shell();throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[]a){shell();throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[]a){shell();throw new UnsupportedOperationException();}
}
