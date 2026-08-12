package com.fongmi.android.tv.playback;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class PlaybackSyncProtocol {

    private PlaybackSyncProtocol() {
    }

    static Page parsePage(String json, long currentCursor) {
        JsonObject body = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        if (body.has("schema") && !"webhtv.playback.v1".equals(body.get("schema").getAsString())) throw new IllegalStateException("协议版本无效");
        long nextCursor = body.has("cursor") ? body.get("cursor").getAsLong() : currentCursor;
        if (nextCursor < currentCursor) throw new IllegalStateException("游标倒退");
        java.util.List<JsonElement> items = new java.util.ArrayList<>();
        if (body.has("items")) for (JsonElement item : body.getAsJsonArray("items")) items.add(item);
        return new Page(items, nextCursor, body.has("hasMore") && body.get("hasMore").getAsBoolean());
    }

    record Page(java.util.List<JsonElement> items, long cursor, boolean hasMore) {
    }
}
