package com.onedayonemasterpiece.repeatit;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.net.Uri;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.core.content.ContextCompat;
import com.onedayonemasterpiece.repeatit.core.Engine;
import java.time.Instant;

public final class MainActivity extends Activity {
    public static volatile boolean visible=false;
    private Store store;private TextView status;private Button pauseButton,syncButton;
    private final BroadcastReceiver changed=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){renderStatus();}};
    @Override public void onCreate(Bundle b){
        super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);store=Store.get(this);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(24,32,24,32);
        TextView title=new TextView(this);title.setText("Повторение");title.setTextSize(32);page.addView(title);
        TextView subtitle=new TextView(this);subtitle.setText("После настройки приложение работает само. Отдельного включения нет.");subtitle.setTextSize(18);subtitle.setPadding(0,8,0,16);page.addView(subtitle);
        status=new TextView(this);status.setTextSize(17);page.addView(status);
        pauseButton=button(page,"",this::togglePause);
        syncButton=button(page,"Обновить карточки сейчас",()->{
            store.put("last_ui_action","manual_sync_tap@"+Instant.now());SyncWorker.configure(this,true);syncButton.setText("Обновление запрошено…");Toast.makeText(this,"Синхронизация запущена — окно можно закрыть",Toast.LENGTH_SHORT).show();syncButton.postDelayed(()->{syncButton.setText("Обновить карточки сейчас");renderStatus();},1400);
        });
        button(page,"Разрешения Android",()->new AlertDialog.Builder(this).setTitle("Для автономного показа")
            .setItems(new String[]{"Поверх других приложений","Уведомления","Точный ближайший сигнал"},(dialog,index)->{
                store.put("last_ui_action","permissions_"+index+"@"+Instant.now());
                if(index==0)startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));
                if(index==1&&Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},71);
                if(index==2&&Build.VERSION.SDK_INT>=31)startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));
            }).show());
        button(page,"Настроить GitHub PAT",()->{
            EditText entry=new EditText(this);entry.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);entry.setSingleLine();entry.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Fine-grained PAT только для idea-hub")
                .setMessage("Contents: read/write. GitHub ограничивает ключ репозиторием, не папкой. Приложение читает learning/ и пишет только свой progress. Ключ не передаётся в журнал или резервную копию.")
                .setView(entry).setPositiveButton("Сохранить",(d,w)->{try{new TokenVault(this).save(entry.getText().toString());entry.getText().clear();store.put("last_ui_action","pat_saved@"+Instant.now());SyncWorker.configure(this,true);Toast.makeText(this,"Ключ сохранён, синхронизация запущена",Toast.LENGTH_SHORT).show();}catch(Exception e){entry.getText().clear();store.put("sync_error","token_save_failed:"+e.getClass().getSimpleName());Toast.makeText(this,"Не удалось сохранить ключ",Toast.LENGTH_LONG).show();}renderStatus();})
                .setNegativeButton("Отмена",(d,w)->entry.getText().clear()).create();dialog.show();dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        });
        ScrollView scroll=new ScrollView(this);scroll.addView(page);setContentView(scroll);ContextCompat.registerReceiver(this,changed,new IntentFilter(Delivery.CHANGED),ContextCompat.RECEIVER_NOT_EXPORTED);renderStatus();
    }
    private Button button(LinearLayout parent,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(18);b.setMinHeight(64);b.setOnClickListener(v->action.run());parent.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;}
    private void togglePause(){
        boolean paused=!store.value("paused","false").equals("true");store.put("paused",String.valueOf(paused));store.put("last_ui_action",(paused?"pause":"resume")+"_tap@"+Instant.now());
        if(paused){Delivery.start(this);Delivery.arm(this);sendBroadcast(new Intent(Delivery.CHANGED).setPackage(getPackageName()));Toast.makeText(this,"Показы поставлены на паузу",Toast.LENGTH_SHORT).show();}
        else{Delivery.start(this);Delivery.arm(this);sendBroadcast(new Intent(Delivery.CHANGED).setPackage(getPackageName()));Toast.makeText(this,"Показы продолжены",Toast.LENGTH_SHORT).show();}
        renderStatus();
    }
    private void renderStatus(){
        if(status==null)return;boolean paused=store.value("paused","false").equals("true");if(pauseButton!=null)pauseButton.setText(paused?"Продолжить показы":"Поставить на паузу");
        Engine.Decision d=store.decision();Store.Pending p=store.pending();StringBuilder s=new StringBuilder();
        s.append("Показы: ").append(paused?"ПАУЗА":"АКТИВНЫ").append(". Отдельного включения нет.\n");
        s.append("Карточек: ").append(store.cards().size()).append(". Режим: ").append(store.mode()).append("\n");
        s.append("Новые показы normal: 07:40–00:30 · ").append(java.time.ZoneId.systemDefault()).append(". Уже показанная карточка ждёт ответа и ночью.\n");
        s.append("Overlay: ").append(Settings.canDrawOverlays(this)?"разрешён":"нужно разрешение").append(". Сейчас виден: ").append(store.value("overlay_visible","false")).append("\n");
        s.append("Звук следующих карточек: ").append(store.sound()?"включён":"выключен").append("\n");
        s.append("Pending: ").append(p==null?"нет":p.id).append("\n");s.append("Следующий срок: ").append(d.due==null?"не назначен":d.due.atZone(java.time.ZoneId.systemDefault())).append("\n");
        s.append("Осталось успешных «Помню»: ").append(d.remaining).append(". Дедлайн уже прошёл: ").append(d.expired).append(". Без плана: ").append(d.withoutPlan).append("\n");
        if(d.loads!=null)for(Engine.Load load:d.loads)s.append("До ").append(load.deadline).append(": ").append(load.cumulative).append(" успешных повторений; расчётный темп ").append((load.requiredPerAllowedHour==null?"нет разрешённого времени":String.format(java.util.Locale.ROOT,"%.2f",load.requiredPerAllowedHour))).append(" за час окна новых показов.\n");
        s.append("Оценка: ").append(d.feasibility).append(". Один pending блокирует выдачу следующей карточки.\n");
        s.append("Ответов локально / readback: ").append(store.eventCount(false)).append(" / ").append(store.eventCount(true)).append("\n");
        s.append("Последнее действие: ").append(store.value("last_ui_action","ещё нет")).append("\n");
        s.append("Запрошено обновление: ").append(store.value("sync_requested_at","ещё нет")).append("\n");
        s.append("Последняя успешная синхронизация: ").append(store.value("last_sync","ещё нет")).append("\n");
        s.append("idea-hub snapshot: ").append(store.value("last_sync_source_sha","ещё нет")).append("\n");
        String sync=store.value("sync_error","");String delivery=store.value("delivery_error","");if(!sync.isEmpty())s.append("Sync: ").append(sync).append("\n");if(!delivery.isEmpty())s.append("Delivery: ").append(delivery).append("\n");
        s.append("«Обновить карточки сейчас» запускает WorkManager: окно приложения после нажатия можно закрыть.\n");
        s.append("Экран блокировки: уведомление-fallback; видимость зависит от Android. Force-stop требует ручного открытия. Расход батареи ещё не измерен.");status.setText(s);
    }
    @Override protected void onResume(){super.onResume();visible=true;SyncWorker.configure(this,true);sendBroadcast(new Intent(Delivery.CHANGED).setPackage(getPackageName()));Delivery.start(this);Delivery.arm(this);renderStatus();}
    @Override protected void onPause(){visible=false;sendBroadcast(new Intent(Delivery.CHANGED).setPackage(getPackageName()));super.onPause();}
    @Override protected void onDestroy(){unregisterReceiver(changed);super.onDestroy();}
}
