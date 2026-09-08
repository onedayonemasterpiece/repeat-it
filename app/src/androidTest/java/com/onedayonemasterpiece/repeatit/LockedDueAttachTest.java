package com.onedayonemasterpiece.repeatit;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import com.onedayonemasterpiece.repeatit.core.Engine;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real WindowManager regression: due while asleep is attached but not credited until unlock. */
@RunWith(AndroidJUnit4.class)
public final class LockedDueAttachTest {
    private static final long TIMEOUT=7000;
    private static void changed(Context c){c.sendBroadcast(new Intent(Delivery.CHANGED).setPackage(c.getPackageName()));}
    private static Store.Pending waitPending(Store s) throws Exception {long end=System.currentTimeMillis()+TIMEOUT;while(System.currentTimeMillis()<end){Store.Pending p=s.pending();if(p!=null)return p;Thread.sleep(50);}fail("pending not created");return null;}
    private static void waitValue(Store s,String key,String expected) throws Exception {long end=System.currentTimeMillis()+TIMEOUT;while(System.currentTimeMillis()<end){if(expected.equals(s.value(key,"")))return;Thread.sleep(50);}assertEquals(key,expected,s.value(key,""));}
    private static Store.Pending waitShown(Store s,String id) throws Exception {long end=System.currentTimeMillis()+TIMEOUT;while(System.currentTimeMillis()<end){Store.Pending p=s.pending();if(p!=null&&id.equals(p.id)&&p.shown>0)return p;Thread.sleep(50);}fail("same pending was not marked shown after unlock");return null;}

    @Test public void dueWhileScreenOffPreattachesAndSurfacesAfterUnlock() throws Exception {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());Store s=Store.get(c);
        assertTrue("overlay permission required",Settings.canDrawOverlays(c));assertEquals("fresh synthetic install expected",0,s.cards().size());s.put("paused","false");s.put("sound","false");
        Engine.Card card=new Engine.Card();card.deck="locked-due";card.id="one";card.title="Locked due";card.text="Synthetic recovery evidence";s.importCards(Map.of("learning/decks/locked-due.yaml",List.of(card)));s.setMode("agent_debug");
        Delivery.start(c);Thread.sleep(400);device.pressHome();Thread.sleep(250);device.sleep();Thread.sleep(400);
        s.forceDue();changed(c);Store.Pending asleep=waitPending(s);String id=asleep.id;waitValue(s,"overlay_attached","true");asleep=s.pending();assertNotNull(asleep);assertEquals("Sleeping/locked attachment must not count as exposure",0,asleep.shown);assertEquals("No response may be synthesized while asleep",0,s.eventCount(false));
        Instant now=Instant.now();Instant retry=Delivery.recoveryAt(s,now);assertNotNull(retry);long retryMs=Duration.between(now,retry).toMillis();assertTrue("pending recovery watchdog must be bounded",retryMs>0&&retryMs<=Delivery.PENDING_RECOVERY_MS+1000);
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");Thread.sleep(450);device.pressHome();changed(c);Store.Pending awake=waitShown(s,id);assertEquals("Unlock must preserve the exact pending identity",id,awake.id);waitValue(s,"overlay_visible","true");assertEquals(0,s.eventCount(false));
        assertTrue(s.answer(id,"remember"));assertNull(s.pending());assertEquals(1,s.eventCount(false));s.put("paused","true");changed(c);Thread.sleep(150);
    }
}
