package com.github.catvod;

import android.app.Activity;
import android.content.Context;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Compile-only legacy API, isolated from the implementation being tested. */
public class Init {
    public static void set(Context context) {}
    public static Context context() { return null; }
    public static void setActivity(Supplier<Activity> supplier) {}
    public static Activity activity() { return null; }
    public static void setToast(Consumer<String> consumer) {}
    public static void toast(String text) {}
    public static void setDialog(BiFunction<String, Function<String, CompletableFuture<String>>, CompletableFuture<String>> host) {}
    public static CompletableFuture<String> dialog(String options, Function<String, CompletableFuture<String>> callback) { return null; }
}
