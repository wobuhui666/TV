package com.fongmi.android.tv.test;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.*;

/** Opt-in screenshots of synthetic fixtures, stored only in the validation app's files. */
public final class NativeUiEvidence {
    private NativeUiEvidence() {}

    /** Clear a suite's previous screenshots through the app's own scoped-storage context. */
    public static void clear() {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("native_ui_evidence"))) return;
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        String applicationId = instrumentation.getTargetContext().getPackageName();
        assertTrue("Evidence cleanup is restricted to isolated validation packages",
                applicationId.equals("com.fongmi.android.tv.preview") || applicationId.equals("com.fongmi.android.tv.sourceprobe"));
        File external = instrumentation.getTargetContext().getExternalFilesDir(null);
        File directory = instrumentation.getTargetContext().getExternalFilesDir("native-usability-evidence");
        assertNotNull("Validation external files directory", external);
        assertNotNull("Validation evidence directory", directory);
        assertTrue("Create the evidence directory", directory.isDirectory() || directory.mkdirs());
        try {
            assertEquals("Cleanup stays in the canonical isolated evidence directory",
                    new File(external.getCanonicalFile(), "native-usability-evidence"), directory.getCanonicalFile());
            File[] entries = directory.listFiles();
            assertNotNull("Read previous evidence files", entries);
            for (File entry : entries) deleteEvidenceEntry(directory, entry);
            File[] remaining = directory.listFiles();
            assertNotNull("Read the cleared evidence directory", remaining);
            assertEquals("Previous evidence is completely cleared", 0, remaining.length);
        } catch (IOException error) {
            throw new AssertionError("Cannot clear native UI evidence", error);
        }
    }

    private static void deleteEvidenceEntry(File parent, File entry) throws IOException {
        assertEquals("Evidence cleanup cannot follow a path outside its parent",
                parent.getCanonicalFile(), entry.getCanonicalFile().getParentFile());
        if (entry.isDirectory()) {
            File[] entries = entry.listFiles();
            assertNotNull("Read nested evidence files", entries);
            for (File child : entries) deleteEvidenceEntry(entry, child);
        }
        assertTrue("Delete previous evidence: " + entry.getName(), entry.delete());
    }

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
        if (screenshot == null) {
            // Some API 24 emulator renderers cannot expose UiAutomation's screenshot buffer.
            // Read the actual display's native PNG through the same automation shell identity.
            ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand("screencap -p");
            assertNotNull("Open the native screenshot stream", descriptor);
            try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                screenshot = BitmapFactory.decodeStream(input);
            } catch (IOException error) {
                throw new AssertionError("Cannot read the native display screenshot", error);
            }
        }
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
