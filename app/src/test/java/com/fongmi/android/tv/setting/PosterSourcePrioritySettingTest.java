package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.bean.Site;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PosterSourcePrioritySettingTest {
    private static final String CONFIG = "https://config.example/a.json?token=private";

    @Test
    public void anUnsetOrDamagedPreferenceUsesTheOriginalOrder() {
        List<Site> sites = List.of(site("a"), site("b"));
        for (String stored : Arrays.asList(null, "", "broken", "{", "[]", "true", "{\"wrong\":[\"b\"]}",
                "{\"" + "a".repeat(64) + "\":[\"b\"]} trailing")) {
            List<String> keys = PosterSourcePrioritySetting.read(stored, CONFIG, sites);
            assertTrue(keys.isEmpty());
            assertEquals(sites, PosterSourcePrioritySetting.orderSites(sites, keys));
        }
    }

    @Test
    public void roundTripKeepsSelectedOrderAndFiltersRepeatedOrUnavailableKeys() {
        Site disabled = site("disabled");
        disabled.setSearchable(2);
        List<Site> sites = List.of(site("a"), site("b"), disabled, site("c"));
        List<String> keys = Arrays.asList("b", null, "", "a", "b", "deleted", "disabled", " ");

        String stored = PosterSourcePrioritySetting.write("", CONFIG, keys, sites);

        assertEquals(List.of("b", "a"), PosterSourcePrioritySetting.read(stored, CONFIG, sites));
        assertEquals(List.of("b", "a", "c"), PosterSourcePrioritySetting.orderSites(sites,
                PosterSourcePrioritySetting.read(stored, CONFIG, sites)).stream().map(Site::getKey).toList());
    }

    @Test
    public void configurationUrlsHaveIndependentOrdersWithoutBeingStoredInClearText() {
        List<Site> sites = List.of(site("a"), site("b"));
        String otherConfig = "https://config.example/a.json?token=another-private";
        String stored = PosterSourcePrioritySetting.write("", CONFIG, List.of("b", "a"), sites);
        stored = PosterSourcePrioritySetting.write(stored, otherConfig, List.of("a"), sites);

        assertEquals(List.of("b", "a"), PosterSourcePrioritySetting.read(stored, CONFIG, sites));
        assertEquals(List.of("a"), PosterSourcePrioritySetting.read(stored, otherConfig, sites));
        assertTrue(PosterSourcePrioritySetting.read(stored, "https://config.example/b.json?token=private", sites).isEmpty());
        assertFalse(stored.contains("https://"));
        assertFalse(stored.contains("config.example"));
        assertFalse(stored.contains("private"));
        assertTrue(JsonParser.parseString(stored).getAsJsonObject().keySet().stream().allMatch(key -> key.matches("[a-f0-9]{64}")));
    }

    @Test
    public void clearedSelectionRestoresOnlyTheCurrentConfiguration() {
        List<Site> sites = List.of(site("a"), site("b"));
        String otherConfig = "https://other.example/config.json";
        String stored = PosterSourcePrioritySetting.write("", CONFIG, List.of("b"), sites);
        stored = PosterSourcePrioritySetting.write(stored, otherConfig, List.of("a"), sites);
        String cleared = PosterSourcePrioritySetting.clear(stored, CONFIG);

        assertTrue(PosterSourcePrioritySetting.read(cleared, CONFIG, sites).isEmpty());
        assertEquals(List.of("a"), PosterSourcePrioritySetting.read(cleared, otherConfig, sites));
        assertEquals(cleared, PosterSourcePrioritySetting.write(stored, CONFIG, List.of(), sites));
        assertEquals("", PosterSourcePrioritySetting.clear(cleared, otherConfig));
    }

    @Test
    public void noConfigurationNeverBecomesASharedPreferenceScope() {
        List<Site> sites = List.of(site("a"), site("b"));
        String stored = PosterSourcePrioritySetting.write("", CONFIG, List.of("b"), sites);
        for (String missing : Arrays.asList(null, "", "  ")) {
            assertTrue(PosterSourcePrioritySetting.read(stored, missing, sites).isEmpty());
            assertEquals(stored, PosterSourcePrioritySetting.write(stored, missing, List.of("a"), sites));
            assertEquals(stored, PosterSourcePrioritySetting.clear(stored, missing));
        }
    }

    @Test
    public void removedOrDisabledSourcesAreSkippedWithoutErasingASlowlyLoadingConfiguration() {
        Site a = site("a");
        Site b = site("b");
        List<Site> sites = List.of(a, b);
        String stored = PosterSourcePrioritySetting.write("", CONFIG, List.of("b", "a"), sites);

        assertTrue(PosterSourcePrioritySetting.read(stored, CONFIG, List.of()).isEmpty());
        assertEquals(List.of("a"), PosterSourcePrioritySetting.read(stored, CONFIG, List.of(a)));
        b.setSearchable(2);
        assertEquals(List.of("a"), PosterSourcePrioritySetting.read(stored, CONFIG, sites));
        b.setSearchable(1);
        assertEquals(List.of("b", "a"), PosterSourcePrioritySetting.read(stored, CONFIG, sites));
    }

    @Test
    public void unavailableKeysInOneConfigurationNeverEraseAnotherConfigurationsPriorities() {
        String otherConfig = "https://other.example/config.json";
        List<Site> sites = List.of(site("a"), site("b"));
        List<Site> otherSites = List.of(site("a"), site("b"));
        String stored = PosterSourcePrioritySetting.write("", CONFIG, List.of("b", "a"), sites);
        stored = PosterSourcePrioritySetting.write(stored, otherConfig, List.of("b", "a"), otherSites);
        sites.get(1).setSearchable(2);

        assertEquals(List.of("a"), PosterSourcePrioritySetting.read(stored, CONFIG, sites));
        assertEquals(List.of("b", "a"), PosterSourcePrioritySetting.read(stored, otherConfig, otherSites));
        String updated = PosterSourcePrioritySetting.write(stored, CONFIG, List.of("b", "a"), sites);
        assertEquals(List.of("a"), PosterSourcePrioritySetting.read(updated, CONFIG, sites));
        assertEquals(List.of("b", "a"), PosterSourcePrioritySetting.read(updated, otherConfig, otherSites));
        assertEquals(List.of("b", "a"), PosterSourcePrioritySetting.read(updated, otherConfig, List.of(otherSites.get(1), otherSites.get(0))));
    }

    @Test
    public void fallbackOrderingPreservesEveryOtherSearchableSourceWithoutMutatingTheConfiguration() {
        Site a = site("a");
        Site b = site("b");
        Site c = site("c");
        Site disabled = site("disabled");
        disabled.setSearchable(0);
        Site longKey = site("x".repeat(600));
        List<Site> sites = new ArrayList<>(Arrays.asList(a, null, b, disabled, c, a, longKey));
        List<Site> before = new ArrayList<>(sites);
        List<Site> ordered = PosterSourcePrioritySetting.orderSites(sites, Arrays.asList("c", "deleted", "c", null));

        assertEquals(List.of(c, a, b, longKey), ordered);
        assertEquals(before, sites);
        assertSame(c, ordered.get(0));
        ordered.clear();
        assertEquals(before, sites);
        assertTrue(PosterSourcePrioritySetting.orderSites(null, List.of("a")).isEmpty());
    }

    @Test
    public void malformedArrayMembersAreIgnoredInsteadOfCoercingThemIntoSourceKeys() {
        List<Site> sites = List.of(site("a"), site("b"), site("42"), site("true"));
        JsonObject object = JsonParser.parseString(PosterSourcePrioritySetting.write("", CONFIG, List.of("a"), sites)).getAsJsonObject();
        String scope = object.keySet().iterator().next();
        object.add(scope, JsonParser.parseString("[\"b\",42,true,null,{},[\"a\"],\"b\",\"a\"]"));

        assertEquals(List.of("b", "a"), PosterSourcePrioritySetting.read(object.toString(), CONFIG, sites));
        object.addProperty(scope, "b");
        assertTrue(PosterSourcePrioritySetting.read(object.toString(), CONFIG, sites).isEmpty());
    }

    @Test
    public void sourceKeysArePreservedExactlyAcrossEscapingAndUnicode() {
        List<String> keys = List.of("影视·甲", "name\"quoted", "path\\key", " source with spaces ", "line\nbreak");
        List<Site> sites = keys.stream().map(PosterSourcePrioritySettingTest::site).toList();
        String stored = PosterSourcePrioritySetting.write("", CONFIG, keys, sites);

        assertEquals(keys, PosterSourcePrioritySetting.read(stored, CONFIG, sites));
    }

    @Test
    public void priorityLimitsDoNotRemoveExtraSourcesFromTheFallbackSearch() {
        List<Site> sites = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            sites.add(site("source-" + i));
            keys.add(0, "source-" + i);
        }
        String stored = PosterSourcePrioritySetting.write("", CONFIG, keys, sites);
        List<String> saved = PosterSourcePrioritySetting.read(stored, CONFIG, sites);

        assertEquals(PosterSourcePrioritySetting.MAX_SOURCES, saved.size());
        assertEquals(keys.subList(0, PosterSourcePrioritySetting.MAX_SOURCES), saved);
        assertEquals(200, PosterSourcePrioritySetting.orderSites(sites, saved).size());
        assertEquals(200, PosterSourcePrioritySetting.orderSites(sites, saved).stream().map(Site::getKey).distinct().count());
    }

    @Test
    public void oldConfigurationsAreBoundedAndSavingOneAgainKeepsItRecent() {
        List<Site> sites = List.of(site("a"), site("b"));
        String stored = "";
        for (int i = 0; i < 32; i++) stored = PosterSourcePrioritySetting.write(stored, "https://config.example/" + i, List.of("a"), sites);
        stored = PosterSourcePrioritySetting.write(stored, "https://config.example/0", List.of("b"), sites);
        stored = PosterSourcePrioritySetting.write(stored, "https://config.example/32", List.of("a"), sites);

        assertEquals(32, JsonParser.parseString(stored).getAsJsonObject().size());
        assertEquals(List.of("b"), PosterSourcePrioritySetting.read(stored, "https://config.example/0", sites));
        assertTrue(PosterSourcePrioritySetting.read(stored, "https://config.example/1", sites).isEmpty());
        assertEquals(List.of("a"), PosterSourcePrioritySetting.read(stored, "https://config.example/32", sites));
    }

    @Test
    public void oversizedAndDeepUnexpectedPreferencesFailSafely() {
        List<Site> sites = List.of(site("a"));
        assertTrue(PosterSourcePrioritySetting.read(" ".repeat(300_000), CONFIG, sites).isEmpty());
        JsonObject object = JsonParser.parseString(PosterSourcePrioritySetting.write("", CONFIG, List.of("a"), sites)).getAsJsonObject();
        String scope = object.keySet().iterator().next();
        String nested = "{\"" + scope + "\":[" + "[".repeat(32) + "0" + "]".repeat(32) + ",\"a\"]}";
        String deeplyNested = "{\"" + scope + "\":[" + "[".repeat(20_000) + "0" + "]".repeat(20_000) + ",\"a\"]}";

        assertEquals(List.of("a"), PosterSourcePrioritySetting.read(nested, CONFIG, sites));
        // Gson's nesting limit rejects this excessive depth; keep the safe original order.
        List<String> keys = PosterSourcePrioritySetting.read(deeplyNested, CONFIG, sites);
        assertTrue(keys.isEmpty());
        assertEquals(sites, PosterSourcePrioritySetting.orderSites(sites, keys));
    }

    @Test
    public void totalStoredPayloadIsBoundedEvenForLongEscapedSourceKeys() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < PosterSourcePrioritySetting.MAX_SOURCES; i++) keys.add("\u0001".repeat(500) + i);
        List<Site> sites = keys.stream().map(PosterSourcePrioritySettingTest::site).toList();
        String stored = "";
        for (int i = 0; i < 3; i++) stored = PosterSourcePrioritySetting.write(stored, "https://config.example/" + i, keys, sites);

        assertTrue(stored.length() <= 256 * 1024);
        List<String> saved = PosterSourcePrioritySetting.read(stored, "https://config.example/2", sites);
        assertFalse(saved.isEmpty());
        assertEquals(keys.subList(0, saved.size()), saved);
        JsonArray values = JsonParser.parseString(stored).getAsJsonObject().entrySet().iterator().next().getValue().getAsJsonArray();
        assertTrue(values.size() <= PosterSourcePrioritySetting.MAX_SOURCES);
    }

    private static Site site(String key) {
        return Site.get(key, key);
    }
}
