package com.fongmi.android.tv.ui.home;

import com.fongmi.android.tv.bean.Result;

import java.util.Objects;

/** Source previews and library categories always belong to the same config and request. */
public final class HomeSourceState {
    public record Request(int generation, int cid, String key) {}

    private int generation;
    private Request request;
    private Result result = Result.empty();
    private boolean loading;

    public Request begin(int cid, String key) {
        request = new Request(++generation, cid, key);
        result = Result.empty();
        loading = !key.isEmpty();
        return request;
    }

    public boolean accepts(Request candidate, int cid, String key) {
        return candidate != null && candidate.equals(request) && candidate.cid() == cid
                && Objects.equals(candidate.key(), key);
    }

    public boolean complete(Request candidate, int cid, String key, Result value) {
        if (!accepts(candidate, cid, key)) return false;
        result = value == null ? Result.empty() : value;
        loading = false;
        return true;
    }

    public Request getRequest() { return request; }
    public Result getResult() { return result; }
    public boolean isLoading() { return loading; }
}
