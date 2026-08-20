package com.fongmi.android.tv.utils;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.github.catvod.utils.Trans;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Removes site-side fuzzy matches that do not contain the requested title. */
public final class SearchResultFilter {

    private SearchResultFilter() {
    }

    @NonNull
    public static Result apply(@NonNull Result result, String keyword) {
        String query = normalize(keyword);
        if (query.isEmpty() || result.getList().isEmpty()) return result;

        List<Vod> filtered = new ArrayList<>();
        for (Vod item : result.getList()) {
            if (item != null && normalize(item.getName()).contains(query)) filtered.add(item);
        }
        result.setList(filtered);
        return result;
    }

    private static String normalize(String value) {
        String text = Trans.t2s(value == null ? "" : value).toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(text.length());
        text.codePoints()
                .filter(codePoint -> Character.isLetterOrDigit(codePoint))
                .forEach(normalized::appendCodePoint);
        return normalized.toString();
    }
}
