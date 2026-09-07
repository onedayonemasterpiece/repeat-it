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
import com.onedayonemasterpiece.repeatit.core.Engine;

public final class MainActivity extends Activity {
    public static volatile boolean visible=false;
    private Store store;private TextView status;
    private final BroadcastReceiver changed=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){renderStatus();}};
    @Override public void onCreate(Bundle b){
        super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);store=Store.get(this);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(24,32,24,32);
        TextView title=new TextView(this);title.setText("Повторение");title.setTextSize(32);page.addView(title);
        status=new TextView(this);status.setTextSize(17);page.addView(status);
        button(page,"Включить / возобновить",()->{store.put("enabled","true");store.put("paused","false");Delivery.start(this);Delivery.arm(this);renderStatus();});
        button(page,"Пауза / продолжить",()->{store.put("paused",String.valueOf(!store.value("paused","false").equals("true")));sendBroadcast(new Intent(Delivery.CHANGED).setPackage(getPackageName()));renderStatus();});
        button(page,"Синхронизировать с GitHub",()->{SyncWorker.configure(this,true);renderStatus();});
        button(page,"Разрешения Android",()->new AlertDialog.Builder(this).setTitle("Для автономного показа")
            .setItems(new String[]{"Поверх других приложений","Уведомления","Точный ближайший сигнал"},(dialog,index)->{
                if(index==0)startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));
                if(index==1&&Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},71);
                if(index==2&&Build.VERSION.SDK_INT>=31)startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));
            }).show());
        button(page,"Настроить GitHub PAT",()->{
            EditText entry=new EditText(this);entry.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);entry.setSingleLine();entry.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Fine-grained PAT только для idea-hub")
                .setMessage("Contents: read/write. GitHub ограничивает ключ репозиторием, не папкой. Приложение читает learning/ и пишет только свой progress. Ключ не передаётся в журнал или резервную копию.")
                .setView(entry).setPositiveButton("Сохранить",(d,w)->{
                    try{new TokenVault(this).save(entry.getText().toString());entry.getText().clear();SyncWorker.configure(this,true);}
                    catch(Exception e){entry.getText().clear();store.put("sync_error","token_save_failed:"+e.getClass().getSimpleName());}renderStatus();
                }).setNegativeButton("Отмена",(d,w)->entry.getText().clear()).create();dialog.show();dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        });
        ScrollView scroll=new ScrollView(this);scroll.addView(page);setContentView(scroll);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(changed,new IntentFilter(Delivery.CHANGED),Context.RECEIVER_NOT_EXPORTED);else registerReceiver(changed,new IntentFilter(Delivery.CHANGED));
        SyncWorker.configure(this,true);
    }
    private void button(LinearLayout parent,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setOnClickListener(v->action.run());parent.addView(b);}
    private void renderStatus(){
        Engine.Decision d=store.decision();Store.Pending p=store.pending();
        StringBuilder s=new StringBuilder("Самооценка, не проверка знаний.\n");
        s.append("Карточек: ").append(store.cards().size()).append(". Режим: ").append(store.mode()).append("\n");
        s.append("07:40–23:00 · ").append(java.time.ZoneId.systemDefault()).append("\n");
        s.append("Overlay: ").append(Settings.canDrawOverlays(this)?"разрешён":"нужно разрешение").append("\n");
        s.append("Пауза: ").append(store.value("paused","false")).append(". Звук следующих карточек: ").append(store.sound()?"включён":"выключен").append("\n");
        s.append("Pending: ").append(p==null?"нет":p.id).append("\n");
        s.append("Следующий срок: ").append(d.due==null?"не назначен":d.due.atZone(java.time.ZoneId.systemDefault())).append("\n");
        s.append("Осталось контактов: ").append(d.remaining).append(". После дедлайна: ").append(d.expired).append(". Без плана: ").append(d.withoutPlan).append("\n");
        if(d.loads!=null)for(Engine.Load load:d.loads)s.append("До ").append(load.deadline).append(": ").append(load.cumulative).append(" контактов; расчётный темп ").append((load.requiredPerAllowedHour==null?"нет разрешённого времени":String.format(java.util.Locale.ROOT,"%.2f",load.requiredPerAllowedHour))).append(" за час разрешённого окна.\n");
        s.append("Оценка: ").append(d.feasibility).append("; окно не означает непрерывную доступность.\n");
        s.append("Ответов локально / readback: ").append(store.eventCount(false)).append(" / ").append(store.eventCount(true)).append("\n");
        s.append("Последняя синхронизация: ").append(store.value("last_sync","ещё нет")).append("\n");
        s.append(store.value("sync_error","")).append("\n").append(store.value("delivery_error","")).append("\n");
        s.append("Экран блокировки: уведомление-fallback; видимость зависит от Android. Виджет на устройстве ещё не подтверждён.\n");
        s.append("Force-stop требует ручного открытия. Расход батареи ещё не измерен.");status.setText(s);
    }
    @Override protected void onResume(){super.onResume();visible=true;sendBroadcast(new Intent(Delivery.CHANGED).setPackage(getPackageName()));Delivery.start(this);renderStatus();}
    @Override protected void onPause(){visible=false;sendBroadcast(new Intent(Delivery.CHANGED).setPackage(getPackageName()));super.onPause();}
    @Override protected void onDestroy(){unregisterReceiver(changed);super.onDestroy();}
}
