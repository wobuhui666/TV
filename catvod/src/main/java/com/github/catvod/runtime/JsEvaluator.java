package com.github.catvod.runtime;

import java.util.concurrent.CompletableFuture;

/** The script module returns JSON text without exposing engine-owned values. */
@FunctionalInterface
public interface JsEvaluator {
    CompletableFuture<String> evaluate(String source, String options);
}
