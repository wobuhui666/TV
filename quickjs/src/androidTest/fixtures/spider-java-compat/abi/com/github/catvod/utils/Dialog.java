package com.github.catvod.utils;

import org.json.JSONObject;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Compile-only exact JVM descriptors for the shared dialog ABI. */
public final class Dialog implements AutoCloseable {
    public JSONObject show(JSONObject options) throws Exception { return null; }
    public JSONObject show(JSONObject options, Callback callback) throws Exception { return null; }
    public CompletableFuture<String> open(String options, Function<String, CompletableFuture<String>> callback) { return null; }
    public void close() {}
    public interface Callback { JSONObject onAction(JSONObject event) throws Exception; }
}
