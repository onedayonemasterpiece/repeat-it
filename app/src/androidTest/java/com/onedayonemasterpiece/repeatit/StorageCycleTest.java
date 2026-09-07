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
    private static Engine.Card card(String deck,String id){Engine.Card c=new Engine.Card();c.deck=deck;c.id=id;c.title="Synthetic "+id;c.text="Public test only";return c;}
    @Test public void transactionRecoveryIsolationOutboxAndHumanDelay() {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();Store s=Store.get(c);assertEquals("Fresh emulator only",0,s.cards().size());ZoneId zone=ZoneId.systemDefault();
        Instant start=ZonedDateTime.of(LocalDate.of(2026,9,7),LocalTime.NOON,zone).toInstant();Store.clock=Clock.fixed(start,zone);
        try {
            Engine.Card base=card("synthetic","transaction");s.importCards(Map.of("learning/decks/synthetic.yaml",List.of(base)));Engine.Plan plan=new Engine.Plan();plan.deck="synthetic";plan.deadline=start.plus(Duration.ofDays(2));s.setPlans(List.of(plan));
            Instant due=s.decision().due;assertNotNull(due);s.importCards(Map.of("learning/decks/synthetic.yaml",List.of(base)));s.setPlans(List.of(plan));assertEquals("Unchanged inputs must keep deterministic due",due,s.decision().due);
            Store.clock=Clock.fixed(due,zone);Store.Pending pending=s.prepare(due);assertNotNull(pending);String id=pending.id;assertEquals(id,s.prepare(due.plusSeconds(5)).id);s.markPresentation(id,true,true);assertFalse(s.markPresentation(id,true,true));Store.clock=Clock.fixed(due.plusSeconds(10),zone);assertTrue(s.answer(id,"remember"));assertFalse(s.answer(id,"remember"));assertNull(s.pending());assertEquals(1,s.eventCount(false));assertEquals(1,s.state(base,"normal").contacts);
            List<Store.Batch> first=s.batches(),second=s.batches();assertEquals(1,first.size());assertEquals(first.get(0).id,second.get(0).id);assertEquals(first.get(0).body,second.get(0).body);s.verified(first.get(0).id);assertEquals(1,s.eventCount(true));assertTrue(s.summary().contains("verified_response_events\":1"));

            // 00:29 may create exactly one normal pending; 00:30 may not create a new one. Once shown, the same card survives the cutoff.
            Engine.Card night=card("night","late-response");s.importCards(Map.of("learning/decks/night.yaml",List.of(night)));plan.active=false;Engine.Plan nightPlan=new Engine.Plan();nightPlan.deck="night";nightPlan.deadline=null;s.setPlans(List.of(plan,nightPlan));
            Instant at0029=ZonedDateTime.of(LocalDate.of(2026,9,8),LocalTime.of(0,29),zone).toInstant();Store.clock=Clock.fixed(at0029,zone);s.recompute();Instant nightDue=s.decision().due;assertEquals(at0029,nightDue);Instant cutoff=ZonedDateTime.of(LocalDate.of(2026,9,8),LocalTime.of(0,30),zone).toInstant();assertNull("00:30 cannot create a fresh normal pending even from stale due",s.prepare(cutoff));
            Store.Pending late=s.prepare(at0029);assertNotNull(late);assertEquals(night.key(),late.card.key());s.markPresentation(late.id,true,true);Instant at0045=ZonedDateTime.of(LocalDate.of(2026,9,8),LocalTime.of(0,45),zone).toInstant();assertEquals("Unanswered card must block every later prepare",late.id,s.prepare(at0045).id);int beforeLate=s.eventCount(false);Store.clock=Clock.fixed(at0045,zone);assertFalse("00:45 must block NEW normal presentations",s.window().allowed(at0045));assertTrue("Already shown card remains answerable after gate closes",s.answer(late.id,"remember"));assertEquals(beforeLate+1,s.eventCount(false));assertNull(s.pending());
            Instant nextMorning=s.window().next(at0045);assertNotNull(s.decision().due);assertFalse("After 00:45 response the next new card cannot be scheduled into blocked night",s.decision().due.isBefore(nextMorning));

            // A four-hour human absence still owns one pending; elapsed time creates no synthetic response or replacement.
            Instant secondDue=s.decision().due;Store.clock=Clock.fixed(secondDue,zone);Store.Pending fourHour=s.prepare(secondDue);assertNotNull(fourHour);s.markPresentation(fourHour.id,true,true);int beforeFourHour=s.eventCount(false);Instant fourHoursLater=secondDue.plus(Duration.ofHours(4));assertEquals(fourHour.id,s.prepare(fourHoursLater).id);assertEquals(beforeFourHour,s.eventCount(false));Store.clock=Clock.fixed(fourHoursLater,zone);assertTrue(s.answer(fourHour.id,"remember"));assertEquals(beforeFourHour+1,s.eventCount(false));assertEquals(2,s.state(night,"normal").contacts);assertNull(s.pending());

            // Successful remote snapshots remove cards/files that really disappeared, while failed/unlisted synthetic imports are separate.
            Engine.Card stale=card("stale","old");s.importCards(Map.of("learning/decks/stale.yaml",List.of(stale)),Set.of("learning/decks/stale.yaml"));assertTrue(s.cards().stream().anyMatch(x->x.key().equals(stale.key())));s.importCards(Map.of(),Set.of());assertFalse("Deleted remote deck must not survive a successful authoritative sync",s.cards().stream().anyMatch(x->x.key().equals(stale.key())));

            // Test modes are 24h and finite one-pass sessions, independent from the normal gate.
            Engine.Card t1=card("test","one"),t2=card("test","two"),t3=card("test","three");s.importCards(Map.of("learning/decks/test.yaml",List.of(t1,t2,t3)));Instant twoAm=ZonedDateTime.of(LocalDate.of(2026,9,9),LocalTime.of(2,0),zone).toInstant();Store.clock=Clock.fixed(twoAm,zone);assertFalse(s.window().allowed(twoAm));s.setMode("user_demo");assertEquals(twoAm.plus(Duration.ofMinutes(5)),s.decision().due);
            int demoCards=s.cards().size(),demoStartEvents=s.eventCount(false);for(int i=0;i<demoCards;i++){Instant demoDue=s.decision().due;assertNotNull("User demo must have one next card until finite pass is complete",demoDue);Store.clock=Clock.fixed(demoDue,zone);Store.Pending demo=s.prepare(demoDue);assertNotNull(demo);s.markPresentation(demo.id,true,true);Store.clock=Clock.fixed(demoDue.plusSeconds(1),zone);assertTrue(s.answer(demo.id,i%2==0?"remember":"repeat"));}
            assertNull("User demo stops after one pass through active cards",s.decision().due);assertEquals("test_session_complete",s.decision().feasibility);assertEquals(demoStartEvents+demoCards,s.eventCount(false));s.setMode("agent_debug");s.forceDue();Store.Pending debug=s.prepare(Store.clock.instant());assertNotNull("Agent debug force due works at 02:00",debug);s.markPresentation(debug.id,true,true);Store.clock=Clock.fixed(Store.clock.instant().plusSeconds(1),zone);assertTrue(s.answer(debug.id,"remember"));s.setMode("normal");assertEquals(1,s.state(base,"normal").contacts);assertEquals(2,s.state(night,"normal").contacts);

            // A mode switch cannot hide one unresolved pending and create another in a different test namespace.
            s.setMode("agent_debug");s.forceDue();Store.Pending modeGuard=s.prepare(Store.clock.instant());assertNotNull(modeGuard);boolean blocked=false;try{s.setMode("user_demo");}catch(IllegalStateException expected){blocked="pending_must_be_answered_before_mode_change".equals(expected.getMessage());}assertTrue("Unresolved pending must block mode change",blocked);s.markPresentation(modeGuard.id,true,true);Store.clock=Clock.fixed(Store.clock.instant().plusSeconds(1),zone);assertTrue(s.answer(modeGuard.id,"remember"));s.setMode("normal");

            boolean denied=false;try{c.getContentResolver().call(Uri.parse("content://com.onedayonemasterpiece.repeatit.admin"),"mode","user_demo",null);}catch(SecurityException expected){denied=true;}assertTrue("Application UID must not get shell administration",denied);
            try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(c.getDatabasePath("repeat-it.db").getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY);android.database.Cursor row=db.rawQuery("SELECT COUNT(*) FROM events",null)){assertTrue(row.moveToFirst());assertTrue(row.getInt(0)>=5);}
        } finally {Store.clock=Clock.systemDefaultZone();}
    }
}
