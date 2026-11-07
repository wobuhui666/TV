package com.fongmi.android.tv.server.process;

import android.app.Activity;
import android.app.Dialog;
import android.os.SystemClock;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedDispatcherOwner;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.server.impl.Process;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import okhttp3.HttpUrl;

/**
 * App-window key dispatch adapted from FongMi/TV (GPL-3.0),
 * commit 4512b4c9f82534ff66acfde06ccdd68f35b074f0, server/process/Remote.java.
 * Each request is a complete tap; it never holds a key across network requests.
 */
public final class RemoteKey implements Process {

    private static final Map<String, Integer> KEYS = Map.ofEntries(
            Map.entry("up", KeyEvent.KEYCODE_DPAD_UP), Map.entry("down", KeyEvent.KEYCODE_DPAD_DOWN),
            Map.entry("left", KeyEvent.KEYCODE_DPAD_LEFT), Map.entry("right", KeyEvent.KEYCODE_DPAD_RIGHT),
            Map.entry("ok", KeyEvent.KEYCODE_DPAD_CENTER), Map.entry("back", KeyEvent.KEYCODE_BACK),
            Map.entry("menu", KeyEvent.KEYCODE_MENU), Map.entry("0", KeyEvent.KEYCODE_0),
            Map.entry("1", KeyEvent.KEYCODE_1), Map.entry("2", KeyEvent.KEYCODE_2),
            Map.entry("3", KeyEvent.KEYCODE_3), Map.entry("4", KeyEvent.KEYCODE_4),
            Map.entry("5", KeyEvent.KEYCODE_5), Map.entry("6", KeyEvent.KEYCODE_6),
            Map.entry("7", KeyEvent.KEYCODE_7), Map.entry("8", KeyEvent.KEYCODE_8),
            Map.entry("9", KeyEvent.KEYCODE_9));

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.equals("/remote/key");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        if (session.getMethod() != Method.POST) return response(Status.METHOD_NOT_ALLOWED, "POST required");
        if (!sameOrigin(session.getHeaders())) return response(Status.FORBIDDEN, "Same origin required");
        Map<String, String> params = session.getParms();
        Integer key = KEYS.get(params.getOrDefault("key", ""));
        if (key == null || !"press".equals(params.getOrDefault("action", "press"))) {
            return response(Status.BAD_REQUEST, "Invalid remote key");
        }
        FutureTask<Response> task = new FutureTask<>(() -> {
            Window.Callback target = target();
            if (target == null) return response(Status.CONFLICT, "Remote key unavailable");
            long time = SystemClock.uptimeMillis();
            try {
                send(target, new KeyEvent(time, time, KeyEvent.ACTION_DOWN, key, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0));
            } finally {
                send(target, new KeyEvent(time, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, key, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0));
            }
            return response(Status.OK, "");
        });
        App.post(task);
        try {
            return task.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return response(Status.SERVICE_UNAVAILABLE, "Request interrupted");
        } catch (TimeoutException e) {
            return response(Status.SERVICE_UNAVAILABLE, "App busy");
        } catch (ExecutionException e) {
            return response(Status.INTERNAL_ERROR, "Remote key failed");
        } finally {
            task.cancel(false);
            App.removeCallbacks(task);
        }
    }

    private Window.Callback target() {
        Activity activity = App.activity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        if (activity.hasWindowFocus()) return activity;
        return activity instanceof FragmentActivity owner ? dialogTarget(owner.getSupportFragmentManager()) : null;
    }

    private static boolean sameOrigin(Map<String, String> headers) {
        String origin = headers.get("origin");
        if (origin == null) return true;
        String host = headers.get("host");
        if (host == null || host.isBlank()) return false;
        HttpUrl source = HttpUrl.parse(origin);
        HttpUrl destination = HttpUrl.parse("http://" + host);
        return isOrigin(source) && isOrigin(destination) && source.scheme().equals(destination.scheme())
                && source.host().equals(destination.host()) && source.port() == destination.port();
    }

    private static boolean isOrigin(HttpUrl url) {
        return url != null && url.username().isEmpty() && url.password().isEmpty()
                && url.encodedPath().equals("/") && url.query() == null && url.fragment() == null;
    }

    private Window.Callback dialogTarget(FragmentManager manager) {
        for (Fragment fragment : manager.getFragments()) {
            if (!fragment.isAdded()) continue;
            if (fragment instanceof DialogFragment owner) {
                Dialog dialog = owner.getDialog();
                if (dialog != null && dialog.getWindow() != null && dialog.getWindow().getDecorView().hasWindowFocus()) {
                    return dialog;
                }
            }
            Window.Callback target = dialogTarget(fragment.getChildFragmentManager());
            if (target != null) return target;
        }
        return null;
    }

    private void send(Window.Callback target, KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && target instanceof ComponentActivity owner) {
            if (event.getAction() == KeyEvent.ACTION_UP) owner.getOnBackPressedDispatcher().onBackPressed();
        } else {
            boolean handled = target.dispatchKeyEvent(event);
            if (!handled && event.getKeyCode() == KeyEvent.KEYCODE_BACK
                    && event.getAction() == KeyEvent.ACTION_UP && target instanceof Dialog dialog
                    && dialog.isShowing() && dialog.getWindow() != null
                    && dialog.getWindow().getDecorView().hasWindowFocus()) {
                // Android 13+ Dialog key-up skips legacy back when predictive back is enabled.
                if (dialog instanceof OnBackPressedDispatcherOwner owner) owner.getOnBackPressedDispatcher().onBackPressed();
                else dialog.onBackPressed();
            }
            // Direct window callbacks bypass ViewRootImpl's unhandled native D-pad navigation.
            if (!handled && event.getAction() == KeyEvent.ACTION_DOWN) moveFocus(target, direction(event.getKeyCode()));
        }
    }

    private static int direction(int key) {
        return switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT;
            default -> 0;
        };
    }

    private void moveFocus(Window.Callback target, int direction) {
        if (direction == 0) return;
        Window window = target instanceof Activity activity ? activity.getWindow()
                : target instanceof Dialog dialog ? dialog.getWindow() : null;
        if (window == null) return;
        View decor = window.getDecorView();
        if (!decor.hasWindowFocus()) return;
        View focused = decor.findFocus();
        if (decor.isInTouchMode()) {
            if (focused == null) {
                decor.requestFocusFromTouch();
                return;
            }
            focused.requestFocusFromTouch();
        }
        View next = focused == null ? decor.focusSearch(direction) : focused.focusSearch(direction);
        if (next != null && next != focused) {
            if (!next.requestFocus(direction)) next.requestFocusFromTouch();
        } else if (focused == null) {
            if (!decor.requestFocus(direction)) decor.requestFocusFromTouch();
        }
    }

    private static Response response(Status status, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("accepted", status == Status.OK);
        if (!message.isEmpty()) body.addProperty("message", message);
        Response response = NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString());
        response.addHeader("Cache-Control", "no-store");
        return response;
    }
}
