package com.fongmi.android.tv.ui.dialog;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.cache.CacheManager;
import com.fongmi.android.tv.cache.CacheResult;
import com.fongmi.android.tv.diagnostics.DiagnosticManager;
import com.fongmi.android.tv.drive.DriveCheckResult;
import com.fongmi.android.tv.drive.DriveLinkChecker;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.QRCode;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.diagnostics.DiagnosticLog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import okhttp3.OkHttpClient;

/** Shared standard Material dialogs for TV and touch devices. All disk/network work is off the UI thread. */
public final class MaintenanceDialog {
    private MaintenanceDialog() {}

    public static void show(FragmentActivity activity) {
        String[] items = {activity.getString(R.string.maintenance_diagnostics),
                activity.getString(R.string.maintenance_drive), activity.getString(R.string.maintenance_health)};
        showDialog(new MaterialAlertDialogBuilder(activity).setTitle(R.string.maintenance_title)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) diagnostics(activity);
                    else if (which == 1) drive(activity);
                    else SourceHealthDialog.show(activity);
                }).setNegativeButton(R.string.dialog_negative, null).show());
    }

    public static void cache(FragmentActivity activity, Runnable refreshed) {
        work(activity, CacheManager::snapshot, entries -> {
            String[] items = new String[entries.size()];
            for (int i = 0; i < entries.size(); i++) {
                CacheManager.Entry entry = entries.get(i);
                items[i] = cacheName(activity, entry.category) + " · " + FileUtil.byteCountToDisplaySize(entry.bytes);
            }
            showDialog(new MaterialAlertDialogBuilder(activity).setTitle(R.string.maintenance_cache)
                    .setItems(items, (dialog, which) -> {
                        CacheManager.Category category = entries.get(which).category;
                        if (category == CacheManager.Category.OTHER) {
                            message(activity, R.string.maintenance_cache, activity.getString(R.string.maintenance_cache_retained));
                        } else {
                            work(activity, () -> CacheManager.clear(category), result -> {
                                refreshed.run();
                                cacheResult(activity, result);
                            });
                        }
                    }).setNeutralButton(R.string.maintenance_clear_safe, (dialog, which) ->
                            work(activity, CacheManager::clearAll, result -> {
                                refreshed.run();
                                cacheResult(activity, result);
                            }))
                    .setNegativeButton(R.string.dialog_negative, null).show());
        });
    }

    private static String cacheName(Context context, CacheManager.Category category) {
        return context.getString(switch (category) {
            case IMAGES -> R.string.maintenance_cache_images;
            case PLAYBACK -> R.string.maintenance_cache_playback;
            case UPDATES -> R.string.maintenance_cache_updates;
            case OTHER -> R.string.maintenance_cache_other;
        });
    }

    private static void cacheResult(FragmentActivity activity, CacheResult result) {
        String text = activity.getString(R.string.maintenance_cache_result,
                FileUtil.byteCountToDisplaySize(result.releasedBytes), result.skippedFiles, result.failedFiles);
        if (result.reasons.containsKey(CacheResult.Reason.IN_USE) || result.reasons.containsKey(CacheResult.Reason.TOO_RECENT)) {
            text += "\n\n" + activity.getString(R.string.maintenance_cache_busy);
        }
        if (result.cancelled) text += "\n" + activity.getString(R.string.maintenance_cancelled);
        message(activity, R.string.maintenance_cache, text);
    }

    private static void diagnostics(FragmentActivity activity) {
        boolean enabled = DiagnosticManager.isEnabled();
        String[] items = {activity.getString(enabled ? R.string.maintenance_log_stop : R.string.maintenance_log_start),
                activity.getString(R.string.maintenance_log_mark), activity.getString(R.string.maintenance_log_download),
                activity.getString(R.string.maintenance_log_share), activity.getString(R.string.maintenance_log_clear)};
        showDialog(new MaterialAlertDialogBuilder(activity).setTitle(R.string.maintenance_diagnostics)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        work(activity, () -> {
                            if (DiagnosticManager.isEnabled()) DiagnosticManager.stop();
                            else DiagnosticManager.start(activity.getApplicationContext());
                            return DiagnosticManager.isEnabled();
                        }, running -> message(activity, R.string.maintenance_diagnostics,
                                activity.getString(running ? R.string.maintenance_log_started : R.string.maintenance_log_stopped)));
                    } else if (which == 1) {
                        if (!DiagnosticManager.isEnabled()) { Notify.show(R.string.maintenance_log_enable_first); return; }
                        DiagnosticManager.markIncident("User marked a playback problem");
                        Notify.show(R.string.maintenance_log_marked);
                    } else if (which == 2) {
                        if (!DiagnosticManager.isEnabled()) { Notify.show(R.string.maintenance_log_enable_first); return; }
                        work(activity, () -> DiagnosticManager.getDownloadUrl(activity), url -> download(activity, url));
                    } else if (which == 3) {
                        if (!DiagnosticManager.isEnabled()) { Notify.show(R.string.maintenance_log_enable_first); return; }
                        work(activity, () -> DiagnosticManager.exportShareZip(activity), file -> share(activity, file));
                    } else {
                        work(activity, () -> {
                            DiagnosticManager.stop();
                            DiagnosticManager.clear(activity);
                            return true;
                        }, done -> Notify.show(R.string.maintenance_log_cleared));
                    }
                }).setNegativeButton(R.string.dialog_negative, null).show());
    }

    private static void download(FragmentActivity activity, String url) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = ResUtil.dp2px(20);
        content.setPadding(padding, 0, padding, 0);
        ImageView qr = new ImageView(activity);
        qr.setImageBitmap(QRCode.getThemedBitmap(activity, url, 180, 4));
        qr.setContentDescription(activity.getString(R.string.maintenance_download_qr));
        content.addView(qr, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(180)));
        MaterialTextView hint = new MaterialTextView(activity);
        hint.setText(activity.getString(R.string.maintenance_download_hint));
        content.addView(hint);
        showDialog(new MaterialAlertDialogBuilder(activity).setTitle(R.string.maintenance_log_download)
                .setView(content).setPositiveButton(R.string.maintenance_copy_link, (dialog, which) -> copy(activity, url))
                .setNegativeButton(R.string.dialog_negative, null).show());
    }

    private static void share(FragmentActivity activity, File file) {
        synchronized (DiagnosticManager.class) {
            if (!DiagnosticManager.canShare(file)) { Notify.show(R.string.maintenance_log_enable_first); return; }
            try {
                android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(activity,
                        com.fongmi.android.tv.BuildConfig.APPLICATION_ID + ".provider", file);
                Intent intent = new Intent(Intent.ACTION_SEND).setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("diagnostics", uri));
                activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.maintenance_log_share)));
            } catch (Exception unavailable) {
                Notify.show(R.string.maintenance_share_unavailable);
            }
        }
    }

    private static void drive(FragmentActivity activity) {
        TextInputLayout input = new TextInputLayout(activity);
        int padding = ResUtil.dp2px(20);
        input.setPadding(padding, 0, padding, 0);
        input.setHint(activity.getString(R.string.maintenance_drive_hint));
        TextInputEditText edit = new TextInputEditText(input.getContext());
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4096)});
        edit.setMinLines(2);
        edit.setMaxLines(4);
        input.addView(edit);
        showDialog(new MaterialAlertDialogBuilder(activity).setTitle(R.string.maintenance_drive)
                .setView(input).setPositiveButton(R.string.maintenance_check, (dialog, which) ->
                        checkDrive(activity, edit.getText() == null ? "" : edit.getText().toString()))
                .setNegativeButton(R.string.dialog_negative, null).show());
    }

    private static void checkDrive(FragmentActivity activity, String text) {
        DriveLinkChecker checker = new DriveLinkChecker(new OkHttpClient());
        AtomicBoolean closed = new AtomicBoolean();
        AlertDialog waiting = new MaterialAlertDialogBuilder(activity).setTitle(R.string.maintenance_drive)
                .setMessage(R.string.maintenance_checking).setNegativeButton(R.string.dialog_negative, null).create();
        Future<?> task = Task.submit(() -> {
            try {
                DriveCheckResult result = checker.check(text);
                App.post(() -> {
                    if (closed.get() || !alive(activity)) return;
                    waiting.dismiss();
                    String state = activity.getString(switch (result.status) {
                        case OK -> R.string.maintenance_drive_ok;
                        case BAD -> R.string.maintenance_drive_bad;
                        case LOCKED -> R.string.maintenance_drive_locked;
                        case UNSUPPORTED -> R.string.maintenance_drive_unsupported;
                        case UNCERTAIN -> R.string.maintenance_drive_uncertain;
                    });
                    String title = result.provider.isEmpty() ? state : result.provider + " · " + state;
                    message(activity, R.string.maintenance_drive,
                            title + "\n\n" + result.message + "\n\n"
                                    + activity.getString(R.string.maintenance_drive_advisory));
                });
            } catch (Exception error) {
                App.post(() -> {
                    if (closed.get() || !alive(activity)) return;
                    waiting.dismiss();
                    message(activity, R.string.maintenance_drive, activity.getString(R.string.maintenance_drive_invalid));
                });
            }
        });
        androidx.lifecycle.LifecycleEventObserver lifecycle = (owner, event) -> {
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP || event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
                closed.set(true);
                checker.cancel();
                task.cancel(true);
                waiting.dismiss();
            }
        };
        activity.getLifecycle().addObserver(lifecycle);
        waiting.setOnDismissListener(dialog -> {
            closed.set(true);
            checker.cancel();
            task.cancel(true);
            activity.getLifecycle().removeObserver(lifecycle);
        });
        showDialog(waiting);
    }

    private static void copy(Context context, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("TV", text));
        Notify.show(R.string.maintenance_copied);
    }

    static void message(FragmentActivity activity, int title, String text) {
        if (!alive(activity)) return;
        showDialog(new MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(text)
                .setPositiveButton(R.string.dialog_positive, null).show());
    }

    static void showDialog(AlertDialog dialog) {
        if (!dialog.isShowing()) dialog.show();
        if (dialog.getListView() != null) dialog.getListView().post(() -> {
            if (dialog.isShowing()) dialog.getListView().requestFocus();
        });
    }

    static boolean alive(FragmentActivity activity) {
        return !activity.isFinishing() && !activity.isDestroyed()
                && !activity.getSupportFragmentManager().isStateSaved();
    }

    static <T> void work(FragmentActivity activity, Callable<T> task, Consumer<T> done) {
        Notify.show(R.string.maintenance_working);
        Task.execute(() -> {
            try {
                T result = task.call();
                App.post(() -> { if (alive(activity)) done.accept(result); });
            } catch (Exception error) {
                DiagnosticLog.record("maintenance", error);
                App.post(() -> { if (alive(activity)) Notify.show(R.string.maintenance_failed); });
            }
        });
    }
}
