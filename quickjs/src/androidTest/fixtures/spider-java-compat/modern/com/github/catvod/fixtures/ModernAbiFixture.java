package com.github.catvod.fixtures;

import android.app.Activity;
import com.github.catvod.Runtime;
import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.Dialog;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.json.JSONObject;

/** This class is compiled against only the fixed upstream declarations. */
public final class ModernAbiFixture extends Spider implements Dialog.Callback, Consumer<String>, Supplier<Activity>,
        BiFunction<String, Function<String, CompletableFuture<String>>, CompletableFuture<String>> {
    public String toastValue;
    public int callbackCount;

    public String probe() throws Exception {
        Runtime.set(Runtime.context());
        Runtime.setToast(this);
        Runtime.toast("modern-fixture");
        Runtime.setActivity(this);
        JSONObject plain = dialog(new JSONObject().put("title", "modern-plain"));
        JSONObject withCallback = dialog(new JSONObject().put("title", "modern-callback"), this);
        HashMap<String, String> params = new HashMap<>();
        params.put("q", "modern & 中文");
        Runtime.setDialog(this);
        JSONObject runtimeReply = new JSONObject(Runtime.dialog("{}", null).get());
        return new JSONObject().put("context", Runtime.context().getPackageName())
                .put("activityEmpty", Runtime.activity() == null && getActivity() == null)
                .put("toast", toastValue).put("proxy", proxyUrl())
                .put("proxyParams", proxyUrl(params, true)).put("plain", plain)
                .put("callback", withCallback).put("callbackCount", callbackCount)
                .put("runtimeReply", runtimeReply).toString();
    }

    public JSONObject pendingDialog() throws Exception { return dialog(new JSONObject().put("title", "pending")); }
    public void closeOwnDialog() { closeDialog(); }
    public void closeOwner() { close(); }
    @Override public JSONObject onAction(JSONObject event) throws Exception {
        callbackCount++;
        return new JSONObject().put("received", event.getString("action"));
    }
    @Override public void accept(String value) { toastValue = value; }
    @Override public Activity get() { return null; }
    @Override public CompletableFuture<String> apply(String options, Function<String, CompletableFuture<String>> callback) {
        return CompletableFuture.completedFuture("{\"runtime\":\"fixture-host\"}");
    }
}
