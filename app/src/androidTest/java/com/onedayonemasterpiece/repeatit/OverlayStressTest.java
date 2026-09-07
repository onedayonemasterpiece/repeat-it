package com.onedayonemasterpiece.repeatit;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import com.onedayonemasterpiece.repeatit.core.Engine;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Synthetic native stress journey for the real TYPE_APPLICATION_OVERLAY.
 * It never uses owner data, PATs or normal-mode progress.
 */
@RunWith(AndroidJUnit4.class)
public final class OverlayStressTest {
    private static final int CYCLES=40;
    private static final long UI_TIMEOUT=7000;

    private static void changed(Context c){
        c.sendBroadcast(new Intent(Delivery.CHANGED).setPackage(c.getPackageName()));
    }

    private static UiObject2 waitButton(UiDevice device,String text){
        UiObject2 value=device.wait(Until.findObject(By.text(text)),UI_TIMEOUT);
        assertNotNull("Overlay button missing: "+text,value);
        assertEquals("Overlay must belong to Repeat It","com.onedayonemasterpiece.repeatit",value.getApplicationPackage());
        return value;
    }

    private static void waitActivityHidden() throws Exception {
        long end=System.currentTimeMillis()+UI_TIMEOUT;
        while(System.currentTimeMillis()<end&&MainActivity.visible)Thread.sleep(50);
        assertFalse("MainActivity must be paused before overlay delivery",MainActivity.visible);
    }

    private static void waitPresentation(Store store) throws Exception {
        long end=System.currentTimeMillis()+UI_TIMEOUT;
        while(System.currentTimeMillis()<end){
            Store.Pending p=store.pending();
            if(p!=null&&p.shown>0&&!store.value("overlay_geometry","").isEmpty())return;
            Thread.sleep(60);
        }
    }

    private static void writeDebug(Context c,UiDevice device,Store store,String stage) {
        File root=c.getExternalFilesDir(null);
        try(FileWriter out=new FileWriter(new File(root,"overlay-stress-debug.txt"),true)){
            Store.Pending p=store.pending();
            out.write("stage="+stage+"\n");
            out.write("main_visible="+MainActivity.visible+"\n");
            out.write("foreground_package="+String.valueOf(device.getCurrentPackageName())+"\n");
            out.write("can_draw_overlays="+Settings.canDrawOverlays(c)+"\n");
            out.write("enabled="+store.value("enabled","")+"\npaused="+store.value("paused","")+"\nmode="+store.mode()+"\n");
            out.write("pending_id="+(p==null?"":p.id)+"\npending_shown="+(p==null?0:p.shown)+"\n");
            out.write("next_due="+String.valueOf(store.decision().due)+"\n");
            out.write("overlay_geometry="+store.value("overlay_geometry","")+"\n");
            out.write("delivery_error="+store.value("delivery_error","")+"\nsync_error="+store.value("sync_error","")+"\n---\n");
        }catch(Exception ignored){}
        try{device.dumpWindowHierarchy(new File(root,"overlay-window-hierarchy.xml"));}catch(Exception ignored){}
    }

    private static void waitEvents(Store store,int expected) throws Exception {
        long end=System.currentTimeMillis()+UI_TIMEOUT;
        while(System.currentTimeMillis()<end){
            if(store.eventCount(false)==expected)return;
            Thread.sleep(60);
        }
        assertEquals("Response event did not commit",expected,store.eventCount(false));
    }

    private static void assertGeometry(Store store){
        String value=store.value("overlay_geometry","");
        assertTrue("Overlay geometry missing",value.matches("[0-9]+x[0-9]+/[0-9]+x[0-9]+"));
        String[] halves=value.split("/");
        String[] panel=halves[0].split("x"),screen=halves[1].split("x");
        double wr=Double.parseDouble(panel[0])/Double.parseDouble(screen[0]);
        double hr=Double.parseDouble(panel[1])/Double.parseDouble(screen[1]);
        assertTrue("Overlay width should be near 94%",wr>=0.90&&wr<=0.98);
        assertTrue("Overlay height should be near 82%",hr>=0.78&&hr<=0.86);
    }

    @Test public void nativeOverlaySurvivesFortyAcceleratedCycles() throws Exception {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Store store=Store.get(c);
        assertTrue("SYSTEM_ALERT_WINDOW must be granted by harness",Settings.canDrawOverlays(c));
        assertEquals("Fresh stress install expected",0,store.cards().size());

        // Test-only broad window avoids wall-clock CI flakiness. Production stays 07:40–23:00
        // and its exact boundaries are separately covered by the pure scheduler suite.
        store.put("window_start","00:00");store.put("window_end","23:59");
        store.put("enabled","true");store.put("paused","false");store.put("sound","false");
        List<Engine.Card> cards=new ArrayList<>();
        for(int i=0;i<12;i++){
            Engine.Card card=new Engine.Card();card.deck="stress";card.id="card-"+i;
            card.title="Stress card "+i;card.text="Synthetic overlay stress payload "+i;cards.add(card);
        }
        assertTrue(store.importCards(Map.of("learning/decks/stress.yaml",cards)).isEmpty());
        store.setMode("agent_debug");store.recompute();

        // Start the FGS while our Activity is unquestionably foreground. Then wait for its real
        // onPause before asking the service to put an overlay above Android Settings.
        Intent launch=new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(launch);Thread.sleep(700);Delivery.start(c);Thread.sleep(300);
        device.pressHome();device.executeShellCommand("am start -a android.settings.SETTINGS");
        waitActivityHidden();Thread.sleep(300);writeDebug(c,device,store,"ready_under_settings");

        int screenCycles=0,appSwitches=1,rotations=0;
        try {
            for(int i=0;i<CYCLES;i++){
                store.forceDue();changed(c);waitPresentation(store);
                writeDebug(c,device,store,"cycle_"+i+"_presented");
                Store.Pending presented=store.pending();
                assertNotNull("Due card must become pending",presented);
                assertTrue("Pending was not actually presented; delivery_error="+store.value("delivery_error",""),presented.shown>0);
                assertGeometry(store);
                UiObject2 remember=waitButton(device,"Помню");
                waitButton(device,"Повторить");

                if(i==10||i==29){
                    String pendingId=presented.id;
                    device.sleep();Thread.sleep(450);
                    assertEquals("Pending must survive screen-off",pendingId,store.pending().id);
                    device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");Thread.sleep(450);changed(c);
                    assertEquals("Pending must survive screen-on",pendingId,store.pending().id);
                    waitButton(device,"Помню");screenCycles++;
                }
                if(i==20){
                    device.setOrientationLeft();Thread.sleep(650);changed(c);waitButton(device,"Помню");assertGeometry(store);
                    device.setOrientationNatural();Thread.sleep(650);changed(c);waitButton(device,"Помню");assertGeometry(store);
                    device.unfreezeRotation();rotations++;
                }
                if(i>0&&i%5==0){
                    device.executeShellCommand(i%10==0?"am start -a android.settings.WIFI_SETTINGS":"am start -a android.settings.SETTINGS");
                    Thread.sleep(450);waitActivityHidden();changed(c);waitButton(device,"Помню");appSwitches++;
                }

                UiObject2 action=(i%3==0)?waitButton(device,"Повторить"):remember;
                action.click();
                waitEvents(store,i+1);
                assertNull("Answered pending must close",store.pending());
                assertEquals("Stress must not write verified normal progress",0,store.eventCount(true));
            }

            assertEquals(CYCLES,store.eventCount(false));
            assertEquals("agent_debug",store.mode());
            assertTrue("Overlay delivery error: "+store.value("delivery_error",""),store.value("delivery_error","").isEmpty());
            List<Store.Batch> batches=store.batches();
            assertEquals("40 synthetic events should fit one outbox batch",1,batches.size());
            assertEquals("agent_debug",batches.get(0).mode);

            File evidence=new File(c.getExternalFilesDir(null),"overlay-stress-summary.txt");
            try(FileWriter out=new FileWriter(evidence,false)){
                out.write("result=PASS\ncycles="+CYCLES+"\nevents="+store.eventCount(false)+"\nmode="+store.mode()+"\n");
                out.write("screen_off_on="+screenCycles+"\napp_switches="+appSwitches+"\nrotations="+rotations+"\n");
                out.write("geometry="+store.value("overlay_geometry","")+"\nalarm_precision="+store.value("alarm_precision","")+"\n");
            }
        } finally {
            writeDebug(c,device,store,"finally");
            store.put("enabled","false");changed(c);Thread.sleep(250);
        }
    }
}
