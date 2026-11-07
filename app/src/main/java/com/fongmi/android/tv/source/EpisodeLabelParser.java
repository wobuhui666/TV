package com.fongmi.android.tv.source;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses source labels and release filenames without treating unrelated numbers as episodes.
 * Edition evidence survives removal of episode numbers, sizes and per-file hashes.
 */
public final class EpisodeLabelParser {

    private static final String NUMBER = "[零〇一二两三四五六七八九十百千0-9]{1,8}";
    private static final String EPISODE_BOUNDARY = "(?=$|[^\\p{L}\\p{Nd}]|(?:第\\s*)?" + NUMBER + "\\s*[集话期])";
    private static final String SEMANTIC_BOUNDARY = "(?=$|[^\\p{L}\\p{Nd}]|第|会员|加更|纯享|未播|幕后|花絮|正片|完整版|[上中下](?:半期|期|半集|集|部|篇)?$)";
    private static final Pattern SE = Pattern.compile("(?i)(?<![a-z0-9])s\\s*([0-9]{1,3})[ ._-]*e\\s*([0-9]{1,4})(?![0-9])");
    private static final Pattern CROSS = Pattern.compile("(?i)(?<![a-z0-9])([0-9]{1,3})x([0-9]{1,4})(?![0-9])");
    private static final Pattern SEASON = Pattern.compile("(?i)(?<![a-z0-9])(?:season\\s*|s)([0-9]{1,3})(?![a-z0-9])|第\\s*(" + NUMBER + ")\\s*季");
    private static final Pattern EPISODE = Pattern.compile("(?i)(?<![a-z0-9])(?:ep(?:isode)?\\s*|e)([0-9]{1,4})(?![0-9])|第\\s*(" + NUMBER + ")\\s*[集话期]" + EPISODE_BOUNDARY + "|(?<![零〇一二两三四五六七八九十百千0-9])(" + NUMBER + ")\\s*[集话期]" + EPISODE_BOUNDARY);
    private static final Pattern BARE = Pattern.compile("^\\s*(?:第\\s*)?(" + NUMBER + ")\\s*$");
    private static final Pattern RELEASE_NUMBER = Pattern.compile("[-–—]\\s*([0-9]{1,4})(?![0-9])[ ._-]*$");
    private static final Pattern FILE_NUMBER = Pattern.compile("(?:^|[ ._-])([0-9]{1,4})(?![0-9])[ ._-]*$");
    private static final Pattern RANGE = Pattern.compile("(?i)(?:e(?:p|pisode)?\\s*|第\\s*)(" + NUMBER + ")\\s*[集话期]?\\s*[-–—~_至到+、,/&]\\s*(?:第|e(?:p|pisode)?)?\\s*" + NUMBER
            + "|e[0-9]{1,4}(?:[ ._-]*e[0-9]{1,4})+|^\\s*(" + NUMBER + ")\\s*[-–—~_至到+、,/&]\\s*" + NUMBER + "[集话期]?[ ._-]*$|[-–—]\\s*(" + NUMBER + ")\\s*[-–—~_至到+、,/&]\\s*" + NUMBER + "[集话期]?[ ._-]*$");
    private static final Pattern DECIMAL = Pattern.compile("(?i)(?:e(?:p|pisode)?\\s*|第\\s*|[-–—]\\s*|^)[0-9]{1,4}\\.[0-9]+(?:[集话期]|[ ._-]*$)");
    private static final Pattern COLLECTION = Pattern.compile("(?i)合集|全集|合并|合輯|\\b(?:complete|batch)\\b");
    private static final Pattern PREVIEW = Pattern.compile("(?i)预告|花絮|先导|\\b(?:trailer|preview|teaser)\\b");
    private static final Pattern SPECIAL = Pattern.compile("(?i)特别篇|特别集|特辑|特典|番外|(?<![a-z])(?:special|ova|oad|sp|ncop|nced|op|ed)(?![a-z])");
    private static final Pattern PART = Pattern.compile("(?i)\\bpart[ ._-]*([0-9]{1,2})(?![0-9])|第\\s*(" + NUMBER + ")\\s*部分|([上中下])(?:部分|半期|半集|半部|半|期|集|部|篇){0,2}(?=[\\s\\]）)]*(?:$|[上中下](?:半)?期))");
    private static final Pattern COMBINED_PART = Pattern.compile("(?:上下|上中下|上(?:半期|期|集|部|篇)?[\\s、,/+&~至到-]+(?:中(?:半期|期|集|部|篇)?[\\s、,/+&~至到-]+)?下)(?:半期|期|集|部|篇)?(?=[\\s\\]）)]*$)");
    private static final Pattern SIZE = Pattern.compile("(?i)(?<![a-z0-9])[0-9]+(?:[.,][0-9]+)?\\s*(?:[kmgt]i?b)(?![a-z])");
    private static final Pattern DATE = Pattern.compile("(?<![a-zA-Z0-9])(?:([0-9]{4})([0-9]{2})([0-9]{2})|([0-9]{4})[- ./_]+([0-9]{1,2})[- ./_]+([0-9]{1,2})|([0-9]{4})年([0-9]{1,2})月([0-9]{1,2})日)(?![a-zA-Z0-9])");
    private static final Pattern VARIANT = Pattern.compile("(?i)会员(?:加长)?(?:版|篇)?|加更(?:版|篇)?|纯享(?:版|篇)?|未播(?:版|篇)?|幕后(?:版|篇)?|花絮(?:版|篇)?|正片(?:版)?|完整版|\\b(?:full(?:[ ._-]+episode)?|regular)\\b");
    private static final Pattern EPISODE_END = Pattern.compile("(?:第\\s*)?" + NUMBER + "\\s*[集话期]$");
    private static final Pattern PART_END = Pattern.compile("(?i)(?:[上中下](?:部分|半期|半集|半部|半|期|集|部|篇){0,2}|\\bpart[ ._-]*[0-9]{1,2})$");
    private static final Pattern SEMANTIC_SUFFIX = Pattern.compile("(?i)^(?:第\\s*" + NUMBER + "\\s*[集话期]|" + NUMBER + "\\s*[集话期]|(?:ep(?:isode)?|e)\\s*[0-9]{1,4}|[上中下](?:半期|期|半集|集|部|篇)?|part[ ._-]*[0-9]{1,2}|会员(?:加长)?(?:版|篇)?|加更(?:版|篇)?|纯享(?:版|篇)?|未播(?:版|篇)?|幕后(?:版|篇)?|花絮(?:版|篇)?|正片(?:版)?|完整版)" + SEMANTIC_BOUNDARY);
    private static final Pattern DIMENSIONS = Pattern.compile("(?i)(?<![0-9])[0-9]{3,4}[x×][0-9]{3,4}(?![0-9])");
    private static final Pattern HASH = Pattern.compile("(?i)(?<![a-z0-9])(?=[a-f0-9]{8,64}(?![a-z0-9]))(?=[a-f0-9]*[a-f])[a-f0-9]{8,64}(?![a-z0-9])");
    private static final Pattern AUDIO_CHANNELS = Pattern.compile("(?i)(?<![a-z0-9])(aac|flac|dts(?:[- .]?hd)?(?:[- .]?ma)?|e?ac3|ddp?|opus)[ ._-]*([1-9]\\.[0-9])(?:ch)?(?![a-z0-9])");
    private static final Pattern BRACKET = Pattern.compile("\\[([^\\]]*)\\]|\\(([^)]*)\\)");
    private static final Pattern EXTENSION = Pattern.compile("(?i)\\.(?:mkv|mp4|m4v|avi|mov|wmv|ts|m2ts|webm|strm|srt|ass|torrent)\\s*$");
    private static final Pattern REVISION = Pattern.compile("(?i)(?<![a-z])v([0-9]{1,2})(?![a-z0-9])");
    private static final Pattern PROPERTY = Pattern.compile("(?i)(?<![a-z0-9])(?:[0-9]{3,4}[pi]|4k|8k|16k|uhd|fhd|web[- .]?dl|webrip|blu[- .]?ray|bdrip|brrip|dvdrip|h[ .]?26[45]|x26[45]|avc|hevc|av1|aac|flac|dts(?:-hd)?|e?ac3|opus|(?:10|8)[ -]?bit|hdr10\\+?|hdr|dolby[ .-]?vision|dv|chs|cht|jpn|eng|简体|繁体|简日|繁日|简繁|中文字幕|中字|国语|粤语)(?![a-z0-9])");
    private static final Pattern PLATFORM = Pattern.compile("^(?:baha|bilibili|netflix|amzn|dsnp|hulu|atvp|crunchyroll)$");
    private static final Pattern NON_NAME = Pattern.compile("[^\\p{L}\\p{Nd}]+");

    private EpisodeLabelParser() {
    }

    public static EpisodeLabel parse(String label) {
        String original = label == null ? "" : label;
        if (original.length() > 4096) return new EpisodeLabel(original, null, null, "", "", false, false, true);
        String value = normalize(original);
        char[] remaining = value.toCharArray();
        Set<String> editionTags = new TreeSet<>();
        List<BracketTag> bracketTags = new ArrayList<>();
        Set<Integer> seasons = new LinkedHashSet<>();
        boolean file = mask(EXTENSION, value, remaining);
        int directory = file ? lastDirectorySeparator(value) : -1;
        Set<String> airDates = new TreeSet<>();
        Set<String> variants = new TreeSet<>();
        boolean invalidDate = false;
        boolean variantPreview = false;
        int identityStart = value.length();
        mask(SIZE, value, remaining);
        // Folder dates describe a download/release location, not the playlist item's air date.
        Matcher dates = DATE.matcher(new String(remaining));
        dates.region(directory + 1, value.length());
        while (dates.find()) {
            String date = airDate(dates);
            if (date.isEmpty()) invalidDate = true;
            else airDates.add(date);
            identityStart = Math.min(identityStart, dates.start());
            int start = dates.start();
            int end = dates.end();
            if (end < value.length() && value.charAt(end) == '期') {
                end++;
                if (start > directory + 1 && value.charAt(start - 1) == '第') start--;
            }
            consume(remaining, start, end);
        }
        Matcher variantMarkers = VARIANT.matcher(new String(remaining));
        while (variantMarkers.find()) {
            if (variantMarkers.start() <= directory || !isSemanticVariant(new String(remaining), variantMarkers)) continue;
            String marker = variantMarkers.group().toLowerCase(Locale.ROOT);
            String tag = variant(marker);
            if (!tag.isEmpty()) variants.add(tag);
            variantPreview |= marker.startsWith("花絮");
            consume(remaining, variantMarkers.start(), variantMarkers.end());
        }
        Matcher dimensions = DIMENSIONS.matcher(value);
        while (dimensions.find()) {
            editionTags.add(name(dimensions.group()));
            consume(remaining, dimensions.start(), dimensions.end());
        }
        mask(HASH, value, remaining);
        Matcher channels = AUDIO_CHANNELS.matcher(new String(remaining));
        while (channels.find()) {
            editionTags.add(property(channels.group(1)));
            editionTags.add("channels" + name(channels.group(2)));
            consume(remaining, channels.start(), channels.end());
        }
        Matcher properties = PROPERTY.matcher(new String(remaining));
        while (properties.find()) {
            editionTags.add(property(properties.group()));
            consume(remaining, properties.start(), properties.end());
        }
        Matcher revisions = REVISION.matcher(new String(remaining));
        while (revisions.find()) {
            editionTags.add("revision" + integer(revisions.group(1)));
            consume(remaining, revisions.start(), revisions.end());
        }
        Matcher brackets = BRACKET.matcher(value);
        while (brackets.find()) {
            String content = brackets.group(1) != null ? brackets.group(1) : brackets.group(2);
            String visible = new String(remaining, brackets.start() + 1, content.length()).trim();
            if (visible.isEmpty()) {
                consume(remaining, brackets.start(), brackets.end());
            } else if (isSemanticBracket(visible)) {
                consume(remaining, brackets.start(), brackets.start() + 1);
                consume(remaining, brackets.end() - 1, brackets.end());
            } else {
                bracketTags.add(new BracketTag(brackets.start(), name(visible),
                        new String(remaining, 0, brackets.start()).trim().isEmpty()));
                consume(remaining, brackets.start(), brackets.end());
            }
        }
        Matcher seasonMarkers = SEASON.matcher(new String(remaining));
        while (seasonMarkers.find()) {
            seasons.add(number(firstGroup(seasonMarkers)));
            consume(remaining, seasonMarkers.start(), seasonMarkers.end());
        }
        if (directory >= 0) consume(remaining, 0, directory + 1);
        String working = new String(remaining);
        boolean preview = variantPreview || PREVIEW.matcher(working).find();
        boolean special = SPECIAL.matcher(working).find();
        boolean ambiguous = invalidDate || airDates.size() > 1 || COLLECTION.matcher(working).find()
                || COMBINED_PART.matcher(working).find();
        Set<String> parts = new LinkedHashSet<>();
        Matcher partMarkers = PART.matcher(working);
        while (partMarkers.find()) {
            parts.add(partMarkers.group(3) != null ? part(partMarkers.group(3))
                    : "part" + number(partMarkers.group(1) != null ? partMarkers.group(1) : partMarkers.group(2)));
            consume(remaining, partMarkers.start(), partMarkers.end());
        }
        if (parts.size() > 1) ambiguous = true;
        mask(PREVIEW, working, remaining);
        mask(SPECIAL, working, remaining);
        working = new String(remaining);
        if (RANGE.matcher(working).find() || DECIMAL.matcher(working).find()) ambiguous = true;
        Integer episode = null;
        boolean issue = false;
        int episodeMarkers = 0;
        int episodeStart = identityStart;
        for (Pattern pattern : new Pattern[]{SE, CROSS}) {
            Matcher compound = pattern.matcher(working);
            while (compound.find()) {
                seasons.add(integer(compound.group(1)));
                episode = integer(compound.group(2));
                episodeMarkers++;
                episodeStart = Math.min(episodeStart, compound.start());
                consume(remaining, compound.start(), compound.end());
            }
        }
        Matcher markers = EPISODE.matcher(new String(remaining));
        while (markers.find()) {
            episode = number(firstGroup(markers));
            issue |= markers.group().endsWith("期");
            episodeMarkers++;
            episodeStart = Math.min(episodeStart, markers.start());
            consume(remaining, markers.start(), markers.end());
        }
        if (episodeMarkers > 1) ambiguous = true;
        if (episodeMarkers == 0 && !ambiguous) {
            Matcher bare = BARE.matcher(new String(remaining));
            Matcher release = RELEASE_NUMBER.matcher(new String(remaining));
            Matcher filename = FILE_NUMBER.matcher(new String(remaining));
            Matcher selected = bare.matches() ? bare : release.find() ? release
                    : (file || !editionTags.isEmpty() || hasLeadingGroup(bracketTags)) && filename.find() ? filename : null;
            if (selected != null) {
                int candidate = number(selected.group(1));
                if (candidate < 1900 || candidate > 2099) {
                    episode = candidate;
                    episodeStart = Math.min(episodeStart, selected.start());
                    consume(remaining, selected.start(), selected.end());
                }
            }
        }
        Integer season = seasons.size() == 1 ? seasons.iterator().next() : null;
        if (seasons.size() > 1 || season != null && (season < 0 || season > 999)) ambiguous = true;
        if (episode != null && (episode <= 0 || episode > 9999)) ambiguous = true;
        if (ambiguous) episode = null;
        special |= Integer.valueOf(0).equals(season);
        String part = parts.isEmpty() ? "" : parts.iterator().next();
        // Plot titles after an episode marker vary by episode, so they are not edition evidence.
        String body = name(new String(remaining, 0, episodeStart));
        for (BracketTag tag : bracketTags) {
            // Unknown post-episode brackets can contain plot titles, not release groups.
            if (tag.start() < episodeStart || PLATFORM.matcher(tag.value()).matches()) editionTags.add(tag.value());
        }
        editionTags.remove("");
        String edition = body.isEmpty() ? String.join("|", editionTags)
                : body + (editionTags.isEmpty() ? "" : "|" + String.join("|", editionTags));
        String airDate = airDates.size() == 1 ? airDates.iterator().next() : "";
        return new EpisodeLabel(original, season, episode, part, edition, special, preview, ambiguous,
                airDate, String.join("|", variants), issue);
    }

    private static String airDate(Matcher matcher) {
        int offset = matcher.group(1) != null ? 1 : matcher.group(4) != null ? 4 : 7;
        int year = integer(matcher.group(offset));
        try {
            if (year < 1) return "";
            return LocalDate.of(year, integer(matcher.group(offset + 1)), integer(matcher.group(offset + 2))).toString();
        } catch (DateTimeException ignored) {
            return "";
        }
    }

    private static boolean isSemanticVariant(String value, Matcher matcher) {
        int start = matcher.start();
        int end = matcher.end();
        Matcher partEnd = PART_END.matcher(value.substring(0, start));
        boolean afterPart = partEnd.find() && (partEnd.start() == 0
                || !Character.isLetterOrDigit(value.charAt(partEnd.start() - 1))
                || EPISODE_END.matcher(value.substring(0, partEnd.start())).find());
        boolean left = start == 0 || !Character.isLetterOrDigit(value.charAt(start - 1))
                || EPISODE_END.matcher(value.substring(0, start)).find()
                || afterPart;
        boolean right = end == value.length() || !Character.isLetterOrDigit(value.charAt(end))
                || SEMANTIC_SUFFIX.matcher(value.substring(end)).find();
        String marker = matcher.group().toLowerCase(Locale.ROOT);
        if (marker.startsWith("full") || marker.equals("regular")) {
            // Keep English title words such as "Full House" and "Regular Show" in the edition.
            String after = value.substring(end).replaceFirst("^[\\s\\p{Punct}]+", "");
            right &= after.isEmpty() || SEMANTIC_SUFFIX.matcher(after).find()
                    || EPISODE_END.matcher(value.substring(0, start).trim()).find();
        }
        return left && right;
    }

    private static String variant(String value) {
        if (value.startsWith("会员")) return "member";
        if (value.startsWith("加更")) return "extra";
        if (value.startsWith("纯享")) return "pure";
        if (value.startsWith("未播") || value.startsWith("幕后") || value.startsWith("花絮")) return "behind";
        return "";
    }

    private static boolean isSemanticBracket(String value) {
        return BARE.matcher(value).matches() || SE.matcher(value).find() || CROSS.matcher(value).find()
                || SEASON.matcher(value).find() || EPISODE.matcher(value).find() || PART.matcher(value).find()
                || SPECIAL.matcher(value).find() || PREVIEW.matcher(value).find() || RANGE.matcher(value).find();
    }

    private static boolean hasLeadingGroup(List<BracketTag> tags) {
        for (BracketTag tag : tags) if (tag.leading()) return true;
        return false;
    }

    private static boolean mask(Pattern pattern, String value, char[] remaining) {
        boolean found = false;
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            found = true;
            consume(remaining, matcher.start(), matcher.end());
        }
        return found;
    }

    private static void consume(char[] remaining, int start, int end) {
        for (int index = start; index < end; index++) remaining[index] = ' ';
    }

    private static int lastDirectorySeparator(String value) {
        int separator = -1;
        int brackets = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '[' || character == '(') brackets++;
            else if (character == ']' || character == ')') brackets = Math.max(0, brackets - 1);
            else if (brackets == 0 && (character == '/' || character == '\\')) separator = index;
        }
        return separator;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
                .replace('兩', '两').replace('話', '话').replace('預', '预')
                .replace('別', '别').replace('輯', '辑').replace('體', '体')
                .replace('語', '语').replace('國', '国').replace('會', '会')
                .replace('員', '员').replace('長', '长').replace('純', '纯').replace('導', '导');
    }

    private static String name(String value) {
        return NON_NAME.matcher(value).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static String property(String value) {
        String normalized = name(value);
        if (normalized.equals("avc") || normalized.equals("x264")) return "h264";
        if (normalized.equals("hevc") || normalized.equals("x265")) return "h265";
        if (normalized.equals("ddp")) return "eac3";
        if (normalized.equals("dd")) return "ac3";
        return normalized;
    }

    private static String part(String value) {
        return value.equals("上") ? "upper" : value.equals("中") ? "middle" : "lower";
    }

    private static String firstGroup(Matcher matcher) {
        for (int index = 1; index <= matcher.groupCount(); index++) if (matcher.group(index) != null) return matcher.group(index);
        return "";
    }

    private static int integer(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static int number(String value) {
        if (value == null || value.isEmpty()) return -1;
        if (value.charAt(0) >= '0' && value.charAt(0) <= '9') return integer(value);
        int total = 0;
        int current = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            int unit = character == '十' ? 10 : character == '百' ? 100 : character == '千' ? 1000 : 0;
            if (unit > 0) {
                total += (current == 0 ? 1 : current) * unit;
                current = 0;
            } else {
                int digit = switch (character) {
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
                if (digit < 0) return -1;
                current = current * 10 + digit;
            }
        }
        return total + current;
    }

    private record BracketTag(int start, String value, boolean leading) {
    }

}
