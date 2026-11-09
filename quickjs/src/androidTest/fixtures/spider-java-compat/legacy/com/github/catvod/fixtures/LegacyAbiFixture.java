package com.github.catvod.fixtures;

import android.app.Activity;
import com.github.catvod.Init;
import com.github.catvod.crawler.Spider;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.json.JSONObject;

/** This class is compiled against only the standalone legacy declarations. */
public final class LegacyAbiFixture extends Spider implements Consumer<String>, Supplier<Activity> {
    public String toastValue;

    public String probe() throws Exception {
        Init.set(Init.context());
        Init.setToast(this);
        Init.toast("legacy-fixture");
        Init.setActivity(this);
        HashMap<String, String> params = new HashMap<>();
        params.put("q", "legacy & 中文");
        JSONObject answer = dialog.show(new JSONObject().put("title", "legacy"));
        return new JSONObject().put("client", Spider.client() != null)
                .put("context", Init.context().getPackageName())
                .put("activityEmpty", Init.activity() == null && getActivity() == null)
                .put("toast", toastValue).put("proxy", getProxyUrl())
                .put("proxyParams", getProxyUrl(params)).put("answer", answer).toString();
    }

    public CompletableFuture<String> pendingDialog() { return dialog.open("{}", null); }
    public void closeOwner() { close(); }
    @Override public void accept(String value) { toastValue = value; }
    @Override public Activity get() { return null; }
}
