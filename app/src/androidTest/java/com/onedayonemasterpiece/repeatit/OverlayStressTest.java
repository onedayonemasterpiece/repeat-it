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

/** Synthetic stress journey for the real TYPE_APPLICATION_OVERLAY. Owner data/PAT/normal progress are never used. */
@RunWith(AndroidJUnit4.class)
public final class OverlayStressTest {
    private static final int CYCLES=40;
    private static final long UI_TIMEOUT=7000;
    private static final String PKG="com.onedayonemasterpiece.repeatit";
    private static void changed(Context c){c.sendBroadcast(new Intent(Delivery.CHANGED).setPackage(c.getPackageName()));}
    private static void waitActivityHidden() throws Exception {long end=System.currentTimeMillis()+UI_TIMEOUT;while(System.currentTimeMillis()<end&&MainActivity.visible)Thread.sleep(50);assertFalse("MainActivity must be paused before overlay delivery",MainActivity.visible);}
    private static void waitPresentation(Store store) throws Exception {long end=System.currentTimeMillis()+UI_TIMEOUT;while(System.currentTimeMillis()<end){Store.Pending p=store.pending();if(p!=null&&p.shown>0&&!store.value("overlay_geometry","").isEmpty()&&store.value("overlay_visible","false").equals("true"))return;Thread.sleep(60);}fail("Pending was not presented");}
    private static void waitControls() throws Exception {long end=System.currentTimeMillis()+UI_TIMEOUT;while(System.currentTimeMillis()<end){if(OverlayService.rememberX>0&&OverlayService.rememberY>0&&OverlayService.repeatX>0&&OverlayService.repeatY>0)return;Thread.sleep(40);}fail("Overlay reaction hit targets were not laid out");}
    private static boolean windowVisible(UiDevice device) throws Exception {String dump=device.executeShellCommand("dumpsys window windows");int owner=dump.indexOf("package="+PKG+" appop=SYSTEM_ALERT_WINDOW");if(owner<0)return false;int start=dump.lastIndexOf("Window #",owner),end=dump.indexOf("Window #",owner+1);if(start<0)start=Math.max(0,owner-1000);if(end<0)end=Math.min(dump.length(),owner+5000);String block=dump.substring(start,end);return block.contains("Surface: shown=true")&&block.contains("isOnScreen=true")&&block.contains("isVisible=true");}
    private static void waitWindow(UiDevice device,boolean visible) throws Exception {long end=System.currentTimeMillis()+UI_TIMEOUT;while(System.currentTimeMillis()<end){if(windowVisible(device)==visible)return;Thread.sleep(100);}assertEquals("Unexpected OS visibility for overlay",visible,windowVisible(device));}
    private static void tapReaction(UiDevice device,boolean repeat){int x=repeat?OverlayService.repeatX:OverlayService.rememberX,y=repeat?OverlayService.repeatY:OverlayService.rememberY;assertTrue("Reaction X outside display: "+x,x>0&&x<device.getDisplayWidth());assertTrue("Reaction Y outside display: "+y,y>0&&y<device.getDisplayHeight());assertTrue("Native reaction tap rejected",device.click(x,y));}
    private static void writeDebug(Context c,UiDevice device,Store store,String stage){File root=c.getExternalFilesDir(null);try(FileWriter out=new FileWriter(new File(root,"overlay-stress-debug.txt"),true)){Store.Pending p=store.pending();out.write("stage="+stage+"\nmain_visible="+MainActivity.visible+"\nforeground_package="+String.valueOf(device.getCurrentPackageName())+"\n");out.write("can_draw_overlays="+Settings.canDrawOverlays(c)+"\npaused="+store.value("paused","")+"\nmode="+store.mode()+"\n");out.write("pending_id="+(p==null?"":p.id)+"\npending_shown="+(p==null?0:p.shown)+"\nnext_due="+String.valueOf(store.decision().due)+"\n");out.write("overlay_geometry="+store.value("overlay_geometry","")+"\noverlay_visible="+store.value("overlay_visible","")+"\nremember_center="+OverlayService.rememberX+","+OverlayService.rememberY+"\nrepeat_center="+OverlayService.repeatX+","+OverlayService.repeatY+"\n");try{out.write("wm_visible="+windowVisible(device)+"\n");}catch(Exception e){out.write("wm_visible=unknown\n");}out.write("last_ui_action="+store.value("last_ui_action","")+"\ndelivery_error="+store.value("delivery_error","")+"\nsync_error="+store.value("sync_error","")+"\n---\n");}catch(Exception ignored){}try{device.dumpWindowHierarchy(new File(root,"overlay-window-hierarchy.xml"));}catch(Exception ignored){}}
    private static void waitEvents(Store store,int expected) throws Exception {long end=System.currentTimeMillis()+UI_TIMEOUT;while(System.currentTimeMillis()<end){if(store.eventCount(false)==expected)return;Thread.sleep(60);}assertEquals("Response event did not commit",expected,store.eventCount(false));}
    private static void assertGeometry(Store store){String value=store.value("overlay_geometry","");assertTrue("Overlay geometry missing",value.matches("[0-9]+x[0-9]+/[0-9]+x[0-9]+"));String[] halves=value.split("/"),panel=halves[0].split("x"),screen=halves[1].split("x");double wr=Double.parseDouble(panel[0])/Double.parseDouble(screen[0]),hr=Double.parseDouble(panel[1])/Double.parseDouble(screen[1]);assertTrue("Overlay width should be near 94%",wr>=0.90&&wr<=0.98);assertTrue("Overlay height should be near 82%",hr>=0.78&&hr<=0.86);}

    @Test public void nativeOverlaySurvivesFiniteDelayedFortyCycleJourney() throws Exception {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());Store store=Store.get(c);assertTrue("SYSTEM_ALERT_WINDOW must be granted by harness",Settings.canDrawOverlays(c));assertEquals("Fresh stress install expected",0,store.cards().size());
        // A clean install is autonomous: no legacy enabled flag may be required. Only explicit pause can stop delivery.
        assertEquals("Fresh install must not depend on a hidden enabled flag","",store.value("enabled",""));store.put("paused","false");store.put("sound","false");List<Engine.Card> cards=new ArrayList<>();
        for(int i=0;i<CYCLES;i++){Engine.Card card=new Engine.Card();card.deck="stress";card.id="card-"+i;card.title="Stress card "+i;card.text="Synthetic overlay stress payload "+i;cards.add(card);}assertTrue(store.importCards(Map.of("learning/decks/stress.yaml",cards)).isEmpty());store.setMode("agent_debug");store.recompute();
        Intent launch=new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);c.startActivity(launch);Thread.sleep(700);
        UiObject2 pause=device.wait(Until.findObject(By.text("Поставить на паузу")),UI_TIMEOUT);assertNotNull("Stateful pause control must be visible",pause);pause.click();UiObject2 resume=device.wait(Until.findObject(By.text("Продолжить показы")),UI_TIMEOUT);assertNotNull("Pause tap must visibly change the control",resume);assertEquals("true",store.value("paused","false"));resume.click();UiObject2 pauseAgain=device.wait(Until.findObject(By.text("Поставить на паузу")),UI_TIMEOUT);assertNotNull("Resume tap must visibly restore the control",pauseAgain);assertEquals("false",store.value("paused","false"));
        Delivery.start(c);Thread.sleep(300);device.pressHome();waitActivityHidden();Thread.sleep(300);writeDebug(c,device,store,"ready_on_launcher");
        int screenCycles=0,protectedTransitions=0,rotations=0,delayedChecks=0;
        try{
            for(int i=0;i<CYCLES;i++){
                store.forceDue();changed(c);waitPresentation(store);waitControls();waitWindow(device,true);writeDebug(c,device,store,"cycle_"+i+"_presented");Store.Pending presented=store.pending();assertNotNull("Due card must become pending",presented);assertTrue("Pending was not actually presented; delivery_error="+store.value("delivery_error",""),presented.shown>0);assertGeometry(store);assertTrue("Reaction centers must be distinct",OverlayService.rememberX!=OverlayService.repeatX);
                if(i==4||i==15||i==32){String pendingId=presented.id;int before=store.eventCount(false);long delay=i==4?350:(i==15?1500:3000);Thread.sleep(delay);changed(c);Thread.sleep(150);assertNotNull(store.pending());assertEquals("Human delay must not replace pending",pendingId,store.pending().id);assertEquals("Human delay must not synthesize a response",before,store.eventCount(false));waitWindow(device,true);delayedChecks++;}
                if(i==8||i==27){String pendingId=presented.id;int before=store.eventCount(false);device.executeShellCommand("am start -a android.settings.SETTINGS");Thread.sleep(500);waitActivityHidden();waitWindow(device,false);assertEquals("Protected Settings window must not consume pending",pendingId,store.pending().id);assertEquals(before,store.eventCount(false));device.pressHome();Thread.sleep(350);changed(c);waitWindow(device,true);waitControls();assertEquals(pendingId,store.pending().id);protectedTransitions++;}
                if(i==10||i==29){String pendingId=presented.id;device.sleep();Thread.sleep(450);assertEquals("Pending must survive screen-off",pendingId,store.pending().id);device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");Thread.sleep(450);device.pressHome();changed(c);assertEquals("Pending must survive screen-on",pendingId,store.pending().id);waitWindow(device,true);waitControls();screenCycles++;}
                if(i==20){device.setOrientationLeft();Thread.sleep(650);changed(c);waitControls();waitWindow(device,true);assertGeometry(store);device.setOrientationNatural();Thread.sleep(650);changed(c);waitControls();waitWindow(device,true);assertGeometry(store);device.unfreezeRotation();rotations++;}
                boolean repeat=i%3==0;tapReaction(device,repeat);waitEvents(store,i+1);assertNull("Answered pending must close",store.pending());assertTrue("Reaction tap must be visibly/diagnostically committed",store.value("last_ui_action","").contains("reaction_"+(repeat?"repeat":"remember")+"_committed"));assertEquals("Stress must not write verified normal progress",0,store.eventCount(true));
            }
            assertEquals(CYCLES,store.eventCount(false));assertEquals("agent_debug",store.mode());assertNull("Finite agent session must stop after one pass",store.decision().due);assertEquals("test_session_complete",store.decision().feasibility);assertTrue("Overlay delivery error: "+store.value("delivery_error",""),store.value("delivery_error","").isEmpty());
            List<Store.Batch> batches=store.batches();assertEquals("40 synthetic events should fit one outbox batch",1,batches.size());assertEquals("agent_debug",batches.get(0).mode);File evidence=new File(c.getExternalFilesDir(null),"overlay-stress-summary.txt");try(FileWriter out=new FileWriter(evidence,false)){out.write("result=PASS\ncycles="+CYCLES+"\nevents="+store.eventCount(false)+"\nmode="+store.mode()+"\n");out.write("delayed_pending_checks="+delayedChecks+"\nscreen_off_on="+screenCycles+"\nprotected_settings_transitions="+protectedTransitions+"\nrotations="+rotations+"\nfinite_session=1\nstateful_pause_control=1\nauto_active_without_enable=1\n");out.write("geometry="+store.value("overlay_geometry","")+"\nalarm_precision="+store.value("alarm_precision","")+"\n");}
        }finally{writeDebug(c,device,store,"finally");store.put("paused","true");changed(c);Thread.sleep(250);}
    }
}
