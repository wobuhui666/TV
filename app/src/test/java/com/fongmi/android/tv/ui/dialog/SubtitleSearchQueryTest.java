package com.fongmi.android.tv.ui.dialog;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SubtitleSearchQueryTest {

    @Test
    public void shouldKeepDateIssuePartAndVariantInVarietySubtitleQueries() {
        for (String label : new String[]{"20261005", "2026-10-05期", "2026年10月5日 第3期 上期 加更", "第3期", "第3期下", "第3期会员版", "第3期预告"}) {
            assertEquals("Show 第二季 " + label, SubtitleSearchDialog.initialQuery("Show 第二季", label));
        }
    }

    @Test
    public void shouldKeepExistingDramaSubtitleCoordinates() {
        assertEquals("Show S02E03", SubtitleSearchDialog.initialQuery("Show", "S02E03"));
        assertEquals("Show 第二季 S02E03", SubtitleSearchDialog.initialQuery("Show 第二季", "第3集"));
        assertEquals("Show 03", SubtitleSearchDialog.initialQuery("Show", "第3集"));
    }
}
