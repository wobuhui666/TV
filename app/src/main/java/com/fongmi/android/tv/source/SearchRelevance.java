package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Vod;
import com.github.catvod.utils.Trans;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative title evidence shared by ordinary search and native poster source selection. */
public final class SearchRelevance {
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(?:19|20)\\d{2}(?!\\d)");
    private static final Pattern TITLE_YEAR = Pattern.compile("(?:[\\[(【]\\s*((?:19|20)\\d{2})\\s*[\\])】]|\\s+((?:19|20)\\d{2})\\s*$)");
    private static final Pattern SEASON = Pattern.compile("(?i)(?:\\bseason\\s*|(?<![a-z])s\\s*)0*(\\d{1,2})(?!\\d)|第\\s*([零〇一二两三四五六七八九十百0-9]+)\\s*[季部]");
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(?:(?<=[\\p{sc=Han}])|(?<=\\s))([1-9][0-9]?)\\s*$");
    private static final Pattern TRAILING_ROMAN = Pattern.compile("(?i)\\s+(VIII|VII|VI|IV|III|II|IX|X|V|I)\\s*$");
    // English tags must be separate words: stripping 'IT', 'Dark' or the 'x' in Matrix corrupts titles.
    private static final Pattern NOISE = Pattern.compile("(?i)(?<![a-z0-9])(?:4k|8k|16k|2160p|1080p|720p|480p|uhd|hdr10?\\+?|dolby|blu[ .-]?ray|web[ .-]?dl)(?![a-z0-9])|中文字幕|中字|国语|粤语|高清|超清|蓝光|完结|全集|全\\d+集|无删减|纯净版|修复版|导演剪辑版");
    private static final Pattern BRACKETS = Pattern.compile("[\\[(【]([^\\])】]*)[\\])】]");
    private static final Pattern SUPPLEMENT = Pattern.compile("(?i)\\b(?:trailer|featurette|recap|review|teaser|reaction|breakdown|explained|interview|clips?|fan[ -]?made)\\b|预告|花絮|解说|影评|幕后|速看|影视剪辑|混剪|饭制|战衣细节");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{Nd}]+");

    private SearchRelevance() { }

    public record Query(String title, List<String> aliases, String year, Integer season) {
        public Query {
            title = safe(title);
            aliases = aliases == null ? List.of() : aliases.stream().filter(value -> value != null && !value.isBlank()).limit(20).toList();
            year = firstYear(year);
            if (season != null && season <= 0) season = null;
        }

        public static Query of(String title) { return new Query(title, List.of(), "", null); }
    }

    public record Match(int score, boolean relevant, boolean strict, boolean confident) {
        public boolean matchesMode(int mode) { return mode == 2 ? strict : relevant; }
        /** Complete title/known alias evidence; unlike strict(), an unselected TV season is allowed. */
        public boolean titleMatch() { return relevant && score >= 76; }
    }

    public static Match evaluate(Vod result, Query query) {
        if (result == null) return none();
        return evaluate(result.getName(), result.getYear(), query);
    }

    /** String overload keeps matching testable without Android and never searches synopsis/actor text. */
    public static Match evaluate(String resultTitle, String resultYear, Query query) {
        if (query == null || query.title().isBlank()) return none();
        Title expected = parse(query.title(), query.year(), query.season());
        Title actual = parse(resultTitle, firstYear(resultYear), null);
        // Ordinary search can reject incompatible metadata before parsing aliases or clip evidence.
        if (conflicts(expected.year, actual.year) || conflicts(expected.season, actual.season)) return none();
        return evaluatePrepared(new PreparedResult(actual, null, resultTitle),
                new PreparedQuery(expected, wanted(query, expected), null, query));
    }

    /** Session-owned parsing preserves every key, including bracketed aliases and title markers. */
    static PreparedResult prepareResult(String title, String year) {
        return new PreparedResult(parse(title, firstYear(year), null), supplementWords(title), null);
    }

    static PreparedQuery prepareQuery(Query query) {
        if (query == null || query.title().isBlank()) return new PreparedQuery(null, List.of(), Set.of(), null);
        Title expected = parse(query.title(), query.year(), query.season());
        return new PreparedQuery(expected, wanted(query, expected),
                supplementWords(query.title() + " " + String.join(" ", query.aliases())), null);
    }

    private static List<Title> wanted(Query query, Title expected) {
        List<Title> wanted = new ArrayList<>();
        wanted.add(expected);
        for (String alias : query.aliases()) wanted.add(parse(alias, expected.year, expected.season));
        return List.copyOf(wanted);
    }

    static Match evaluatePrepared(PreparedResult result, PreparedQuery query) {
        if (result == null || query == null || query.expected == null) return none();
        Title expected = query.expected;
        Title actual = result.actual;
        if (conflicts(expected.year, actual.year) || conflicts(expected.season, actual.season)) return none();
        int score = 0;
        boolean exact = false;
        for (Title title : query.wanted) {
            if (conflicts(title.year, actual.year) || conflicts(title.season, actual.season)) continue;
            for (TitleKey key : title.keys) {
                for (TitleKey candidate : actual.keys) {
                    if (key.value.isEmpty() || candidate.value.isEmpty()) continue;
                    if (key.compact.equals(candidate.compact)) {
                        exact = true;
                        score = Math.max(score, title == expected ? 80 : 76);
                    } else if (containsTitle(candidate, key)) {
                        score = Math.max(score, 45);
                    }
                }
            }
        }
        if (score == 0) return none();
        boolean supplement = result.supplementWords != null && query.supplementWords != null
                ? !query.supplementWords.containsAll(result.supplementWords) : hasExtraSupplement(result.rawTitle, query.rawQuery);
        if (supplement) exact = false;
        if (!expected.year.isEmpty() && expected.year.equals(actual.year)) score += 10;
        if (expected.season != null && expected.season.equals(actual.season)) score += 10;
        if (supplement) score = Math.min(score, 45);
        boolean confirmedYear = expected.year.isEmpty() || expected.year.equals(actual.year);
        boolean confirmedSeason = expected.season == null ? actual.season == null : expected.season.equals(actual.season);
        // Related search may include the series; strict search must not silently change to a sequel.
        boolean strict = exact && confirmedSeason;
        return new Match(score, true, strict, strict && confirmedYear && confirmedSeason);
    }

    private static boolean containsTitle(TitleKey candidate, TitleKey query) {
        // One-character searches must not turn into every title containing that character.
        if (query.characters < 2) return false;
        if (query.han) return candidate.compact.contains(query.compact);
        // Latin queries require word boundaries; 'it' must not match 'Titanic'.
        return candidate.bounded.contains(query.bounded);
    }

    private static Set<String> supplementWords(String value) {
        Set<String> words = new LinkedHashSet<>();
        Matcher matcher = SUPPLEMENT.matcher(normalize(value));
        while (matcher.find()) words.add(matcher.group());
        return Set.copyOf(words);
    }

    private static boolean hasExtraSupplement(String actual, Query query) {
        Set<String> titleWords = new LinkedHashSet<>();
        Matcher known = SUPPLEMENT.matcher(normalize(query.title() + " " + String.join(" ", query.aliases())));
        while (known.find()) titleWords.add(known.group());
        Matcher found = SUPPLEMENT.matcher(normalize(actual));
        while (found.find()) if (!titleWords.contains(found.group())) return true;
        return false;
    }

    private static Title parse(String value, String suppliedYear, Integer suppliedSeason) {
        String text = normalize(value);
        String year = suppliedYear;
        Matcher yearMatcher = TITLE_YEAR.matcher(text);
        if (yearMatcher.find()) {
            if (year.isEmpty()) year = yearMatcher.group(1) == null ? yearMatcher.group(2) : yearMatcher.group(1);
            text = yearMatcher.replaceAll(" ");
        }
        String withoutNoise = NOISE.matcher(text).replaceAll(" ").trim();
        // A real title such as '4K' is still searchable even if it looks like a resource tag.
        if (!key(withoutNoise).isEmpty()) text = withoutNoise;
        text = text.replaceAll("\\[\\s*\\]|\\(\\s*\\)|【\\s*】", " ").trim();
        Integer season = suppliedSeason;
        Matcher seasonMatcher = SEASON.matcher(text);
        if (seasonMatcher.find()) {
            if (season == null) season = number(seasonMatcher.group(1) == null ? seasonMatcher.group(2) : seasonMatcher.group(1));
            text = seasonMatcher.replaceAll(" ");
        } else {
            Matcher trailing = TRAILING_NUMBER.matcher(text);
            Matcher roman = TRAILING_ROMAN.matcher(text);
            if (trailing.find()) {
                if (season == null) season = Integer.parseInt(trailing.group(1));
                text = trailing.replaceAll(" ");
            } else if (roman.find()) {
                if (season == null) season = roman(roman.group(1));
                text = roman.replaceAll(" ");
            }
        }
        Set<String> keys = new LinkedHashSet<>();
        addKey(keys, text);
        // Only explicit alternate-name delimiters count as aliases; no fuzzy edit distance.
        for (String part : text.split("[/|;；]")) addKey(keys, part);
        Matcher brackets = BRACKETS.matcher(text);
        String outside = brackets.replaceAll(" ");
        brackets.reset();
        boolean hasAlias = false;
        while (brackets.find()) {
            String inside = brackets.group(1).trim();
            boolean explicit = inside.matches("(?i)^(?:又名|别名|aka\\b|a\\.k\\.a\\.).*");
            if (explicit || hasHan(inside) != hasHan(outside) && !key(inside).isEmpty()) {
                addKey(keys, inside.replaceFirst("(?i)^(?:又名|别名|aka\\b|a\\.k\\.a\\.)[:：\\s]*", ""));
                hasAlias = true;
            }
        }
        if (hasAlias) addKey(keys, outside);
        keys.remove("");
        return new Title(keys.stream().map(TitleKey::new).toList(), year, season);
    }

    private static void addKey(Set<String> keys, String text) {
        String normalized = key(text);
        keys.add(normalized);
        if (hasHan(normalized)) keys.add(compact(normalized));
    }

    private static String key(String text) { return NON_WORD.matcher(text).replaceAll(" ").trim().replaceAll("\\s+", " "); }
    private static String compact(String text) { return text.replace(" ", ""); }
    private static boolean hasHan(String value) { return value.codePoints().anyMatch(character -> Character.UnicodeScript.of(character) == Character.UnicodeScript.HAN); }
    private static boolean conflicts(String expected, String actual) { return !expected.isEmpty() && !actual.isEmpty() && !expected.equals(actual); }
    private static boolean conflicts(Integer expected, Integer actual) { return expected != null && actual != null && !expected.equals(actual); }
    private static Match none() { return new Match(0, false, false, false); }
    private record Title(List<TitleKey> keys, String year, Integer season) { }
    private record TitleKey(String value, String compact, String bounded, boolean han, int characters) {
        private TitleKey(String value) { this(value, SearchRelevance.compact(value), " " + value + " ", hasHan(value), value.codePointCount(0, value.length())); }
    }

    static final class PreparedResult {
        private final Title actual;
        private final Set<String> supplementWords;
        private final String rawTitle;
        private PreparedResult(Title actual, Set<String> supplementWords, String rawTitle) {
            this.actual = actual; this.supplementWords = supplementWords; this.rawTitle = rawTitle;
        }
    }

    static final class PreparedQuery {
        private final Title expected;
        private final List<Title> wanted;
        private final Set<String> supplementWords;
        private final Query rawQuery;
        private PreparedQuery(Title expected, List<Title> wanted, Set<String> supplementWords, Query rawQuery) {
            this.expected = expected; this.wanted = wanted; this.supplementWords = supplementWords; this.rawQuery = rawQuery;
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(Trans.t2s(false, safe(value)), Normalizer.Form.NFKC)
                .replace('馀', '余').toLowerCase(Locale.ROOT).trim();
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static String firstYear(String value) {
        Matcher matcher = YEAR.matcher(safe(value));
        return matcher.find() ? matcher.group() : "";
    }

    private static int number(String value) {
        if (value.matches("\\d+")) return Integer.parseInt(value);
        int total = 0;
        int current = 0;
        for (char character : value.toCharArray()) {
            if (character == '十' || character == '百') {
                total += (current == 0 ? 1 : current) * (character == '十' ? 10 : 100);
                current = 0;
            } else {
                int digit = "零一二三四五六七八九".indexOf(character == '两' ? '二' : character == '〇' ? '零' : character);
                if (digit >= 0) current = current * 10 + digit;
            }
        }
        return total + current;
    }

    private static int roman(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "I" -> 1; case "II" -> 2; case "III" -> 3; case "IV" -> 4; case "V" -> 5;
            case "VI" -> 6; case "VII" -> 7; case "VIII" -> 8; case "IX" -> 9; default -> 10;
        };
    }
}
