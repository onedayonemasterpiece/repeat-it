package com.onedayonemasterpiece.repeatit;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Binder UID gate, not an exported secret-bearing broadcast. Only USB-authorized Android shell UID 2000. */
public final class AdminProvider extends ContentProvider {
    private void shell(){if(Binder.getCallingUid()!=2000)throw new SecurityException("ADB shell only");}
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){
        shell();Context c=getContext();Store s=Store.get(c);Bundle reply=new Bundle();
        if(method.equals("mode"))s.setMode(arg);
        else if(method.equals("due"))s.forceDue();
        else if(method.equals("sync"))SyncWorker.configure(c,true);
        else if(method.equals("widget_verified")){s.put("widget_verified",String.valueOf("true".equals(arg)));PreviewWidget.refresh(c);}
        else if(!method.equals("status"))throw new IllegalArgumentException("unknown_command");
        Delivery.arm(c);c.sendBroadcast(new Intent(Delivery.CHANGED).setPackage(c.getPackageName()));
        Store.Pending pending=s.pending();reply.putString("mode",s.mode());reply.putString("pending_id",pending==null?"":pending.id);reply.putInt("cards",s.cards().size());reply.putInt("events",s.eventCount(false));reply.putInt("verified_events",s.eventCount(true));reply.putString("next_due",String.valueOf(s.decision().due));reply.putString("overlay_geometry",s.value("overlay_geometry",""));reply.putString("sync_error",s.value("sync_error",""));reply.putString("last_sync",s.value("last_sync",""));reply.putString("device_id",s.device());return reply;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        shell();if(!"/provision".equals(uri.getPath())||!"w".equals(mode))throw new FileNotFoundException("write-only provisioning");
        try{
            ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();Context c=getContext();
            new Thread(()->{
                try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[128];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>1024)throw new IOException("token_length");out.write(buffer,0,n);}
                    byte[] raw=out.toByteArray();try{new TokenVault(c).save(new String(raw,StandardCharsets.UTF_8));}finally{java.util.Arrays.fill(raw,(byte)0);}
                    Store.get(c).put("provision_status","stored");SyncWorker.configure(c,true);
                }catch(Exception e){Store.get(c).put("provision_status","failed:"+e.getClass().getSimpleName());}
            },"token-provision").start();return pipe[1];
        }catch(IOException e){throw new FileNotFoundException("pipe_unavailable");}
    }
    @Override public Cursor query(Uri u,String[]p,String s,String[]a,String o){shell();throw new UnsupportedOperationException();}
    @Override public String getType(Uri u){shell();return "application/octet-stream";}
    @Override public Uri insert(Uri u,ContentValues v){shell();throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[]a){shell();throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[]a){shell();throw new UnsupportedOperationException();}
}
