package com.github.catvod;

import android.app.Activity;
import android.content.Context;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** New SDK name sharing the existing host state with legacy Init callers. */
public class Runtime {

    public static void set(Context context) {
        Init.set(context);
    }

    public static Context context() {
        return Init.context();
    }

    public static void setActivity(Supplier<Activity> supplier) {
        Init.setActivity(supplier);
    }

    public static Activity activity() {
        return Init.activity();
    }

    public static void setToast(Consumer<String> consumer) {
        Init.setToast(consumer);
    }

    public static void toast(String text) {
        Init.toast(text);
    }

    public static void setDialog(BiFunction<String, Function<String, CompletableFuture<String>>, CompletableFuture<String>> host) {
        Init.setDialog(host);
    }

    public static CompletableFuture<String> dialog(String options, Function<String, CompletableFuture<String>> callback) {
        return Init.dialog(options, callback);
    }
}
