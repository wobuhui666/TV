package com.fongmi.android.tv.playback;

import com.fongmi.android.tv.bean.PlaybackWebhookDelivery;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class PlaybackWebhookDispatcher {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final long[] BACKOFF_MS = {1_000, 2_000, 4_000};

    private PlaybackWebhookDispatcher() {
    }

    public static String enqueue(String endpointId, String endpointUrl, String payload, int retries) {
        return enqueue(UUID.randomUUID().toString(), endpointId, endpointUrl, payload, retries);
    }

    public static String enqueue(String eventId, String endpointId, String endpointUrl, String payload, int retries) {
        PlaybackWebhookDelivery item = new PlaybackWebhookDelivery();
        item.deliveryId = UUID.randomUUID().toString();
        item.eventId = eventId == null || eventId.isEmpty() ? UUID.randomUUID().toString() : eventId;
        item.endpointId = endpointId == null ? "" : endpointId;
        item.endpointUrl = endpointUrl == null ? "" : endpointUrl;
        item.payload = payload == null ? "{}" : payload;
        item.maxRetries = Math.clamp(retries, 0, 3);
        item.nextAttemptAt = System.currentTimeMillis();
        AppDatabase.get().getPlaybackWebhookDeliveryDao().insertOrUpdate(item);
        Task.execute(PlaybackWebhookDispatcher::drain);
        return item.eventId;
    }

    public static void drain() {
        AppDatabase.get().getPlaybackWebhookDeliveryDao().deleteDeadLettersBefore(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7));
        List<PlaybackWebhookDelivery> items = AppDatabase.get().getPlaybackWebhookDeliveryDao().ready(System.currentTimeMillis(), 20);
        for (PlaybackWebhookDelivery item : items) deliver(item);
    }

    public static void retryFailed() {
        for (PlaybackWebhookDelivery item : AppDatabase.get().getPlaybackWebhookDeliveryDao().failed()) {
            item.attempts = 0;
            item.failedAt = 0;
            item.lastError = "";
            item.nextAttemptAt = System.currentTimeMillis();
            AppDatabase.get().getPlaybackWebhookDeliveryDao().insertOrUpdate(item);
        }
        Task.execute(PlaybackWebhookDispatcher::drain);
    }

    private static void deliver(PlaybackWebhookDelivery item) {
        PlaybackSyncEndpoint endpoint = PlaybackSyncStore.get().stream().filter(value -> value.id.equals(item.endpointId)).findFirst().orElse(null);
        Request.Builder builder = new Request.Builder().url(item.endpointUrl)
                .header("Idempotency-Key", item.eventId)
                .header("X-WebHTV-Event-Id", item.eventId)
                .header("X-WebHTV-Config-Key", PlaybackConfigIdentity.currentKey());
        if (endpoint != null && endpoint.token != null && !endpoint.token.isEmpty()) builder.header("X-WebHTV-Token", endpoint.token);
        Request request = builder.post(RequestBody.create(item.payload, JSON)).build();
        try (Response response = OkHttp.client().newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            AppDatabase.get().getPlaybackWebhookDeliveryDao().delete(item.deliveryId);
        } catch (Throwable error) {
            item.lastError = error.getClass().getSimpleName();
            if (item.attempts >= item.maxRetries) {
                item.failedAt = System.currentTimeMillis();
            } else {
                item.nextAttemptAt = System.currentTimeMillis() + BACKOFF_MS[Math.min(item.attempts, BACKOFF_MS.length - 1)];
                item.attempts++;
            }
            AppDatabase.get().getPlaybackWebhookDeliveryDao().insertOrUpdate(item);
            if (item.failedAt == 0) Task.schedule(PlaybackWebhookDispatcher::drain,
                    Math.max(0, item.nextAttemptAt - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        }
    }
}
