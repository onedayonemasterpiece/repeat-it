package com.onedayonemasterpiece.repeatit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import android.content.Context;
import android.net.Uri;
import com.onedayonemasterpiece.repeatit.core.*;
import java.time.*;
import java.util.*;

/** Emulator-only synthetic transaction test. Never execute against an owner's configured installation. */
@RunWith(AndroidJUnit4.class)
public class StorageCycleTest {
    @Test public void transactionRecoveryIsolationAndOutbox() {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();Store s=Store.get(c);
        assertEquals("Fresh emulator only",0,s.cards().size());
        Instant start=ZonedDateTime.now().withHour(12).withMinute(0).withSecond(0).withNano(0).toInstant();
        Store.clock=Clock.fixed(start,ZoneId.systemDefault());
        try {
            Engine.Card card=new Engine.Card();card.deck="synthetic";card.id="transaction";card.title="Synthetic";card.text="Public test only";
            s.importCards(Map.of("learning/decks/synthetic.yaml",List.of(card)));
            Engine.Plan plan=new Engine.Plan();plan.deck="synthetic";plan.deadline=start.plus(Duration.ofDays(2));s.setPlans(List.of(plan));
            Instant due=s.decision().due;assertNotNull(due);
            s.importCards(Map.of("learning/decks/synthetic.yaml",List.of(card)));s.setPlans(List.of(plan));assertEquals("Unchanged sync must not postpone",due,s.decision().due);
            Store.clock=Clock.fixed(due,ZoneId.systemDefault());Store.Pending pending=s.prepare(due);assertNotNull(pending);String id=pending.id;
            assertEquals(id,s.prepare(due.plusSeconds(5)).id);
            s.markPresentation(id,true,true);assertFalse(s.markPresentation(id,true,true));
            Store.clock=Clock.fixed(due.plusSeconds(10),ZoneId.systemDefault());
            assertTrue(s.answer(id,"remember"));assertFalse(s.answer(id,"remember"));assertNull(s.pending());assertEquals(1,s.eventCount(false));assertNotNull(s.decision().due);
            assertEquals(1,s.state(card,"normal").contacts);
            List<Store.Batch> first=s.batches(),second=s.batches();assertEquals(1,first.size());assertEquals(first.get(0).id,second.get(0).id);assertEquals(first.get(0).body,second.get(0).body);
            s.verified(first.get(0).id);assertEquals(1,s.eventCount(true));assertTrue(s.summary().contains("verified_response_events\":1"));
            s.put("sound","true");s.setMode("user_demo");assertTrue(s.sound());assertEquals(1,s.state(card,"normal").contacts);
            assertEquals(300000,s.window().available(Store.clock.instant(),s.decision().due));
            s.setMode("normal");assertEquals(1,s.state(card,"normal").contacts);
            Engine.Card changed=new Engine.Card();changed.deck=card.deck;changed.id=card.id;changed.title="Synthetic";changed.text="New synthetic meaning";changed.revision=2;changed.meaning=2;
            s.importCards(Map.of("learning/decks/synthetic.yaml",List.of(changed)));assertEquals(0,s.state(changed,"normal").contacts);assertEquals(1,s.state(card,"normal").contacts);
            boolean denied=false;try{c.getContentResolver().call(Uri.parse("content://com.onedayonemasterpiece.repeatit.admin"),"mode","user_demo",null);}catch(SecurityException expected){denied=true;}assertTrue("Application UID must not get shell administration",denied);
            try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(c.getDatabasePath("repeat-it.db").getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY);android.database.Cursor row=db.rawQuery("SELECT COUNT(*) FROM events",null)){assertTrue(row.moveToFirst());assertEquals(1,row.getInt(0));}
        } finally {Store.clock=Clock.systemDefaultZone();}
    }
}
