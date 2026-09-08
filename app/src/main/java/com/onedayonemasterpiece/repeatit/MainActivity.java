package com.onedayonemasterpiece.repeatit;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.core.content.ContextCompat;
import com.onedayonemasterpiece.repeatit.core.Engine;
import java.time.Instant;

public final class MainActivity extends Activity {
    public static volatile boolean visible=false;

    private static final int SAGE=0xffd9e4df;
    private static final int GRAPHITE=0xff292b29;
    private static final int GRAPHITE_SOFT=0xff3b3e3a;
    private static final int PAPER=0xfff6f3ed;
    private static final int INK=0xff202220;
    private static final int MUTED=0xff6d746f;
    private static final int ORANGE=0xffef4b23;
    private static final int WHITE=0xffffffff;

    private final Typeface display=Typeface.create("sans-serif-medium",Typeface.NORMAL);
    private final Typeface body=Typeface.create("sans-serif",Typeface.NORMAL);
    private final Typeface bodyLight=Typeface.create("sans-serif-light",Typeface.NORMAL);
    private final Typeface editorial=Typeface.create("serif",Typeface.ITALIC);

    private Store store;
    private TextView stateWord,heroMeta,heroSync,pauseButton,syncButton,setupHint;
    private final BroadcastReceiver changed=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){renderStatus();}};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(SAGE);getWindow().setNavigationBarColor(SAGE);
        if(Build.VERSION.SDK_INT>=23)getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        if(Build.VERSION.SDK_INT>=26)getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        store=Store.get(this);

        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(SAGE);scroll.setClipToPadding(false);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(18),dp(22),dp(18),dp(34));

        TextView brand=micro("REPEAT IT",INK);brand.setPadding(dp(3),0,0,dp(10));page.addView(brand);
        TextView title=label("Повторение",40,INK,display);title.setLetterSpacing(-0.025f);page.addView(title);
        TextView subtitle=label("Одна карточка. Один ответ. Дальше — только после тебя.",18,GRAPHITE_SOFT,bodyLight);subtitle.setPadding(0,dp(7),0,dp(18));subtitle.setLineSpacing(0,1.12f);page.addView(subtitle);

        LinearLayout hero=surface(GRAPHITE,30);hero.setPadding(dp(20),dp(19),dp(20),dp(20));
        TextView now=micro("СЕЙЧАС",ORANGE);hero.addView(now);
        stateWord=label("",42,PAPER,display);stateWord.setLetterSpacing(-0.025f);stateWord.setPadding(0,dp(12),0,dp(7));hero.addView(stateWord);
        heroMeta=label("",19,PAPER,body);heroMeta.setLineSpacing(dp(2),1.08f);hero.addView(heroMeta);
        TextView philosophy=label("не торопим · не складываем карточки стопкой",17,0xffcbd0cb,editorial);philosophy.setPadding(0,dp(16),0,0);hero.addView(philosophy);
        page.addView(hero,blockMargins(0,0,0,dp(12)));

        LinearLayout syncSurface=surface(PAPER,26);syncSurface.setPadding(dp(18),dp(16),dp(18),dp(17));
        LinearLayout syncHeader=new LinearLayout(this);syncHeader.setOrientation(LinearLayout.HORIZONTAL);syncHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView library=micro("БИБЛИОТЕКА",MUTED);syncHeader.addView(library,new LinearLayout.LayoutParams(0,-2,1));
        heroSync=label("",14,MUTED,body);heroSync.setGravity(Gravity.END);syncHeader.addView(heroSync);syncSurface.addView(syncHeader);
        syncButton=action("Обновить карточки сейчас",ORANGE,WHITE,ORANGE,0,()->{
            store.put("last_ui_action","manual_sync_tap@"+Instant.now());SyncWorker.configure(this,true);
            syncButton.setText("Обновление запрошено  →");Toast.makeText(this,"Синхронизация запущена — окно можно закрыть",Toast.LENGTH_SHORT).show();
            syncButton.postDelayed(()->{syncButton.setText("Обновить карточки сейчас");renderStatus();},1500);
        });
        syncSurface.addView(syncButton,blockMargins(0,dp(12),0,0));
        page.addView(syncSurface,blockMargins(0,0,0,dp(12)));

        pauseButton=action("",SAGE,INK,INK,1,this::togglePause);page.addView(pauseButton,blockMargins(0,0,0,dp(12)));

        LinearLayout setup=surface(PAPER,26);setup.setPadding(dp(18),dp(17),dp(18),dp(18));
        setup.addView(micro("НАСТРОЙКА",MUTED));
        TextView setupTitle=label("Доступ и разрешения",26,INK,display);setupTitle.setPadding(0,dp(8),0,dp(5));setup.addView(setupTitle);
        setupHint=label("",16,MUTED,bodyLight);setupHint.setLineSpacing(dp(1),1.08f);setup.addView(setupHint);
        LinearLayout setupActions=new LinearLayout(this);setupActions.setOrientation(LinearLayout.HORIZONTAL);setupActions.setPadding(0,dp(13),0,0);
        TextView token=action("GitHub PAT",GRAPHITE,PAPER,GRAPHITE,0,this::showPatDialog);
        TextView permissions=action("Android",PAPER,INK,INK,1,this::showPermissions);
        setupActions.addView(token,new LinearLayout.LayoutParams(0,dp(58),1));LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,dp(58),1);right.setMargins(dp(8),0,0,0);setupActions.addView(permissions,right);setup.addView(setupActions);
        page.addView(setup,blockMargins(0,0,0,dp(14)));

        TextView diagnostics=action("Техническое состояние  ↗",SAGE,MUTED,MUTED,0,this::showDiagnostics);diagnostics.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);diagnostics.setPadding(dp(6),0,dp(6),0);page.addView(diagnostics);

        scroll.addView(page);setContentView(scroll);
        ContextCompat.registerReceiver(this,changed,new IntentFilter(Delivery.CHANGED),ContextCompat.RECEIVER_NOT_EXPORTED);
        renderStatus();
    }

    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView label(String value,int sp,int color,Typeface face){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);t.setTypeface(face);t.setIncludeFontPadding(false);return t;}
    private TextView micro(String value,int color){TextView t=label(value,11,color,display);t.setLetterSpacing(.16f);return t;}
    private LinearLayout surface(int color,int radius){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));v.setBackground(g);v.setClipToOutline(true);v.setElevation(dp(1));return v;}
    private LinearLayout.LayoutParams blockMargins(int left,int top,int right,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(left),dp(top),dp(right),dp(bottom));return p;}
    private RippleDrawable buttonBackground(int fill,int stroke,int strokeDp,int radius){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(dp(radius));if(strokeDp>0)g.setStroke(dp(strokeDp),stroke);return new RippleDrawable(ColorStateList.valueOf(0x22000000),g,null);}
    private TextView action(String text,int fill,int textColor,int stroke,int strokeDp,Runnable action){TextView v=label(text,17,textColor,display);v.setGravity(Gravity.CENTER);v.setMinHeight(dp(58));v.setPadding(dp(16),0,dp(16),0);v.setBackground(buttonBackground(fill,stroke,strokeDp,22));v.setClickable(true);v.setFocusable(true);v.setOnClickListener(x->action.run());return v;}

    private void togglePause(){
        boolean paused=!store.value("paused","false").equals("true");store.put("paused",String.valueOf(paused));store.put("last_ui_action",(paused?"pause":"resume")+"_tap@"+Instant.now());
        Delivery.start(this);Delivery.arm(this);sendBroadcast(new Intent(Delivery.CHANGED).setPackage(getPackageName()));Toast.makeText(this,paused?"Показы поставлены на паузу":"Показы продолжены",Toast.LENGTH_SHORT).show();renderStatus();
    }

    private void showPatDialog(){
        EditText entry=new EditText(this);entry.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);entry.setSingleLine();entry.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);entry.setTextSize(18);entry.setPadding(dp(14),dp(12),dp(14),dp(12));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("GitHub PAT · idea-hub")
            .setMessage("Fine-grained token: Contents read/write. Repeat It читает learning/ и пишет только собственный progress.")
            .setView(entry).setPositiveButton("Сохранить",(d,w)->{try{new TokenVault(this).save(entry.getText().toString());entry.getText().clear();store.put("last_ui_action","pat_saved@"+Instant.now());SyncWorker.configure(this,true);Toast.makeText(this,"Ключ сохранён · синхронизация запущена",Toast.LENGTH_SHORT).show();}catch(Exception e){entry.getText().clear();store.put("sync_error","token_save_failed:"+e.getClass().getSimpleName());Toast.makeText(this,"Не удалось сохранить ключ",Toast.LENGTH_LONG).show();}renderStatus();})
            .setNegativeButton("Отмена",(d,w)->entry.getText().clear()).create();dialog.show();dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void showPermissions(){
        new AlertDialog.Builder(this).setTitle("Разрешения Android").setItems(new String[]{"Поверх других приложений","Уведомления","Точный ближайший сигнал"},(dialog,index)->{
            store.put("last_ui_action","permissions_"+index+"@"+Instant.now());
            if(index==0)startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));
            if(index==1&&Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},71);
            if(index==2&&Build.VERSION.SDK_INT>=31)startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));
        }).show();
    }

    private String nextLabel(Engine.Decision d){return d.due==null?"пока не назначен":d.due.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString().replace('T',' ');}
    private String syncLabel(){String err=store.value("sync_error","");String last=store.value("last_sync","");if(!err.isEmpty())return "ОШИБКА";if(last.isEmpty())return "ЕЩЁ НЕ БЫЛО";return "АКТУАЛЬНО";}
    private String setupStatus(){String overlay=Settings.canDrawOverlays(this)?"overlay разрешён":"нужен overlay";String sync=store.value("last_sync","").isEmpty()?"GitHub ещё не синхронизирован":"GitHub синхронизирован";return sync+" · "+overlay+".";}

    private String diagnosticText(){
        Engine.Decision d=store.decision();Store.Pending p=store.pending();StringBuilder s=new StringBuilder();
        s.append("Режим: ").append(store.mode()).append("\nПауза: ").append(store.value("paused","false")).append("\n");
        s.append("Карточек: ").append(store.cards().size()).append("\nPending: ").append(p==null?"нет":p.id).append("\nOverlay visible: ").append(store.value("overlay_visible","false")).append("\n");
        s.append("Следующий срок: ").append(d.due==null?"не назначен":d.due.atZone(java.time.ZoneId.systemDefault())).append("\nОсталось успешных «Помню»: ").append(d.remaining).append("\nПросрочено: ").append(d.expired).append("\nБез плана: ").append(d.withoutPlan).append("\n");
        s.append("Ответов локально / readback: ").append(store.eventCount(false)).append(" / ").append(store.eventCount(true)).append("\nПоследнее действие: ").append(store.value("last_ui_action","ещё нет")).append("\n");
        s.append("Sync requested: ").append(store.value("sync_requested_at","ещё нет")).append("\nLast sync: ").append(store.value("last_sync","ещё нет")).append("\nidea-hub SHA: ").append(store.value("last_sync_source_sha","ещё нет")).append("\n");
        String sync=store.value("sync_error","");String delivery=store.value("delivery_error","");if(!sync.isEmpty())s.append("Sync error: ").append(sync).append("\n");if(!delivery.isEmpty())s.append("Delivery error: ").append(delivery).append("\n");
        s.append("Новые normal-показы: 07:40–00:30 · ").append(java.time.ZoneId.systemDefault()).append(". Уже показанная карточка ждёт ответа и ночью.");return s.toString();
    }

    private void showDiagnostics(){TextView copy=label(diagnosticText(),14,INK,body);copy.setPadding(dp(20),dp(6),dp(20),dp(8));copy.setTextIsSelectable(true);new AlertDialog.Builder(this).setTitle("Техническое состояние").setView(copy).setPositiveButton("Закрыть",null).show();}

    private void renderStatus(){
        if(stateWord==null)return;boolean paused=store.value("paused","false").equals("true");Engine.Decision d=store.decision();Store.Pending p=store.pending();
        stateWord.setText(paused?"ПАУЗА":"АКТИВНО");stateWord.setTextColor(paused?0xffffb29f:PAPER);
        if(p!=null)heroMeta.setText("Одна карточка уже ждёт ответа.\nСледующая не появится, пока ты её не закроешь.");
        else if(store.cards().isEmpty())heroMeta.setText("Карточек пока нет.\nОбнови библиотеку или настрой GitHub PAT.");
        else heroMeta.setText("Карточек: "+store.cards().size()+"\nСледующий показ: "+nextLabel(d));
        heroSync.setText(syncLabel());
        pauseButton.setText(paused?"Продолжить показы  →":"Поставить на паузу");
        pauseButton.setBackground(buttonBackground(paused?GRAPHITE:SAGE,paused?GRAPHITE:INK,1,22));pauseButton.setTextColor(paused?PAPER:INK);
        setupHint.setText(setupStatus());
    }

    @Override protected void onResume(){super.onResume();visible=true;SyncWorker.configure(this,true);sendBroadcast(new Intent(Delivery.CHANGED).setPackage(getPackageName()));Delivery.start(this);Delivery.arm(this);renderStatus();}
    @Override protected void onPause(){visible=false;sendBroadcast(new Intent(Delivery.CHANGED).setPackage(getPackageName()));super.onPause();}
    @Override protected void onDestroy(){unregisterReceiver(changed);super.onDestroy();}
}
