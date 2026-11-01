package com.fongmi.android.tv.player.skip;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Parses the documented provider formats without inventing missing boundaries. */
final class CommunitySkipParser {

    private static final double MIN_CONFIDENCE = 0.7;
    private static final long MAX_MEDIA_MS = 6 * 60 * 60 * 1000L;

    static List<SkipSegment> theIntroDb(String body, EpisodeIdentity identity, long durationMs) {
        JsonObject root = object(body);
        if (!(identity.isMovie() ? "movie" : "tv").equals(string(root, "type"))
                || (!identity.isMovie() && !coordinates(root, "season", "episode", identity))
                || number(root, "tvdb_id", false) <= 0) return List.of();
        long groupDuration = number(root, "group_duration_ms", false);
        if (groupDuration > 0 && !matchesDuration(groupDuration, durationMs)) return List.of();
        if (root.has("tmdb_id") && number(root, "tmdb_id", false) != identity.tmdbId()) return List.of();
        List<SkipSegment> segments = new ArrayList<>();
        addTheIntroSegments(root, "intro", SkipSegment.Type.INTRO, durationMs, segments);
        addTheIntroSegments(root, "credits", SkipSegment.Type.CREDITS, durationMs, segments);
        return normalize(segments);
    }

    static List<SkipSegment> introDb(String body, EpisodeIdentity identity, long durationMs) {
        JsonObject root = object(body);
        if (identity.imdbId().isEmpty() || !identity.imdbId().equals(string(root, "imdb_id"))) return List.of();
        if (identity.isMovie()) {
            if (!"movie".equals(string(root, "media_type")) && !"true".equals(string(root, "is_movie"))) return List.of();
        } else if (!coordinates(root, "season", "episode", identity)
                || "movie".equals(string(root, "media_type"))
                || (root.has("is_movie") && !"false".equals(string(root, "is_movie")))) return List.of();
        List<SkipSegment> segments = new ArrayList<>();
        for (String key : List.of("intro", "outro")) {
            JsonObject item = object(root.get(key));
            SkipSegment.Type type = "intro".equals(key) ? SkipSegment.Type.INTRO : SkipSegment.Type.CREDITS;
            add(segments, type, number(item, "start_ms", false), number(item, "end_ms", false),
                    "introdb", durationMs, confidence(item));
        }
        return normalize(segments);
    }

    static List<SkipSegment> theOtherDb(String body, EpisodeIdentity identity, long durationMs) {
        JsonObject root = object(body);
        JsonArray items = array(root.get("items"));
        List<SkipSegment> best = List.of();
        long bestDifference = Long.MAX_VALUE;
        boolean ambiguous = false;
        // Select one release. Never combine markers from different source versions.
        for (int i = 0; i < Math.min(items.size(), 30); i++) {
            JsonObject item = object(items.get(i));
            if (!(identity.isMovie() ? "movie" : "tv").equals(string(item, "video_type"))) continue;
            if (!identity.isMovie() && (number(object(item.get("base_video_season")), "season_number", false) != identity.season()
                    || number(object(item.get("base_video_episode")), "episode_number", false) != identity.episode())) continue;
            long versionDuration = number(item, "version_runtime", true);
            if (!matchesDuration(versionDuration, durationMs)) continue;
            long difference = Math.abs(versionDuration - durationMs);
            if (difference > bestDifference) continue;
            List<SkipSegment> candidate = new ArrayList<>();
            JsonArray markers = array(item.get("marker"));
            for (int j = 0; j < Math.min(markers.size(), 128); j++) {
                JsonObject marker = object(markers.get(j));
                String kind = string(marker, "marker_type");
                SkipSegment.Type type = "intro".equals(kind) ? SkipSegment.Type.INTRO
                        : "credits".equals(kind) ? SkipSegment.Type.CREDITS : null;
                if (type == null) continue; // Keep recaps, chapters and post-credit scenes.
                add(candidate, type, number(marker, "time_start", true), number(marker, "time_end", true),
                        "theotherdb", durationMs, true);
            }
            candidate = normalize(candidate);
            if (candidate.isEmpty()) continue;
            if (difference < bestDifference) {
                bestDifference = difference;
                best = candidate;
                ambiguous = false;
            } else if (!candidate.equals(best)) {
                ambiguous = true;
            }
        }
        return ambiguous ? List.of() : best;
    }

    static boolean matchesDuration(long expectedMs, long actualMs) {
        return expectedMs > 0 && actualMs > 0 && actualMs <= MAX_MEDIA_MS
                && Math.abs(expectedMs - actualMs) <= Math.max(2_000, actualMs / 200);
    }

    private static void addTheIntroSegments(JsonObject root, String key, SkipSegment.Type type,
                                            long durationMs, List<SkipSegment> result) {
        JsonArray segments = array(root.get(key));
        for (int i = 0; i < Math.min(segments.size(), 32); i++) {
            JsonObject item = object(segments.get(i));
            long start = number(item, "start_ms", false);
            long end = number(item, "end_ms", false);
            // Explicit null has documented semantics. An absent property has none.
            if (type == SkipSegment.Type.INTRO && item.has("start_ms") && item.get("start_ms").isJsonNull()) start = 0;
            if (type == SkipSegment.Type.CREDITS && item.has("end_ms") && item.get("end_ms").isJsonNull()) end = durationMs;
            add(result, type, start, end, "theintrodb", durationMs, confidence(item));
        }
    }

    private static void add(List<SkipSegment> result, SkipSegment.Type type, long start, long end,
                            String source, long durationMs, boolean confident) {
        if (!confident || durationMs <= 0 || durationMs > MAX_MEDIA_MS || start < 0 || end <= start
                || end > durationMs || end - start < 1_000) return;
        if (type == SkipSegment.Type.INTRO ? end - start > 300_000 || end >= durationMs
                : end - start > 1_800_000 || start < durationMs / 2) return;
        result.add(new SkipSegment(type, start, end, source));
    }

    static List<SkipSegment> normalize(List<SkipSegment> result) {
        List<SkipSegment> sorted = new ArrayList<>();
        for (SkipSegment segment : result) if (!sorted.contains(segment)) sorted.add(segment);
        sorted.sort(Comparator.comparingLong(SkipSegment::startMs).thenComparingLong(SkipSegment::endMs));
        boolean[] conflict = new boolean[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size() && sorted.get(j).startMs() < sorted.get(i).endMs(); j++) {
                conflict[i] = true;
                conflict[j] = true;
            }
        }
        List<SkipSegment> safe = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) if (!conflict[i]) safe.add(sorted.get(i));
        return List.copyOf(safe);
    }

    private static boolean coordinates(JsonObject root, String season, String episode, EpisodeIdentity identity) {
        return number(root, season, false) == identity.season() && number(root, episode, false) == identity.episode();
    }

    private static boolean confidence(JsonObject item) {
        if (!item.has("confidence")) return true;
        try {
            JsonElement value = item.get("confidence");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return false;
            double confidence = value.getAsDouble();
            return Double.isFinite(confidence) && confidence >= MIN_CONFIDENCE && confidence <= 1;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static long number(JsonObject object, String key, boolean seconds) {
        try {
            JsonElement element = object.get(key);
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) return -1;
            BigDecimal value = element.getAsBigDecimal();
            return (seconds ? value.multiply(BigDecimal.valueOf(1_000)) : value).longValueExact();
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static JsonObject object(String body) {
        try {
            return object(JsonParser.parseString(body));
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private static JsonObject object(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray array(JsonElement element) {
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static String string(JsonObject object, String key) {
        try {
            JsonElement element = object.get(key);
            return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
        } catch (Exception ignored) {
            return "";
        }
    }
}
