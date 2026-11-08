package com.github.catvod.crawler;

import android.app.Activity;
import com.github.catvod.utils.Dialog;
import java.util.Map;
import org.json.JSONObject;

/** Compile-only public Spider descriptors from FongMi/TV@4512b4c9. */
public abstract class Spider implements AutoCloseable {
    public String siteKey;
    public JSONObject dialog(JSONObject options) throws Exception { return null; }
    public JSONObject dialog(JSONObject options, Dialog.Callback callback) throws Exception { return null; }
    public final void closeDialog() {}
    public Activity getActivity() { return null; }
    public String proxyUrl() { return null; }
    public String proxyUrl(Map<String, String> params, boolean local) { return null; }
    public void close() {}
}
