package com.fongmi.android.tv.api;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DanmakuApiTest {

    @Test
    public void shouldRequireManualChoiceForComplexVarietyLabelsFromLegacyApis() {
        for (String label : new String[]{"20261005", "2026-10-05期", "第3期上", "第3期下", "第3期 加更", "第3期会员版", "第3期纯享版", "第3期预告", "第3期下预告", "第3期下 预告", "第3期下预告片", "第3期下特别篇", "第3期下 特辑", "2026-02-30", "２０２６０２３０", "20261005 20261006"}) {
            assertFalse(label, DanmakuApi.canAutoSearchLegacy(label));
        }
    }

    @Test
    public void shouldKeepSimpleIssueAndExistingDramaLegacyCompatibility() {
        for (String label : new String[]{"第3期", "第3集", "S02E03", "第3集上", "[ANi] Show - 03 [1080p].mkv"}) {
            assertTrue(label, DanmakuApi.canAutoSearchLegacy(label));
        }
    }
}
