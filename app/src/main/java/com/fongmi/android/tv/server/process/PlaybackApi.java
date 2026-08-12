package com.fongmi.android.tv.server.process;

import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.playback.PlaybackMerge;
import com.fongmi.android.tv.playback.PlaybackProgress;
import com.fongmi.android.tv.playback.PlaybackSyncSetting;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.setting.Setting;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

public class PlaybackApi implements Process {

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return normalize(url).startsWith("/playback/");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        String path = normalize(url);
        if ("/playback/current".equals(path)) return current(session.getParms().get("siteKey"));
        if (!PlaybackSyncSetting.isEnabled()) return json(Status.FORBIDDEN, error("观影记录同步未开启"));
        if (!PlaybackSyncSetting.isLocalWriteEnabled()) return json(Status.FORBIDDEN, error("本机 API 修改未开启"));
        if (Setting.isIncognito()) return json(Status.FORBIDDEN, error("隐身模式不允许写入"));
        try {
            String body = files.getOrDefault("postData", "");
            JsonElement root = JsonParser.parseString(body);
            if ("/playback/progress".equals(path)) return apply(root);
            if ("/playback/progress/batch".equals(path)) return batch(root);
            if ("/playback/progress/delete".equals(path)) return delete(root);
        } catch (Throwable error) {
            return json(Status.BAD_REQUEST, error("JSON 格式错误"));
        }
        return json(Status.NOT_FOUND, error("接口不存在"));
    }

    private Response current(String siteKey) {
        JsonObject result = new JsonObject();
        result.addProperty("schema", "webhtv.playback.v1");
        result.addProperty("state", "idle");
        if (Server.get().getService() == null || Server.get().getService().player() == null) return json(Status.OK, result);
        var player = Server.get().getService().player();
        String key = player.getKey();
        String safeSiteKey = SiteHealthStore.normalizeSiteKey(key);
        if (key == null || !siteKeyMatches(safeSiteKey, siteKey)) return json(Status.OK, result);
        result.addProperty("state", player.isPlaying() ? "playing" : "paused");
        result.addProperty("siteKey", safeSiteKey);
        result.addProperty("positionMs", Math.max(0, player.getPosition()));
        result.addProperty("durationMs", Math.max(0, player.getDuration()));
        if (player.getMetadata() != null && player.getMetadata().title != null) result.addProperty("title", player.getMetadata().title.toString());
        return json(Status.OK, result);
    }

    private Response apply(JsonElement root) {
        if (!root.isJsonObject()) return json(Status.BAD_REQUEST, error("请求体必须是对象"));
        PlaybackProgress input = App.gson().fromJson(root, PlaybackProgress.class);
        String validation = validate(input);
        if (validation != null) return json(Status.BAD_REQUEST, error(validation));
        boolean changed = PlaybackMerge.apply(input);
        JsonObject result = new JsonObject();
        result.addProperty("ok", changed);
        result.addProperty("status", changed ? "applied" : "skipped");
        return json(Status.OK, result);
    }

    private Response batch(JsonElement root) {
        JsonArray inputs = root.isJsonArray() ? root.getAsJsonArray() : root.getAsJsonObject().getAsJsonArray("items");
        if (inputs == null || inputs.isEmpty() || inputs.size() > 100) return json(Status.BAD_REQUEST, error("批量记录必须为 1～100 条"));
        JsonArray results = new JsonArray();
        for (JsonElement item : inputs) {
            JsonObject result = new JsonObject();
            try {
                PlaybackProgress input = item.isJsonObject() ? App.gson().fromJson(item, PlaybackProgress.class) : null;
                String validation = validate(input);
                if (validation != null) throw new IllegalArgumentException(validation);
                boolean changed = PlaybackMerge.apply(input);
                result.addProperty("ok", changed);
                result.addProperty("status", changed ? "applied" : "skipped");
            } catch (Throwable ignored) {
                result.addProperty("ok", false);
                result.addProperty("status", "invalid");
            }
            results.add(result);
        }
        JsonObject response = new JsonObject();
        response.add("results", results);
        return json(Status.OK, response);
    }

    private Response delete(JsonElement root) {
        if (!root.isJsonObject()) return json(Status.BAD_REQUEST, error("请求体必须是对象"));
        JsonObject object = root.getAsJsonObject();
        String scope = string(object, "scope", "item");
        if (!"item".equals(scope) && !"site".equals(scope) && !"all".equals(scope)) return json(Status.BAD_REQUEST, error("删除范围无效"));
        String siteKey = string(object, "siteKey", "");
        String vodId = string(object, "vodId", "");
        if ("site".equals(scope) && siteKey.isEmpty()) return json(Status.BAD_REQUEST, error("site 范围必须提交 siteKey"));
        if ("item".equals(scope) && (siteKey.isEmpty() || vodId.isEmpty())) return json(Status.BAD_REQUEST, error("item 范围必须提交 siteKey 和 vodId"));
        if ("all".equals(scope) && (!object.has("confirm") || !object.get("confirm").getAsBoolean())) return json(Status.BAD_REQUEST, error("scope=all 必须提交 confirm=true"));
        PlaybackMerge.delete(scope, siteKey, vodId, number(object, "deletedAt"));
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return json(Status.OK, result);
    }

    private static String normalize(String url) {
        return url.startsWith("/api/") ? url.substring(4) : url;
    }

    private static boolean siteKeyMatches(String current, String requested) {
        return requested == null || requested.isEmpty() || requested.equals(current);
    }

    private static String string(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) ? object.get(key).getAsString() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static long number(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsLong() : System.currentTimeMillis();
        } catch (Throwable ignored) {
            return System.currentTimeMillis();
        }
    }

    private static String validate(PlaybackProgress input) {
        if (input == null) return "记录无效";
        if (!"webhtv.playback.v1".equals(input.schema)) return "协议版本无效";
        if (input.siteKey == null || input.siteKey.isEmpty() || input.vodId == null || input.vodId.isEmpty()) return "siteKey 和 vodId 不能为空";
        if (input.updatedAt <= 0) return "updatedAt 必须大于 0";
        return null;
    }

    private static JsonObject error(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", message);
        return result;
    }

    private static Response json(Status status, JsonObject body) {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString());
    }
}
