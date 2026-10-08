package com.fongmi.android.tv.test;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Looper;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.*;

/** Opt-in screenshots of synthetic fixtures, stored only in the validation app's files. */
public final class NativeUiEvidence {
    private NativeUiEvidence() {}

    public static void capture(String name) {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("native_ui_evidence"))) return;
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        String applicationId = instrumentation.getTargetContext().getPackageName();
        assertTrue("Screenshots are restricted to isolated validation packages",
                applicationId.equals("com.fongmi.android.tv.preview") || applicationId.equals("com.fongmi.android.tv.sourceprobe"));
        assertTrue("Use a fixed evidence filename", name.matches("[a-z0-9-]{1,64}"));
        assertNotSame("Capture after returning from UI operations", Looper.getMainLooper(), Looper.myLooper());
        instrumentation.waitForIdleSync();
        SystemClock.sleep(350);
        File directory = instrumentation.getTargetContext().getExternalFilesDir("native-usability-evidence");
        assertNotNull("Validation external files directory", directory);
        assertTrue("Create the evidence directory", directory.isDirectory() || directory.mkdirs());
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("Capture the actual Android window", screenshot);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue("Encode native UI evidence", screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
            output.getFD().sync();
        } catch (IOException error) {
            throw new AssertionError("Cannot save native UI evidence", error);
        } finally {
            screenshot.recycle();
        }
    }
}
