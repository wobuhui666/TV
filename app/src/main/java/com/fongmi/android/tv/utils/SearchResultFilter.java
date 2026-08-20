package com.fongmi.android.tv.utils;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.source.MediaMatcher;

import java.util.ArrayList;
import java.util.List;

/** Removes site-side fuzzy matches that do not contain the requested title. */
public final class SearchResultFilter {

    private SearchResultFilter() {
    }

    @NonNull
    public static Result apply(@NonNull Result result, String keyword) {
        if (keyword == null || keyword.trim().isEmpty() || result.getList().isEmpty()) return result;

        List<Vod> filtered = new ArrayList<>();
        for (Vod item : result.getList()) {
            if (item != null && MediaMatcher.queryMatches(keyword, item)) filtered.add(item);
        }
        result.setList(filtered);
        return result;
    }
}
