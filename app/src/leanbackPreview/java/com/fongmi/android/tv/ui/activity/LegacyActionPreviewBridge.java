package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;

/** The static Java ABI used by older JARs, including a deliberately cached wrong Activity. */
public final class LegacyActionPreviewBridge {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Activity cachedActivity;

    public static void setCachedActivity(Activity activity) { cachedActivity = activity; }
    public static Activity getActivity() { return cachedActivity; }
    public static Activity getConfigActivity() { return cachedActivity; }
    public static void run(Runnable runnable, int delay) { MAIN.postDelayed(runnable, delay); }
    public static boolean post(Runnable runnable) { return MAIN.post(runnable); }
    public static void execute(Runnable runnable) { new Thread(runnable, "legacy-source-fixture").start(); }

    public static AlertDialog createDialog(Activity context, String title, String button, DialogInterface.OnClickListener click) {
        return new AlertDialog.Builder(context).setTitle(title).setPositiveButton(button, click).create();
    }

    public static AlertDialog createLoginDialog(Activity context, String title) {
        return new AlertDialog.Builder(context).setTitle(title).setPositiveButton("Done", null).create();
    }
}
