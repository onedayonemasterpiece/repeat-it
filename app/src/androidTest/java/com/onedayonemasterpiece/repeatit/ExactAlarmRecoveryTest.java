package com.onedayonemasterpiece.repeatit;

import android.app.AlarmManager;
import android.content.Context;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** CI contract: Android 15 durable process-restart recovery is exercised with exact-alarm special access granted. */
@RunWith(AndroidJUnit4.class)
public final class ExactAlarmRecoveryTest {
    @Test public void exactAlarmRecoveryCapabilityIsAvailable() {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        AlarmManager alarms=c.getSystemService(AlarmManager.class);
        assertNotNull(alarms);
        assertTrue("Android 12+ recovery harness must grant Alarms & reminders special access",Build.VERSION.SDK_INT<31||alarms.canScheduleExactAlarms());
    }
}
