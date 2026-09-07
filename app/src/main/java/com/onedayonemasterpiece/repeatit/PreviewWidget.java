package com.onedayonemasterpiece.repeatit;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.widget.RemoteViews;
import java.time.Instant;

/** Host-dependent optional surface. Updates never count as a view or a response. */
public final class PreviewWidget extends AppWidgetProvider {
    public static void refresh(Context c){
        AppWidgetManager manager=AppWidgetManager.getInstance(c);int[] ids=manager.getAppWidgetIds(new ComponentName(c,PreviewWidget.class));
        if(ids.length==0)return;
        Store s=Store.get(c);Store.Pending p=s.pending();boolean allowed=s.window().allowed(Instant.now())&&s.value("widget_verified","false").equals("true")&&p!=null&&p.card.preview&&!s.value("paused","false").equals("true");
        if(allowed){boolean current=false;for(com.onedayonemasterpiece.repeatit.core.Engine.Card card:s.cards())if(card.key().equals(p.card.key()))current=card.preview&&card.active;allowed=current;}
        RemoteViews views=new RemoteViews(c.getPackageName(),R.layout.preview_widget);views.setTextViewText(R.id.widget_title,allowed?p.card.title:"Повторение");views.setTextViewText(R.id.widget_text,allowed?p.card.text:"Открыть после разблокировки");
        views.setOnClickPendingIntent(R.id.widget_root,PendingIntent.getActivity(c,81,new Intent(c,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));manager.updateAppWidget(ids,views);
    }
    @Override public void onUpdate(Context c,AppWidgetManager m,int[] ids){refresh(c);}
}
