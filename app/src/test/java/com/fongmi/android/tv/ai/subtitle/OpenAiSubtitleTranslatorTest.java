package com.fongmi.android.tv.ai.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public class OpenAiSubtitleTranslatorTest {
    private MockWebServer server;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void defaultRealtimeRequestDisablesThinkingAndHistory() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"你好世界\"}}]}"));
        TestConfig config = new TestConfig(false, false, server.url("/").toString());
        OpenAiSubtitleTranslator translator = new OpenAiSubtitleTranslator(config, new OkHttpClient());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        translator.translate(AiLanguage.ENGLISH, "Hello world", callback(latch, result));
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals("你好世界", result.get());

        RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
        JSONObject body = new JSONObject(request.getBody().readUtf8());
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
        assertEquals("deepseek-v4-flash", body.getString("model"));
        JSONArray messages = body.getJSONArray("messages");
        assertEquals(2, messages.length());
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        assertEquals("Hello world", messages.getJSONObject(1).getString("content"));
        assertEquals("Bearer test-key", request.getHeader("Authorization"));
    }

    @Test
    public void contextAndThinkingAreIndependentOptInOptions() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"第一句\"}}]}"));
        server.enqueue(new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"第二句\"}}]}"));
        TestConfig config = new TestConfig(true, true, server.url("/").toString());
        OpenAiSubtitleTranslator translator = new OpenAiSubtitleTranslator(config, new OkHttpClient());

        CountDownLatch first = new CountDownLatch(1);
        translator.translate(AiLanguage.GERMAN, "Erster Satz", callback(first, new AtomicReference<>()));
        assertTrue(first.await(3, TimeUnit.SECONDS));
        server.takeRequest(3, TimeUnit.SECONDS);

        CountDownLatch second = new CountDownLatch(1);
        translator.translate(AiLanguage.GERMAN, "Zweiter Satz", callback(second, new AtomicReference<>()));
        assertTrue(second.await(3, TimeUnit.SECONDS));
        RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
        JSONObject body = new JSONObject(request.getBody().readUtf8());
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"));
        assertEquals(4, body.getJSONArray("messages").length());
    }

    @Test
    public void traditionalChineseIsConvertedWithoutPlatformApi() {
        assertEquals("繁体字幕", OpenAiSubtitleTranslator.toSimplified("繁體字幕"));
        assertEquals("", OpenAiSubtitleTranslator.toSimplified(""));
        assertEquals("", OpenAiSubtitleTranslator.toSimplified(null));
    }


    @Test
    public void mTranUsesOfficialV4ProtocolAndParsesResult() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"result\":\"Guten Tag 的中文\"}"));
        OpenAiSubtitleTranslator.Config config = new TestConfig(false, false, "https://unused.invalid") {
            @Override public AiSubtitleSettings.TranslationProvider provider() { return AiSubtitleSettings.TranslationProvider.MTRAN; }
            @Override public String mTranUrl() { return server.url("/").toString(); }
            @Override public String mTranToken() { return "mtran-test-token"; }
        };
        OpenAiSubtitleTranslator translator = new OpenAiSubtitleTranslator(config, new OkHttpClient());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        translator.translate(AiLanguage.GERMAN, "Guten Tag", callback(latch, result));
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals("Guten Tag 的中文", result.get());

        RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
        assertEquals("/translate", request.getPath());
        assertEquals("Bearer mtran-test-token", request.getHeader("Authorization"));
        JSONObject body = new JSONObject(request.getBody().readUtf8());
        assertEquals("de", body.getString("from"));
        assertEquals("zh-Hans", body.getString("to"));
        assertEquals("Guten Tag", body.getString("text"));
    }

    @Test
    public void denseSpeechQueuesTranslationsWithoutCancelingEveryPreviousCue() throws Exception {
        for (int i = 1; i <= 4; i++) {
            server.enqueue(new MockResponse()
                    .setBodyDelay(120, TimeUnit.MILLISECONDS)
                    .setBody("{\"choices\":[{\"message\":{\"content\":\"译文" + i + "\"}}]}"));
        }
        TestConfig config = new TestConfig(false, false, server.url("/").toString());
        OpenAiSubtitleTranslator translator = new OpenAiSubtitleTranslator(config, new OkHttpClient());
        CountDownLatch latch = new CountDownLatch(4);
        AtomicInteger successes = new AtomicInteger();
        for (int i = 1; i <= 4; i++) {
            translator.translate(AiLanguage.GERMAN, "Satz " + i, new OpenAiSubtitleTranslator.ResultCallback() {
                @Override public void onSuccess(String source, String translated) {
                    successes.incrementAndGet();
                    latch.countDown();
                }
                @Override public void onFailure(String source, String message) { latch.countDown(); }
            });
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(4, successes.get());
        assertEquals(4, server.getRequestCount());
    }

    private static OpenAiSubtitleTranslator.ResultCallback callback(CountDownLatch latch, AtomicReference<String> result) {
        return new OpenAiSubtitleTranslator.ResultCallback() {
            @Override
            public void onSuccess(String source, String translated) {
                result.set(translated);
                latch.countDown();
            }

            @Override
            public void onFailure(String source, String message) {
                result.set(message);
                latch.countDown();
            }
        };
    }

    private static class TestConfig implements OpenAiSubtitleTranslator.Config {
        private final boolean thinkingEnabled;
        private final boolean contextEnabled;
        private final String baseUrl;

        TestConfig(boolean thinkingEnabled, boolean contextEnabled, String baseUrl) {
            this.thinkingEnabled = thinkingEnabled;
            this.contextEnabled = contextEnabled;
            this.baseUrl = baseUrl;
        }

        @Override public boolean thinkingEnabled() { return thinkingEnabled; }
        @Override public boolean contextEnabled() { return contextEnabled; }
        @Override public String baseUrl() { return baseUrl; }
        @Override
        public AiSubtitleSettings.TranslationProvider provider() {
            return AiSubtitleSettings.TranslationProvider.OPENAI;
        }

        @Override
        public String apiKey() {
            return "test-key";
        }

        @Override
        public String model() {
            return "deepseek-v4-flash";
        }
    }
}
