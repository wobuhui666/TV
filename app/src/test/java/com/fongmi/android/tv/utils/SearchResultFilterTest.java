package com.fongmi.android.tv.utils;

import static org.junit.Assert.assertEquals;

import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;

import org.junit.Test;

import java.util.List;

public class SearchResultFilterTest {

    @Test
    public void keepsTitlesContainingNormalizedKeyword() {
        Result result = Result.list(List.of(vod("斗破苍穹 年番"), vod("完美世界"), vod("The Matrix")));

        SearchResultFilter.apply(result, "斗破  苍穹");

        assertEquals(1, result.getList().size());
        assertEquals("斗破苍穹 年番", result.getList().get(0).getName());
    }

    @Test
    public void ignoresCaseAndPunctuation() {
        Result result = Result.list(List.of(vod("The.Matrix Reloaded"), vod("The Lord of the Rings")));

        SearchResultFilter.apply(result, "the matrix");

        assertEquals(1, result.getList().size());
        assertEquals("The.Matrix Reloaded", result.getList().get(0).getName());
    }

    @Test
    public void keepsAllResultsForEmptyKeyword() {
        Result result = Result.list(List.of(vod("任意影片")));

        SearchResultFilter.apply(result, "  ");

        assertEquals(1, result.getList().size());
    }

    private static Vod vod(String name) {
        Vod vod = new Vod();
        vod.setName(name);
        return vod;
    }
}
