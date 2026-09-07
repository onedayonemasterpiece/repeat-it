package com.onedayonemasterpiece.repeatit;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.core.content.ContextCompat;
import com.onedayonemasterpiece.repeatit.core.Engine;
import java.io.File;
import java.time.*;

public final class OverlayService extends Service {
    private Store store;private WindowManager wm;private View panel;private String visibleId="";
    static volatile int rememberX,rememberY,repeatX,repeatY;
    private final Handler handler=new Handler(Looper.getMainLooper());private final Runnable boundary=this::tick;
    private final BroadcastReceiver changes=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){tick();}};
    public static final String SILENT="cards-silent-v1",SOUND="cards-chime-v1",SERVICE="service-v1";
    private void channels(){
        NotificationManager n=getSystemService(NotificationManager.class);NotificationChannel silent=new NotificationChannel(SILENT,"Карточки без звука",NotificationManager.IMPORTANCE_LOW);silent.setSound(null,null);silent.enableVibration(false);n.createNotificationChannel(silent);
        NotificationChannel sound=new NotificationChannel(SOUND,"Карточки со звуком",NotificationManager.IMPORTANCE_DEFAULT);sound.enableVibration(false);sound.setSound(Uri.parse("android.resource://"+getPackageName()+"/raw/chime"),new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());n.createNotificationChannel(sound);
        NotificationChannel service=new NotificationChannel(SERVICE,"Работа повторения",NotificationManager.IMPORTANCE_LOW);service.setSound(null,null);service.enableVibration(false);n.createNotificationChannel(service);
    }
    private PendingIntent open(){return PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    @Override public void onCreate(){
        super.onCreate();store=Store.get(this);wm=getSystemService(WindowManager.class);channels();
        Notification notification=new Notification.Builder(this,SERVICE).setSmallIcon(R.drawable.ic_repeat).setContentTitle("Повторение включено").setContentText("Новые карточки 07:40–00:30; уже показанная ждёт ответа").setOngoing(true).setContentIntent(open()).build();
        if(Build.VERSION.SDK_INT>=34)startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(1,notification);
        IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);filter.addAction(Intent.ACTION_TIME_CHANGED);filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);filter.addAction(Delivery.CHANGED);ContextCompat.registerReceiver(this,changes,filter,ContextCompat.RECEIVER_NOT_EXPORTED);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){tick();return START_STICKY;}
    @Override public IBinder onBind(Intent i){return null;}
    private boolean active(){return getSystemService(PowerManager.class).isInteractive();}
    private boolean locked(){return getSystemService(KeyguardManager.class).isDeviceLocked();}
    private void cancelContent(){String tag=store.value("notification_tag","");if(!tag.isEmpty())getSystemService(NotificationManager.class).cancel(tag,2);}
    private void hide(){if(panel!=null){try{wm.removeView(panel);}catch(IllegalArgumentException ignored){}panel=null;visibleId="";}rememberX=rememberY=repeatX=repeatY=0;}
    private boolean previewAllowed(Store.Pending p){if(!p.card.preview)return false;for(Engine.Card c:store.cards())if(c.key().equals(p.card.key()))return c.preview&&c.active;return false;}
    /** A shown pending card stays available after the new-presentation gate closes. */
    private void content(Store.Pending p,boolean signal){
        if(!active())return;if(locked()&&!previewAllowed(p)){cancelContent();return;}NotificationManager manager=getSystemService(NotificationManager.class);if(!manager.areNotificationsEnabled())return;
        AudioManager audio=getSystemService(AudioManager.class);signal=signal&&!locked()&&audio.getRingerMode()==AudioManager.RINGER_MODE_NORMAL&&manager.getCurrentInterruptionFilter()==NotificationManager.INTERRUPTION_FILTER_ALL;
        String old=store.value("notification_tag","");if(!old.equals(p.id))cancelContent();Notification.Builder b=new Notification.Builder(this,signal?SOUND:SILENT).setSmallIcon(R.drawable.ic_repeat).setContentTitle(p.card.title).setContentText(p.card.text).setStyle(new Notification.BigTextStyle().bigText(p.card.text)).setContentIntent(open()).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true);
        manager.notify(p.id,2,b.build());store.put("notification_tag",p.id);
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String content,int sp,int color){TextView t=new TextView(this);t.setText(content);t.setTextSize(sp);t.setTextColor(color);t.setPadding(dp(8),dp(5),dp(8),dp(5));t.setTextIsSelectable(false);return t;}
    private void publishControls(View root,Button remember,Button repeat){root.post(()->{if(panel!=root)return;int[] a=new int[2],b=new int[2];remember.getLocationOnScreen(a);repeat.getLocationOnScreen(b);rememberX=a[0]+remember.getWidth()/2;rememberY=a[1]+remember.getHeight()/2;repeatX=b[0]+repeat.getWidth()/2;repeatY=b[1]+repeat.getHeight()/2;});}
    private void show(Store.Pending p){
        if(panel!=null&&visibleId.equals(p.id))return;if(locked()||!active()||!Settings.canDrawOverlays(this))return;hide();boolean dark=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;int fg=dark?0xfff5f3ef:0xff24272c,bg=dark?0xff24272c:0xfff5f3ef;
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(12),dp(16),dp(12));GradientDrawable backdrop=new GradientDrawable();backdrop.setColor(bg);backdrop.setCornerRadius(dp(24));root.setBackground(backdrop);root.setElevation(dp(10));
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);TextView label=text("ПОВТОРЕНИЕ",13,fg);toolbar.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        Button bell=new Button(this);bell.setText(store.sound()?"🔔":"🔕");bell.setTextColor(fg);bell.setTextSize(24);bell.setMinWidth(0);bell.setMinimumWidth(0);bell.setPadding(0,0,0,0);GradientDrawable circle=new GradientDrawable();circle.setShape(GradientDrawable.OVAL);circle.setColor(dark?0xff3d434c:0xffe2e5e8);bell.setBackground(circle);bell.setContentDescription(store.sound()?"Выключить звук следующих карточек":"Включить звук следующих карточек");bell.setOnClickListener(v->{store.put("sound",String.valueOf(!store.sound()));bell.setText(store.sound()?"🔔":"🔕");bell.setContentDescription(store.sound()?"Выключить звук следующих карточек":"Включить звук следующих карточек");});toolbar.addView(bell,new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(toolbar);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.addView(text(p.card.title,28,fg));body.addView(text(p.card.text,24,fg));
        if(!p.card.image.isEmpty()){
            File image=GitHubSync.imageFile(this,p.card.imageHash);if(image.exists()){BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(image.getPath(),options);int sample=1;while(Math.max(options.outWidth,options.outHeight)/sample>1600)sample*=2;options.inSampleSize=sample;options.inJustDecodeBounds=false;Bitmap bitmap=BitmapFactory.decodeFile(image.getPath(),options);if(bitmap!=null){ImageView iv=new ImageView(this);iv.setAdjustViewBounds(true);iv.setImageBitmap(bitmap);iv.setContentDescription(p.card.alt);body.addView(iv,new LinearLayout.LayoutParams(-1,-2));}}else body.addView(text(p.card.alt+" · изображение пока недоступно офлайн",16,fg));body.addView(text(p.card.caption,16,fg));
        }
        scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));LinearLayout reactions=new LinearLayout(this);reactions.setOrientation(LinearLayout.HORIZONTAL);
        Button remember=new Button(this);remember.setText("Помню");remember.setTextSize(20);remember.setMinHeight(dp(60));remember.setContentDescription("repeat-it-remember");remember.setOnClickListener(v->{remember.setEnabled(false);if(!locked()&&store.answer(p.id,"remember")){hide();cancelContent();tick();}else remember.setEnabled(true);});
        Button repeat=new Button(this);repeat.setText("Повторить");repeat.setTextSize(20);repeat.setMinHeight(dp(60));repeat.setContentDescription("repeat-it-repeat");repeat.setOnClickListener(v->{repeat.setEnabled(false);if(!locked()&&store.answer(p.id,"repeat")){hide();cancelContent();tick();}else repeat.setEnabled(true);});reactions.addView(remember,new LinearLayout.LayoutParams(0,-2,1));reactions.addView(repeat,new LinearLayout.LayoutParams(0,-2,1));root.addView(reactions);
        int width,height;if(Build.VERSION.SDK_INT>=30){WindowMetrics metrics=wm.getMaximumWindowMetrics();Insets insets=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());Rect bounds=metrics.getBounds();width=bounds.width()-insets.left-insets.right;height=bounds.height()-insets.top-insets.bottom;}else{android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();wm.getDefaultDisplay().getMetrics(metrics);width=metrics.widthPixels;height=metrics.heightPixels;}
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(Math.round(width*.94f),Math.round(height*.82f),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_SECURE,PixelFormat.TRANSLUCENT);params.gravity=Gravity.CENTER;
        try{wm.addView(root,params);panel=root;visibleId=p.id;store.put("overlay_geometry",params.width+"x"+params.height+"/"+width+"x"+height);publishControls(root,remember,repeat);}catch(RuntimeException e){store.put("delivery_error","overlay_failed:"+e.getClass().getSimpleName());}
    }
    private void tick(){
        handler.removeCallbacks(boundary);if(!store.value("enabled","false").equals("true")){hide();cancelContent();stopSelf();return;}Instant now=Instant.now();boolean normal=store.mode().equals("normal");boolean mayStart=!normal||store.window().allowed(now);
        if(store.value("paused","false").equals("true")||!active()||MainActivity.visible){hide();cancelContent();}
        else{
            Store.Pending p=store.pending();boolean fresh=false;if(p==null&&mayStart){fresh=true;p=store.prepare(now);}if(p==null){hide();cancelContent();}
            else{if(locked())hide();else show(p);boolean signal=store.markPresentation(p.id,panel!=null&&!locked(),fresh&&!locked()&&panel!=null);content(p,signal);}
        }
        Delivery.arm(this);Instant next;if(normal)next=store.window().allowed(now)?store.window().close(now):store.window().next(now);else next=now.plus(Duration.ofHours(6));handler.postDelayed(boundary,Math.max(1,Duration.between(now,next).toMillis()));PreviewWidget.refresh(this);
    }
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);hide();tick();}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);hide();unregisterReceiver(changes);super.onDestroy();}
}
