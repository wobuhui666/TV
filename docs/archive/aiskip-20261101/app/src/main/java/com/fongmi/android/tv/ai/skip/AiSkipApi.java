package com.fongmi.android.tv.ai.skip;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class AiSkipApi {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType WAV = MediaType.parse("audio/wav");
    private final Gson gson = App.gson();

    public boolean health() throws IOException {
        Request request = base("/v1/health").get().build();
        try (Response response = execute(request)) {
            return response.isSuccessful();
        }
    }

    public AiSkipResult find(String mediaKey) throws IOException {
        return findPath("/v1/jobs/media/" + encode(mediaKey));
    }

    public AiSkipResult findSeries(String seriesKey) throws IOException {
        return findPath("/v1/jobs/series/" + encode(seriesKey));
    }

    private AiSkipResult findPath(String path) throws IOException {
        Request request = base(path).get().build();
        try (Response response = execute(request)) {
            if (response.code() == 404) return null;
            if (!response.isSuccessful()) throw new IOException("api_" + response.code());
            return gson.fromJson(response.body().string(), AiSkipResult.class);
        }
    }

    public String upload(byte[] wav) throws IOException {
        String id = UUID.randomUUID().toString().replace("-", "");
        Request request = base("/v1/uploads/" + id).put(RequestBody.create(wav, WAV)).build();
        try (Response response = execute(request)) {
            if (!response.isSuccessful()) throw new IOException("upload_" + response.code());
            JsonObject object = gson.fromJson(response.body().string(), JsonObject.class);
            return object.get("objectKey").getAsString();
        }
    }

    public AiSkipResult create(String mediaKey, String seriesKey, String episode, long durationMs, List<Sample> samples) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("mediaKey", mediaKey);
        body.addProperty("seriesKey", seriesKey);
        body.addProperty("episode", episode);
        body.addProperty("durationMs", durationMs);
        var array = new com.google.gson.JsonArray();
        for (Sample sample : samples) {
            JsonObject item = new JsonObject();
            item.addProperty("side", sample.side());
            item.addProperty("startMs", sample.startMs());
            item.addProperty("durationMs", sample.durationMs());
            item.addProperty("objectKey", sample.objectKey());
            array.add(item);
        }
        body.add("samples", array);
        Request request = base("/v1/jobs").post(RequestBody.create(body.toString(), JSON)).build();
        return parse(request);
    }

    public AiSkipResult getJob(String jobId) throws IOException {
        return get("/v1/jobs/" + encode(jobId));
    }

    public void feedback(String jobId, long openingMs, long endingMs) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("openingMs", Math.max(0, openingMs));
        body.addProperty("endingMs", Math.max(0, endingMs));
        Request request = base("/v1/jobs/" + encode(jobId) + "/feedback")
                .post(RequestBody.create(body.toString(), JSON)).build();
        try (Response response = execute(request)) {
            if (!response.isSuccessful()) throw new IOException("feedback_" + response.code());
        }
    }

    private AiSkipResult get(String path) throws IOException {
        return parse(base(path).get().build());
    }

    private AiSkipResult parse(Request request) throws IOException {
        try (Response response = execute(request)) {
            if (!response.isSuccessful()) throw new IOException("api_" + response.code());
            return gson.fromJson(response.body().string(), AiSkipResult.class);
        }
    }

    private Response execute(Request request) throws IOException {
        return OkHttp.client(15_000L).newCall(request).execute();
    }

    private Request.Builder base(String path) throws IOException {
        String url = AiSkipSettings.getBaseUrl();
        String token = AiSkipSettings.getToken();
        if (url.isEmpty() || token.isEmpty()) throw new IOException("ai_skip_not_configured");
        return new Request.Builder().url(url + path).header("Authorization", "Bearer " + token);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record Sample(String side, long startMs, long durationMs, String objectKey) {
    }
}
