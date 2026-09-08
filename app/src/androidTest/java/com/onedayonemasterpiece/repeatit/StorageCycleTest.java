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
        // This test drives Store time manually. Isolate it from the now-auto-active delivery service; OverlayStressTest separately proves autonomous startup.
        s.put("paused","true");c.stopService(new android.content.Intent(c,OverlayService.class));Delivery.arm(c);
        Instant start=ZonedDateTime.of(LocalDate.of(2026,9,7),LocalTime.NOON,zone).toInstant();Store.clock=Clock.fixed(start,zone);
        try {
            // Normal is an interruption channel: with no deadline pressure, a just-completed contact creates at least 15 minutes of breathing room.
            Engine.Card pacingA=card("pacing","a"),pacingB=card("pacing","b");s.importCards(Map.of("learning/decks/pacing.yaml",List.of(pacingA,pacingB)));Engine.Plan pacingPlan=new Engine.Plan();pacingPlan.deck="pacing";pacingPlan.deadline=null;s.setPlans(List.of(pacingPlan));s.put("last_answer_at",start.toString());s.recompute();assertNotNull(s.decision().due);assertFalse("Normal must not spam immediately after a completed contact",s.decision().due.isBefore(start.plus(Duration.ofMinutes(15))));s.importCards(Map.of(),Set.of());s.setPlans(List.of());s.put("last_answer_at","");s.recompute();

            Engine.Card base=card("synthetic","transaction");s.importCards(Map.of("learning/decks/synthetic.yaml",List.of(base)));Engine.Plan plan=new Engine.Plan();plan.deck="synthetic";plan.deadline=start.plus(Duration.ofDays(2));s.setPlans(List.of(plan));
            Instant due=s.decision().due;assertNotNull(due);s.importCards(Map.of("learning/decks/synthetic.yaml",List.of(base)));s.setPlans(List.of(plan));assertEquals("Unchanged inputs must keep deterministic due",due,s.decision().due);
            Store.clock=Clock.fixed(due,zone);Store.Pending pending=s.prepare(due);assertNotNull(pending);String id=pending.id;assertEquals(id,s.prepare(due.plusSeconds(5)).id);s.markPresentation(id,true,true);assertFalse(s.markPresentation(id,true,true));Store.clock=Clock.fixed(due.plusSeconds(10),zone);assertTrue(s.answer(id,"remember"));assertFalse(s.answer(id,"remember"));assertNull(s.pending());assertEquals(1,s.eventCount(false));assertEquals(1,s.state(base,"normal").contacts);
            List<Store.Batch> first=s.batches(),second=s.batches();assertEquals(1,first.size());assertEquals(first.get(0).id,second.get(0).id);assertEquals(first.get(0).body,second.get(0).body);s.verified(first.get(0).id);assertEquals(1,s.eventCount(true));assertTrue(s.summary().contains("verified_response_events\":1"));

            // 00:29 may create exactly one normal pending; 00:30 may not create a new one. Once shown, the same card survives the cutoff.
            Engine.Card night=card("night","late-response");s.importCards(Map.of("learning/decks/night.yaml",List.of(night)));plan.active=false;Engine.Plan nightPlan=new Engine.Plan();nightPlan.deck="night";nightPlan.deadline=null;s.setPlans(List.of(plan,nightPlan));
            Instant at0029=ZonedDateTime.of(LocalDate.of(2026,9,8),LocalTime.of(0,29),zone).toInstant();Store.clock=Clock.fixed(at0029,zone);s.recompute();Instant nightDue=s.decision().due;assertEquals(at0029,nightDue);Instant cutoff=ZonedDateTime.of(LocalDate.of(2026,9,8),LocalTime.of(0,30),zone).toInstant();assertNull("00:30 cannot create a fresh normal pending even from stale due",s.prepare(cutoff));
            Store.Pending late=s.prepare(at0029);assertNotNull(late);assertEquals(night.key(),late.card.key());s.put("paused","false");assertEquals("An unshown pending must not be first-surfaced after the night cutoff",s.window().next(at0029.plus(Duration.ofMinutes(5))),Delivery.recoveryAt(s,at0029));s.put("paused","true");s.markPresentation(late.id,true,true);Instant at0045=ZonedDateTime.of(LocalDate.of(2026,9,8),LocalTime.of(0,45),zone).toInstant();assertEquals("Unanswered card must block every later prepare",late.id,s.prepare(at0045).id);int beforeLate=s.eventCount(false);Store.clock=Clock.fixed(at0045,zone);assertFalse("00:45 must block NEW normal presentations",s.window().allowed(at0045));assertTrue("Already shown card remains answerable after gate closes",s.answer(late.id,"remember"));assertEquals(beforeLate+1,s.eventCount(false));assertNull(s.pending());
            Instant nextMorning=s.window().next(at0045);assertNotNull(s.decision().due);assertFalse("After 00:45 response the next new card cannot be scheduled into blocked night",s.decision().due.isBefore(nextMorning));

            // A four-hour human absence still owns one pending; elapsed time creates no synthetic response or replacement.
            Instant secondDue=s.decision().due;Store.clock=Clock.fixed(secondDue,zone);Store.Pending fourHour=s.prepare(secondDue);assertNotNull(fourHour);s.markPresentation(fourHour.id,true,true);int beforeFourHour=s.eventCount(false);Instant fourHoursLater=secondDue.plus(Duration.ofHours(4));assertEquals(fourHour.id,s.prepare(fourHoursLater).id);assertEquals(beforeFourHour,s.eventCount(false));s.put("paused","false");assertEquals("A shown unresolved pending gets a bounded durable recovery watchdog",fourHoursLater.plus(Duration.ofMinutes(5)),Delivery.recoveryAt(s,fourHoursLater));s.put("paused","true");Store.clock=Clock.fixed(fourHoursLater,zone);assertTrue(s.answer(fourHour.id,"remember"));assertEquals(beforeFourHour+1,s.eventCount(false));assertEquals(2,s.state(night,"normal").contacts);assertNull(s.pending());

            // Regression from a real Samsung: due while locked may create an unshown pending, then the process can be frozen/killed.
            // Two hours later, while the user is actively using the phone, the same pending must own the slot and have a <=5 minute durable retry.
            Engine.Card recovery=card("recovery","locked-due");s.importCards(Map.of("learning/decks/recovery.yaml",List.of(recovery)));nightPlan.active=false;Engine.Plan recoveryPlan=new Engine.Plan();recoveryPlan.deck="recovery";recoveryPlan.deadline=null;s.setPlans(List.of(plan,nightPlan,recoveryPlan));s.put("last_answer_at","");Instant recoveryDue=ZonedDateTime.of(LocalDate.of(2026,9,8),LocalTime.NOON,zone).toInstant();Store.clock=Clock.fixed(recoveryDue,zone);s.recompute();assertEquals(recoveryDue,s.decision().due);Store.Pending lockedPending=s.prepare(recoveryDue);assertNotNull(lockedPending);assertEquals(0,lockedPending.shown);Instant userReturns=recoveryDue.plus(Duration.ofHours(2));assertTrue(s.window().allowed(userReturns));assertEquals("Time passage must never replace the locked pending",lockedPending.id,s.prepare(userReturns).id);s.put("paused","false");assertEquals("Unshown overdue pending must be retried promptly when normal window is open",userReturns.plus(Duration.ofMinutes(5)),Delivery.recoveryAt(s,userReturns));s.put("paused","true");s.markPresentation(lockedPending.id,true,true);Store.clock=Clock.fixed(userReturns.plusSeconds(1),zone);assertTrue(s.answer(lockedPending.id,"remember"));assertNull(s.pending());

            // Successful remote snapshots remove cards/files that really disappeared, while failed/unlisted synthetic imports are separate.
            Engine.Card stale=card("stale","old");s.importCards(Map.of("learning/decks/stale.yaml",List.of(stale)),Set.of("learning/decks/stale.yaml"));assertTrue(s.cards().stream().anyMatch(x->x.key().equals(stale.key())));s.importCards(Map.of(),Set.of());assertFalse("Deleted remote deck must not survive a successful authoritative sync",s.cards().stream().anyMatch(x->x.key().equals(stale.key())));

            // Test modes are 24h and finite one-pass sessions, independent from the normal gate.
            Engine.Card t1=card("test","one"),t2=card("test","two"),t3=card("test","three");s.importCards(Map.of("learning/decks/test.yaml",List.of(t1,t2,t3)));Instant twoAm=ZonedDateTime.of(LocalDate.of(2026,9,9),LocalTime.of(2,0),zone).toInstant();Store.clock=Clock.fixed(twoAm,zone);assertFalse(s.window().allowed(twoAm));s.setMode("user_demo");assertEquals(twoAm.plus(Duration.ofMinutes(5)),s.decision().due);
            int demoCards=s.cards().size(),demoStartEvents=s.eventCount(false);for(int i=0;i<demoCards;i++){Instant demoDue=s.decision().due;assertNotNull("User demo must have one next card until finite pass is complete",demoDue);Store.clock=Clock.fixed(demoDue,zone);Store.Pending demo=s.prepare(demoDue);assertNotNull(demo);s.markPresentation(demo.id,true,true);Store.clock=Clock.fixed(demoDue.plusSeconds(1),zone);assertTrue(s.answer(demo.id,i%2==0?"remember":"repeat"));}
            assertNull("User demo stops after one pass through active cards",s.decision().due);assertEquals("test_session_complete",s.decision().feasibility);assertEquals(demoStartEvents+demoCards,s.eventCount(false));s.setMode("agent_debug");assertNull("Agent debug waits for explicit due command",s.decision().due);s.forceDue();Store.Pending debug=s.prepare(Store.clock.instant());assertNotNull("Agent debug force due works at 02:00",debug);s.markPresentation(debug.id,true,true);Store.clock=Clock.fixed(Store.clock.instant().plusSeconds(1),zone);assertTrue(s.answer(debug.id,"remember"));assertNull("Agent debug does not automatically spam the next card",s.decision().due);s.setMode("normal");assertEquals(1,s.state(base,"normal").contacts);assertEquals(2,s.state(night,"normal").contacts);

            // Generic mode switch stays guarded, while the explicit owner escape hatch may discard test-only pending and return to normal without learning credit.
            s.setMode("agent_debug");s.forceDue();Store.Pending modeGuard=s.prepare(Store.clock.instant());assertNotNull(modeGuard);boolean blocked=false;try{s.setMode("user_demo");}catch(IllegalStateException expected){blocked="pending_must_be_answered_before_mode_change".equals(expected.getMessage());}assertTrue("Unresolved pending must block generic mode change",blocked);int beforeExit=s.eventCount(false);s.exitTestMode();assertEquals("normal",s.mode());assertNull("Owner test exit discards only test pending",s.pending());assertEquals("Exiting test must not fake a response",beforeExit,s.eventCount(false));assertEquals("false",s.value("paused","false"));

            boolean denied=false;try{c.getContentResolver().call(Uri.parse("content://com.onedayonemasterpiece.repeatit.admin"),"mode","user_demo",null);}catch(SecurityException expected){denied=true;}assertTrue("Application UID must not get shell administration",denied);
            try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(c.getDatabasePath("repeat-it.db").getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY);android.database.Cursor row=db.rawQuery("SELECT COUNT(*) FROM events",null)){assertTrue(row.moveToFirst());assertTrue(row.getInt(0)>=5);}
        } finally {Store.clock=Clock.systemDefaultZone();}
    }
}
