package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Vod;
import com.github.catvod.utils.Trans;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MediaMatcher {

    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(?:19|20)\\d{2}(?!\\d)");
    private static final Pattern SEASON = Pattern.compile("(?i)(?:s|season)\\s*0*(\\d{1,2})");
    private static final Pattern CHINESE_SEASON = Pattern.compile("第\\s*([零〇一二两三四五六七八九十百千万0-9]+)\\s*季");
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(?<=[\\p{L}\\p{IsHan}])\\s*([0-9]{1,2})\\s*$");
    private static final Pattern TRAILING_ROMAN = Pattern.compile("(?i)(?<=[\\p{L}\\p{IsHan}])\\s*(I{1,3}|IV|V|VI{0,3}|IX|X)\\s*$");
    private static final Pattern NOISE = Pattern.compile("(?i)(?:4k|8k|16k|2160p|1080p|720p|480p|uhd|hdr|dolby|blu[ -]?ray|web[ -]?dl|中字|中文字幕|国语|国語|粤语|高清|超清|蓝光|藍光|完结|完結|全集|全\\d+集|无删减|無刪減|纯净版|純淨版|修复版|修復版|抢先版|搶先版|导演剪辑版|導演剪輯版)");
    private static final Pattern TYPE_MOVIE = Pattern.compile("(?i)电影|電影|movie|film");
    private static final Pattern TYPE_SERIES = Pattern.compile("(?i)电视剧|電視劇|连续剧|連續劇|剧集|劇集|series|tv|anime|番剧|番劇");

    private MediaMatcher() {
    }

    public static MediaIdentity identity(Vod vod) {
        if (vod == null) return identity("");
        String title = vod.getName();
        String year = firstYear(vod.getYear());
        if (year.isEmpty()) year = firstYear(title);
        Integer season = extractSeason(title);
        String normalized = normalizeTitle(title, season, year);
        MediaIdentity.Kind kind = kind(vod.getTypeName() + " " + title + " " + vod.getRemarks());
        return new MediaIdentity(normalized, year, season, kind);
    }

    public static MediaIdentity identity(String title) {
        String value = safe(title);
        String year = firstYear(value);
        Integer season = extractSeason(value);
        return new MediaIdentity(normalizeTitle(value, season, year), year, season, MediaIdentity.Kind.UNKNOWN);
    }

    public static boolean sameMedia(Vod first, Vod second) {
        return first != null && second != null && identity(first).compatibleWith(identity(second));
    }

    public static boolean queryMatches(String query, Vod result) {
        if (result == null) return false;
        MediaIdentity expected = identity(query);
        MediaIdentity actual = identity(result);
        boolean yearCompatible = expected.getYear().isEmpty() || actual.getYear().isEmpty() || expected.getYear().equals(actual.getYear());
        boolean seasonCompatible = expected.getSeason() == null || actual.getSeason() == null || expected.getSeason().equals(actual.getSeason());
        boolean kindCompatible = expected.getKind() == MediaIdentity.Kind.UNKNOWN || actual.getKind() == MediaIdentity.Kind.UNKNOWN || expected.getKind() == actual.getKind();
        return yearCompatible && seasonCompatible && kindCompatible && (expected.getTitle().equals(actual.getTitle()) ||
                expected.getTitle().contains(actual.getTitle()) || actual.getTitle().contains(expected.getTitle()));
    }

    public static int matchScore(Vod result, String query) {
        if (!queryMatches(query, result)) return 0;
        MediaIdentity expected = identity(query);
        MediaIdentity actual = identity(result);
        int score = expected.getTitle().equals(actual.getTitle()) ? 70 : 45;
        if (!expected.getYear().isEmpty() && expected.getYear().equals(actual.getYear())) score += 15;
        if (expected.getSeason() != null && expected.getSeason().equals(actual.getSeason())) score += 15;
        if (actual.getKind() != MediaIdentity.Kind.UNKNOWN) score += 5;
        return score;
    }

    public static Integer extractSeason(String value) {
        String text = normalize(value);
        Matcher matcher = SEASON.matcher(text);
        if (matcher.find()) return parseNumber(matcher.group(1));
        matcher = CHINESE_SEASON.matcher(text);
        if (matcher.find()) return parseNumber(matcher.group(1));
        matcher = TRAILING_NUMBER.matcher(text);
        if (matcher.find() && !isYear(matcher.group(1))) return Integer.parseInt(matcher.group(1));
        matcher = TRAILING_ROMAN.matcher(text);
        if (matcher.find()) return roman(matcher.group(1));
        return null;
    }

    public static Integer extractEpisodeNumber(String value) {
        String text = normalize(value);
        Matcher matcher = Pattern.compile("(?i)(?:ep(?:isode)?|第)\\s*([0-9]{1,4})").matcher(text);
        if (matcher.find()) return Integer.parseInt(matcher.group(1));
        matcher = Pattern.compile("(?<!\\d)(\\d{1,4})(?!\\d)").matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    public static String normalizedEpisodeName(String value) {
        return normalize(value).replaceAll("(?i)(?:ep(?:isode)?|第)", "").replaceAll("[^\\p{L}\\p{IsHan}\\p{Nd}]", "").toLowerCase(Locale.ROOT);
    }

    private static String normalizeTitle(String value, Integer season, String year) {
        String text = normalize(value);
        text = NOISE.matcher(text).replaceAll("");
        text = SEASON.matcher(text).replaceAll("");
        text = CHINESE_SEASON.matcher(text).replaceAll("");
        if (season != null) {
            text = TRAILING_NUMBER.matcher(text).replaceAll("");
            text = TRAILING_ROMAN.matcher(text).replaceAll("");
        }
        if (!year.isEmpty()) text = text.replace(year, "");
        return text.replaceAll("[^\\p{L}\\p{IsHan}\\p{Nd}]", "").toLowerCase(Locale.ROOT);
    }

    private static MediaIdentity.Kind kind(String value) {
        if (TYPE_MOVIE.matcher(value).find()) return MediaIdentity.Kind.MOVIE;
        if (TYPE_SERIES.matcher(value).find()) return MediaIdentity.Kind.SERIES;
        return MediaIdentity.Kind.UNKNOWN;
    }

    private static String firstYear(String value) {
        Matcher matcher = YEAR.matcher(safe(value));
        return matcher.find() ? matcher.group() : "";
    }

    private static String normalize(String value) {
        String text = safe(value);
        try {
            text = Trans.t2s(false, text);
        } catch (Throwable ignored) {
        }
        text = text.replace('馀', '余');
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replace('\u3000', ' ').trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean isYear(String value) {
        return value != null && value.length() == 4 && (value.startsWith("19") || value.startsWith("20"));
    }

    private static int parseNumber(String value) {
        if (value == null || value.isEmpty()) return 0;
        if (value.matches("\\d+")) return Integer.parseInt(value);
        int total = 0;
        int current = 0;
        for (char item : value.toCharArray()) {
            int digit = switch (item) {
                case '零', '〇' -> 0;
                case '一' -> 1;
                case '二', '两' -> 2;
                case '三' -> 3;
                case '四' -> 4;
                case '五' -> 5;
                case '六' -> 6;
                case '七' -> 7;
                case '八' -> 8;
                case '九' -> 9;
                default -> -1;
            };
            if (item == '十' || item == '百' || item == '千' || item == '万') {
                int unit = item == '十' ? 10 : item == '百' ? 100 : item == '千' ? 1000 : 10000;
                total += (current == 0 ? 1 : current) * unit;
                current = 0;
            } else if (digit >= 0) {
                current = current * 10 + digit;
            }
        }
        return total + current;
    }

    private static int roman(String value) {
        String text = value.toUpperCase(Locale.ROOT);
        int total = 0;
        int previous = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            int current = switch (text.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                default -> 0;
            };
            total += current < previous ? -current : current;
            previous = current;
        }
        return total;
    }
}
