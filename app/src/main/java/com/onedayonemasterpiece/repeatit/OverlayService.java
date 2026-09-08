package com.onedayonemasterpiece.repeatit;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
    private static final int GRAPHITE=0xff292b29;
    private static final int GRAPHITE_SOFT=0xff3a3d39;
    private static final int PAPER=0xfff6f3ed;
    private static final int MUTED=0xffc3c9c3;
    private static final int ORANGE=0xffef4b23;
    private static final int WHITE=0xffffffff;

    private final Typeface display=Typeface.create("sans-serif-medium",Typeface.NORMAL);
    private final Typeface body=Typeface.create("sans-serif",Typeface.NORMAL);
    private final Typeface editorial=Typeface.create("serif",Typeface.ITALIC);

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
        Notification notification=new Notification.Builder(this,SERVICE).setSmallIcon(R.drawable.ic_repeat).setContentTitle("Повторение активно").setContentText("Одна карточка за раз · нажмите, чтобы открыть состояние").setOngoing(true).setContentIntent(open()).build();
        if(Build.VERSION.SDK_INT>=34)startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(1,notification);
        IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);filter.addAction(Intent.ACTION_TIME_CHANGED);filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);filter.addAction(Delivery.CHANGED);ContextCompat.registerReceiver(this,changes,filter,ContextCompat.RECEIVER_NOT_EXPORTED);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){tick();return START_STICKY;}
    @Override public IBinder onBind(Intent i){return null;}
    private boolean active(){return getSystemService(PowerManager.class).isInteractive();}
    private boolean locked(){return getSystemService(KeyguardManager.class).isDeviceLocked();}
    private void cancelContent(){String tag=store.value("notification_tag","");if(!tag.isEmpty())getSystemService(NotificationManager.class).cancel(tag,2);}
    private void hide(){if(panel!=null){try{wm.removeView(panel);}catch(IllegalArgumentException ignored){}panel=null;visibleId="";}rememberX=rememberY=repeatX=repeatY=0;if(store!=null)store.put("overlay_visible","false");}
    private boolean previewAllowed(Store.Pending p){if(!p.card.preview)return false;for(Engine.Card c:store.cards())if(c.key().equals(p.card.key()))return c.preview&&c.active;return false;}
    private String sourceText(Engine.Card card,String key){if(card.source==null)return "";Object value=card.source.get(key);return value instanceof String?((String)value).trim():"";}
    private String presentation(Engine.Card card){String value=sourceText(card,"presentation");return value.isEmpty()?"thesis":value;}
    private void content(Store.Pending p,boolean signal){
        if(!active())return;if(locked()&&!previewAllowed(p)){cancelContent();return;}NotificationManager manager=getSystemService(NotificationManager.class);if(!manager.areNotificationsEnabled())return;
        AudioManager audio=getSystemService(AudioManager.class);signal=signal&&!locked()&&audio.getRingerMode()==AudioManager.RINGER_MODE_NORMAL&&manager.getCurrentInterruptionFilter()==NotificationManager.INTERRUPTION_FILTER_ALL;
        String old=store.value("notification_tag","");if(!old.equals(p.id))cancelContent();String metric=presentation(p.card).equals("metric")?sourceText(p.card,"metric"):"";String fallback=(metric.isEmpty()?"":metric+" · ")+p.card.text;Notification.Builder b=new Notification.Builder(this,signal?SOUND:SILENT).setSmallIcon(R.drawable.ic_repeat).setContentTitle(p.card.title).setContentText(fallback).setStyle(new Notification.BigTextStyle().bigText(fallback)).setContentIntent(open()).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true);manager.notify(p.id,2,b.build());store.put("notification_tag",p.id);
    }

    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView label(String content,int sp,int color,Typeface face){TextView t=new TextView(this);t.setText(content);t.setTextSize(sp);t.setTextColor(color);t.setTypeface(face);t.setIncludeFontPadding(false);t.setTextIsSelectable(false);return t;}
    private TextView micro(String content,int color){TextView t=label(content,11,color,display);t.setLetterSpacing(.15f);return t;}
    private GradientDrawable shape(int color,int radius,int stroke,int strokeWidth){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));if(strokeWidth>0)g.setStroke(dp(strokeWidth),stroke);return g;}
    private RippleDrawable ripple(int fill,int radius,int stroke,int strokeWidth){return new RippleDrawable(ColorStateList.valueOf(0x33ffffff),shape(fill,radius,stroke,strokeWidth),null);}
    private TextView action(String title,int fill,int textColor,int stroke,int strokeWidth){TextView t=label(title,19,textColor,display);t.setGravity(Gravity.CENTER);t.setMinHeight(dp(66));t.setPadding(dp(14),0,dp(14),0);t.setBackground(ripple(fill,22,stroke,strokeWidth));t.setClickable(true);t.setFocusable(true);return t;}
    private void publishControls(View root,View remember,View repeat){root.post(()->{if(panel!=root)return;int[] a=new int[2],b=new int[2];remember.getLocationOnScreen(a);repeat.getLocationOnScreen(b);rememberX=a[0]+remember.getWidth()/2;rememberY=a[1]+remember.getHeight()/2;repeatX=b[0]+repeat.getWidth()/2;repeatY=b[1]+repeat.getHeight()/2;});}
    private void react(Store.Pending p,String reaction,TextView button){
        button.setEnabled(false);button.setAlpha(.55f);store.put("last_ui_action","reaction_"+reaction+"_tap@"+Instant.now());
        if(!locked()&&store.answer(p.id,reaction)){
            store.put("last_ui_action","reaction_"+reaction+"_committed@"+Instant.now());Toast.makeText(this,reaction.equals("remember")?"Помню · сохранено":"Повторить · сохранено",Toast.LENGTH_SHORT).show();hide();cancelContent();tick();
        }else{
            store.put("last_ui_action","reaction_"+reaction+"_rejected@"+Instant.now());button.setEnabled(true);button.setAlpha(1f);Toast.makeText(this,"Не удалось сохранить ответ — карточка остаётся открытой",Toast.LENGTH_LONG).show();
        }
    }
    private int bodySize(String text){int n=text==null?0:text.length();if(n>360)return 23;if(n>260)return 25;if(n>180)return 27;if(n>110)return 30;return 33;}
    private int metricSize(String metric){int n=metric==null?0:metric.length();if(n<=4)return 96;if(n<=8)return 84;if(n<=12)return 72;return 60;}
    private void addDetail(LinearLayout panel,Engine.Card card){String detail=sourceText(card,"detail");if(detail.isEmpty())return;TextView note=label(detail,16,MUTED,editorial);note.setLineSpacing(dp(2),1.08f);note.setPadding(0,dp(18),0,0);panel.addView(note);}
    private void addImage(LinearLayout panel,Engine.Card card,boolean hero){
        if(card.image.isEmpty()){
            if(hero){TextView missing=label(card.alt.isEmpty()?"Изображение пока недоступно офлайн":card.alt+" · изображение пока недоступно офлайн",14,MUTED,body);missing.setPadding(0,dp(16),0,0);panel.addView(missing);}return;
        }
        File image=GitHubSync.imageFile(this,card.imageHash);if(!image.exists()){if(hero){TextView missing=label(card.alt+" · изображение пока недоступно офлайн",14,MUTED,body);missing.setPadding(0,dp(16),0,0);panel.addView(missing);}return;}
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(image.getPath(),options);int sample=1;while(Math.max(options.outWidth,options.outHeight)/sample>1600)sample*=2;options.inSampleSize=sample;options.inJustDecodeBounds=false;Bitmap bitmap=BitmapFactory.decodeFile(image.getPath(),options);if(bitmap==null)return;
        ImageView iv=new ImageView(this);iv.setImageBitmap(bitmap);iv.setContentDescription(card.alt);iv.setBackground(shape(PAPER,20,0,0));iv.setClipToOutline(true);LinearLayout.LayoutParams ip;if(hero){iv.setScaleType(ImageView.ScaleType.CENTER_CROP);ip=new LinearLayout.LayoutParams(-1,dp(285));}else{iv.setAdjustViewBounds(true);iv.setScaleType(ImageView.ScaleType.CENTER_CROP);ip=new LinearLayout.LayoutParams(-1,-2);}ip.setMargins(0,dp(18),0,0);panel.addView(iv,ip);
        if(!card.caption.isEmpty()){TextView caption=label(card.caption,14,MUTED,editorial);caption.setPadding(0,dp(9),0,0);panel.addView(caption);}
    }

    private void show(Store.Pending p){
        if(panel!=null&&visibleId.equals(p.id))return;if(locked()||!active()||!Settings.canDrawOverlays(this))return;hide();

        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(19),dp(17),dp(19),dp(17));root.setBackground(shape(GRAPHITE,30,0,0));root.setElevation(dp(12));

        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setOrientation(LinearLayout.HORIZONTAL);
        TextView badge=micro("СЕЙЧАС",GRAPHITE);badge.setGravity(Gravity.CENTER);badge.setBackground(shape(ORANGE,14,0,0));badge.setPadding(dp(11),dp(7),dp(11),dp(7));toolbar.addView(badge);
        TextView brand=micro("REPEAT IT",MUTED);brand.setPadding(dp(12),0,0,0);toolbar.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView sound=label(store.sound()?"♪":"×",22,store.sound()?ORANGE:PAPER,display);sound.setGravity(Gravity.CENTER);sound.setBackground(ripple(GRAPHITE_SOFT,22,ORANGE,1));sound.setMinWidth(dp(46));sound.setMinHeight(dp(46));sound.setContentDescription(store.sound()?"Выключить звук следующих карточек":"Включить звук следующих карточек");sound.setClickable(true);sound.setFocusable(true);sound.setOnClickListener(v->{boolean next=!store.sound();store.put("sound",String.valueOf(next));store.put("last_ui_action","sound_"+(next?"on":"off")+"@"+Instant.now());sound.setText(next?"♪":"×");sound.setTextColor(next?ORANGE:PAPER);sound.setContentDescription(next?"Выключить звук следующих карточек":"Включить звук следующих карточек");Toast.makeText(this,next?"Звук следующих карточек включён":"Звук следующих карточек выключен",Toast.LENGTH_SHORT).show();});toolbar.addView(sound,new LinearLayout.LayoutParams(dp(46),dp(46)));root.addView(toolbar);

        View accent=new View(this);accent.setBackground(shape(ORANGE,3,0,0));LinearLayout.LayoutParams accentP=new LinearLayout.LayoutParams(dp(54),dp(4));accentP.setMargins(0,dp(18),0,dp(16));root.addView(accent,accentP);

        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);
        LinearLayout bodyPanel=new LinearLayout(this);bodyPanel.setOrientation(LinearLayout.VERTICAL);bodyPanel.setPadding(dp(18),dp(18),dp(18),dp(20));bodyPanel.setBackground(shape(0xff333632,24,0,0));
        TextView title=label(p.card.title,17,ORANGE,display);title.setLetterSpacing(.02f);title.setLineSpacing(0,1.05f);bodyPanel.addView(title);

        String type=presentation(p.card);
        if(type.equals("metric")){
            String metric=sourceText(p.card,"metric");TextView number=label(metric,metricSize(metric),PAPER,display);number.setLetterSpacing(-.025f);number.setPadding(0,dp(14),0,0);bodyPanel.addView(number);
            TextView thesis=label(p.card.text,Math.min(28,bodySize(p.card.text)),PAPER,display);thesis.setLetterSpacing(-.01f);thesis.setLineSpacing(dp(2),1.05f);thesis.setPadding(0,dp(8),0,0);bodyPanel.addView(thesis);addDetail(bodyPanel,p.card);
        }else if(type.equals("image")){
            addImage(bodyPanel,p.card,true);TextView thesis=label(p.card.text,Math.min(27,bodySize(p.card.text)),PAPER,display);thesis.setLetterSpacing(-.01f);thesis.setLineSpacing(dp(2),1.05f);thesis.setPadding(0,dp(16),0,0);bodyPanel.addView(thesis);addDetail(bodyPanel,p.card);
        }else{
            TextView thesis=label(p.card.text,bodySize(p.card.text),PAPER,display);thesis.setLetterSpacing(-.012f);thesis.setLineSpacing(dp(2),1.05f);thesis.setPadding(0,dp(12),0,0);if(Build.VERSION.SDK_INT>=23)thesis.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);bodyPanel.addView(thesis);addImage(bodyPanel,p.card,false);addDetail(bodyPanel,p.card);
        }
        scroll.addView(bodyPanel);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout reactions=new LinearLayout(this);reactions.setOrientation(LinearLayout.HORIZONTAL);reactions.setPadding(0,dp(14),0,0);
        TextView repeat=action("Повторить",GRAPHITE,PAPER,0xff71766f,1);repeat.setContentDescription("repeat-it-repeat");repeat.setOnClickListener(v->react(p,"repeat",repeat));
        TextView remember=action("Помню  →",ORANGE,WHITE,ORANGE,0);remember.setContentDescription("repeat-it-remember");remember.setOnClickListener(v->react(p,"remember",remember));
        reactions.addView(repeat,new LinearLayout.LayoutParams(0,dp(66),1));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,dp(66),1);rp.setMargins(dp(9),0,0,0);reactions.addView(remember,rp);root.addView(reactions);

        int width,height;if(Build.VERSION.SDK_INT>=30){WindowMetrics metrics=wm.getMaximumWindowMetrics();Insets insets=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());Rect bounds=metrics.getBounds();width=bounds.width()-insets.left-insets.right;height=bounds.height()-insets.top-insets.bottom;}else{android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();wm.getDefaultDisplay().getMetrics(metrics);width=metrics.widthPixels;height=metrics.heightPixels;}
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(Math.round(width*.94f),Math.round(height*.82f),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_SECURE,PixelFormat.TRANSLUCENT);params.gravity=Gravity.CENTER;
        try{wm.addView(root,params);panel=root;visibleId=p.id;store.put("overlay_geometry",params.width+"x"+params.height+"/"+width+"x"+height);store.put("overlay_visible","true");store.put("last_ui_action","overlay_presented@"+Instant.now());publishControls(root,remember,repeat);}catch(RuntimeException e){store.put("overlay_visible","false");store.put("delivery_error","overlay_failed:"+e.getClass().getSimpleName());}
    }

    private void tick(){
        handler.removeCallbacks(boundary);
        if(store.value("paused","false").equals("true")){hide();cancelContent();PreviewWidget.refresh(this);stopSelf();return;}
        Instant now=Instant.now();boolean normal=store.mode().equals("normal");Engine.Window w=store.window();boolean mayStart=!normal||w.allowed(now);
        if(!active()||MainActivity.visible){hide();cancelContent();}
        else{
            Store.Pending p=store.pending();boolean fresh=false;if(p==null&&mayStart){fresh=true;p=store.prepare(now);}
            if(p==null){hide();cancelContent();}
            else{
                boolean maySurface=!normal||w.allowed(now)||p.shown>0;
                if(!maySurface){hide();cancelContent();}
                else{if(locked())hide();else show(p);boolean signal=store.markPresentation(p.id,panel!=null&&!locked(),fresh&&!locked()&&panel!=null);content(p,signal);}
            }
        }
        Delivery.arm(this);Instant next;if(normal)next=w.allowed(now)?w.close(now):w.next(now);else next=now.plus(Duration.ofHours(6));handler.postDelayed(boundary,Math.max(1,Duration.between(now,next).toMillis()));PreviewWidget.refresh(this);
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);hide();tick();}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);hide();unregisterReceiver(changes);super.onDestroy();}
}
