package com.fongmi.android.tv.ai.subtitle;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Trans;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Bounded, serial subtitle translation dispatcher.  One request is allowed in flight and up to
 * three newer cues wait behind it.  This avoids the cancel-previous starvation which used to make
 * dense German commentary repeatedly abort the preceding translation before it could produce an
 * atomic bilingual cue.
 */
public final class OpenAiSubtitleTranslator {
    private static final String TAG = "AiSubtitle";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_PENDING_TRANSLATIONS = 3;

    interface Config {
        AiSubtitleSettings.TranslationProvider provider();

        String apiKey();

        String baseUrl();

        String model();

        boolean thinkingEnabled();

        boolean contextEnabled();

        default String mTranUrl() {
            return AiSubtitleSettings.getMTranUrl();
        }

        default String mTranToken() {
            return SecretStore.getMTranToken();
        }
    }

    public interface ResultCallback {
        void onSuccess(String source, String translated);

        void onFailure(String source, String message);
    }

    public interface TestCallback {
        void onResult(boolean available, long latencyMs, String detail);
    }

    private record History(String source, String translated) {
    }

    private record PendingTranslation(AiLanguage language, String source, ResultCallback callback,
                                      AiSubtitleSettings.TranslationProvider provider, long epoch) {
    }

    private final Object lock = new Object();
    private final Object dispatchLock = new Object();
    private final Deque<History> history = new ArrayDeque<>(2);
    private final Deque<PendingTranslation> pending = new ArrayDeque<>(MAX_PENDING_TRANSLATIONS);
    private final AtomicLong sequence = new AtomicLong();
    private final Config config;
    private final OkHttpClient client;
    private final YoudaoSubtitleTranslator youdao;
    private volatile Call activeCall;
    private PendingTranslation activeTranslation;

    public OpenAiSubtitleTranslator() {
        this(new SettingsConfig(), OkHttp.client(8_000L));
    }

    OpenAiSubtitleTranslator(Config config, OkHttpClient client) {
        this.config = config;
        this.client = client;
        this.youdao = new YoudaoSubtitleTranslator(client);
    }

    public void reset() {
        sequence.incrementAndGet();
        synchronized (dispatchLock) {
            activeTranslation = null;
            pending.clear();
        }
        cancelActive();
        youdao.cancel();
        synchronized (lock) {
            history.clear();
        }
    }

    public void translate(AiLanguage language, String source, ResultCallback callback) {
        if (source == null || source.isBlank()) return;
        AiSubtitleSettings.TranslationProvider provider = config.provider();
        if (provider == AiSubtitleSettings.TranslationProvider.OFF) {
            callback.onSuccess(source, source);
            return;
        }
        PendingTranslation task = new PendingTranslation(language, source, callback, provider,
                sequence.get());
        PendingTranslation start = null;
        PendingTranslation dropped = null;
        synchronized (dispatchLock) {
            if (activeTranslation == null) {
                activeTranslation = task;
                start = task;
            } else {
                if (pending.size() >= MAX_PENDING_TRANSLATIONS) dropped = pending.removeFirst();
                pending.addLast(task);
            }
        }
        if (dropped != null) Log.i(TAG, "translation backlog dropped source cue epoch=" + dropped.epoch);
        if (start != null) dispatch(start);
    }

    /** Sends a real, short translation so model availability and end-to-end latency are tested. */
    public void test(AiLanguage language, TestCallback callback) {
        long started = SystemClock.elapsedRealtime();
        String sample = switch (language) {
            case MANDARIN -> "你好";
            case CANTONESE -> "今日天气几好";
            case GERMAN -> "Guten Tag";
            case ENGLISH -> "Hello";
            case FRENCH -> "Bonjour";
            case SPANISH -> "Hola";
            case JAPANESE -> "こんにちは";
        };
        translate(language, sample, new ResultCallback() {
            @Override
            public void onSuccess(String source, String translated) {
                long elapsed = SystemClock.elapsedRealtime() - started;
                callback.onResult(translated != null && !translated.isBlank(), elapsed, "");
            }

            @Override
            public void onFailure(String source, String message) {
                callback.onResult(false, SystemClock.elapsedRealtime() - started, message);
            }
        });
    }

    private void dispatch(PendingTranslation task) {
        ResultCallback completion = completing(task);
        switch (task.provider) {
            case DEFAULT -> youdao.translate(task.language, task.source, completion);
            case MTRAN -> translateMTran(task.language, task.source, task.epoch, completion);
            case OPENAI -> translateOpenAi(task.language, task.source, task.epoch, completion);
            default -> completion.onSuccess(task.source, task.source);
        }
    }

    private ResultCallback completing(PendingTranslation task) {
        return new ResultCallback() {
            @Override
            public void onSuccess(String source, String translated) {
                try {
                    if (task.epoch == sequence.get()) task.callback.onSuccess(source, translated);
                } finally {
                    complete(task);
                }
            }

            @Override
            public void onFailure(String source, String message) {
                try {
                    if (task.epoch == sequence.get()) task.callback.onFailure(source, message);
                } finally {
                    complete(task);
                }
            }
        };
    }

    private void complete(PendingTranslation task) {
        PendingTranslation next = null;
        synchronized (dispatchLock) {
            if (activeTranslation != task) return;
            activeTranslation = null;
            activeCall = null;
            while (!pending.isEmpty()) {
                PendingTranslation candidate = pending.removeFirst();
                if (candidate.epoch == sequence.get()) {
                    activeTranslation = candidate;
                    next = candidate;
                    break;
                }
            }
        }
        if (next != null) dispatch(next);
    }

    private void translateMTran(AiLanguage language, String source, long requestId, ResultCallback callback) {
        try {
            JSONObject body = new JSONObject()
                    .put("from", language.mTranCode())
                    .put("to", "zh-Hans")
                    .put("text", source)
                    .put("html", false);
            Request.Builder request = new Request.Builder()
                    .url(mTranEndpoint(config.mTranUrl()))
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), JSON));
            if (!config.mTranToken().isBlank()) request.header("Authorization", "Bearer " + config.mTranToken().trim());
            enqueue(request.build(), requestId, source, callback, response -> response.optString("result", "").trim());
        } catch (Exception error) {
            fail(callback, source, "MTranServer 配置无效", error);
        }
    }

    private void translateOpenAi(AiLanguage language, String source, long requestId, ResultCallback callback) {
        if (config.apiKey().isEmpty()) {
            fail(callback, source, "未配置 API Key", null);
            return;
        }
        try {
            Request request = new Request.Builder()
                    .url(endpoint(config.baseUrl()))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(buildRequestJson(language, source).toString(), JSON))
                    .build();
            enqueue(request, requestId, source, callback, response -> {
                JSONArray choices = response.optJSONArray("choices");
                return choices == null || choices.length() == 0 ? "" :
                        choices.getJSONObject(0).getJSONObject("message").optString("content", "").trim();
            });
        } catch (Exception error) {
            fail(callback, source, "大模型配置无效", error);
        }
    }

    private interface Parser {
        String parse(JSONObject response) throws Exception;
    }

    private void enqueue(Request request, long requestId, String source, ResultCallback callback, Parser parser) {
        Call call = client.newCall(request);
        activeCall = call;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException error) {
                if (requestId != sequence.get() || call.isCanceled()) return;
                fail(callback, source, "翻译网络错误", error);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (response) {
                    if (requestId != sequence.get()) return;
                    if (!response.isSuccessful()) {
                        fail(callback, source, "翻译接口 HTTP " + response.code(), null);
                        return;
                    }
                    ResponseBody body = response.body();
                    String translated = body == null ? "" : parser.parse(new JSONObject(body.string()));
                    if (translated.isBlank()) {
                        fail(callback, source, "翻译结果为空", null);
                        return;
                    }
                    if (config.provider() == AiSubtitleSettings.TranslationProvider.OPENAI && config.contextEnabled()) {
                        synchronized (lock) {
                            while (history.size() >= 2) history.removeFirst();
                            history.addLast(new History(source, translated));
                        }
                    }
                    callback.onSuccess(source, translated);
                } catch (Exception error) {
                    if (requestId == sequence.get()) fail(callback, source, "翻译响应格式错误", error);
                }
            }
        });
    }

    private static void fail(ResultCallback callback, String source, String message, Throwable error) {
        if (error == null) Log.w(TAG, message);
        else Log.w(TAG, message + ": " + error.getClass().getSimpleName());
        callback.onFailure(source, message);
    }

    private void cancelActive() {
        Call call = activeCall;
        activeCall = null;
        if (call != null) call.cancel();
    }

    JSONObject buildRequestJson(AiLanguage language, String source) throws Exception {
        JSONObject root = new JSONObject();
        root.put("model", config.model());
        root.put("stream", false);
        root.put("max_tokens", 192);
        root.put("temperature", 0);
        // DeepSeek documents this extension explicitly.  Do not send an unknown field to unrelated
        // OpenAI-compatible servers, which commonly reject extra request properties.
        String model = config.model().toLowerCase(java.util.Locale.ROOT);
        String baseUrl = config.baseUrl().toLowerCase(java.util.Locale.ROOT);
        if (model.contains("deepseek") || baseUrl.contains("deepseek")) {
            root.put("thinking", new JSONObject().put("type", config.thinkingEnabled() ? "enabled" : "disabled"));
        }

        JSONArray messages = new JSONArray();
        messages.put(message("system", "你是实时字幕翻译器。把输入的" + language.label() +
                "字幕翻译成自然、简洁的简体中文。只输出译文，不解释，不添加引号。"));
        if (config.contextEnabled()) {
            synchronized (lock) {
                for (History item : history) {
                    messages.put(message("user", item.source));
                    messages.put(message("assistant", item.translated));
                }
            }
        } else {
            synchronized (lock) {
                history.clear();
            }
        }
        messages.put(message("user", source));
        root.put("messages", messages);
        return root;
    }

    private static JSONObject message(String role, String content) throws Exception {
        return new JSONObject().put("role", role).put("content", content);
    }

    static String endpoint(String baseUrl) {
        String base = normalizeBase(baseUrl);
        if (base.endsWith("/chat/completions")) return base;
        if (base.isEmpty()) return "https://api.deepseek.com/chat/completions";
        return base + "/chat/completions";
    }

    static String mTranEndpoint(String baseUrl) {
        String base = normalizeBase(baseUrl);
        if (base.endsWith("/translate")) return base;
        if (base.isEmpty()) return "http://localhost:8989/translate";
        return base + "/translate";
    }

    private static String normalizeBase(String value) {
        String base = value == null ? "" : value.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    static String toSimplified(String source) {
        if (source == null || source.isEmpty()) return "";
        return Trans.t2s(false, source);
    }

    private static final class SettingsConfig implements Config {
        @Override
        public AiSubtitleSettings.TranslationProvider provider() {
            return AiSubtitleSettings.getTranslationProvider();
        }

        @Override
        public String apiKey() {
            return SecretStore.getApiKey();
        }

        @Override
        public String baseUrl() {
            return AiSubtitleSettings.getBaseUrl();
        }

        @Override
        public String model() {
            return AiSubtitleSettings.getTranslationModel();
        }

        @Override
        public boolean thinkingEnabled() {
            return AiSubtitleSettings.isThinkingEnabled();
        }

        @Override
        public boolean contextEnabled() {
            return AiSubtitleSettings.isContextEnabled();
        }
    }
}
