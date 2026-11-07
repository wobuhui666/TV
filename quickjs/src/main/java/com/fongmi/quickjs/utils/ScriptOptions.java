package com.fongmi.quickjs.utils;

import com.github.catvod.utils.Json;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class ScriptOptions {

    private static final Gson GSON = new Gson();
    // Capture builtins before globals are injected; every evaluation has a fresh context.
    private static final String RUN = """
            ((globals, source) => {
                const target = globalThis, evaluate = eval, stringify = JSON.stringify;
                const define = Object.defineProperty, keys = Object.keys;
                const resolve = Promise.resolve.bind(Promise);
                const serialize = value => {
                    const json = stringify(value, (key, item) => {
                        if (item && typeof item.then === 'function') throw new TypeError('Script result must be JSON');
                        return item;
                    });
                    if (json === undefined) throw new TypeError('Script result must be JSON');
                    return json;
                };
                for (const key of keys(globals)) define(target, key, {value: globals[key], writable: true, configurable: true});
                return {run: () => resolve(evaluate(source)).then(serialize)};
            })
            """;

    final long timeout;
    final String script;

    ScriptOptions(String source, String options) {
        if (source == null) throw new IllegalArgumentException("Script source is required");
        JsonElement value = Json.strict(options == null ? "{}" : options);
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("Script options require an object");
        JsonObject settings = value.getAsJsonObject();
        timeout = timeout(settings);
        JsonElement globals = settings.has("globals") ? settings.get("globals") : new JsonObject();
        if (!globals.isJsonObject() || globals.getAsJsonObject().has("md5X") || globals.getAsJsonObject().has("format")) throw new IllegalArgumentException("Invalid script globals");
        script = RUN + "(JSON.parse(" + GSON.toJson(globals.toString()) + ")," + GSON.toJson(source) + ")";
    }

    private static long timeout(JsonObject settings) {
        if (!settings.has("timeout")) return 5000;
        JsonElement value = settings.get("timeout");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Invalid script timeout");
        try {
            long timeout = value.getAsBigDecimal().longValueExact();
            if (timeout < 1 || timeout > 9007199254740991L) throw new IllegalArgumentException("Invalid script timeout");
            return timeout;
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("Invalid script timeout", error);
        }
    }
}
