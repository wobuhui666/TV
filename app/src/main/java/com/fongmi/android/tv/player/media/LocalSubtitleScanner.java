package com.fongmi.android.tv.player.media;

import android.content.Context;
import android.os.Looper;

import androidx.media3.common.C;

import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.util.PlayerHelper;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.SubtitleArchive;
import com.fongmi.android.tv.utils.UrlUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bounded, nonrecursive sidecar discovery. Call from the media-resolution worker. */
public final class LocalSubtitleScanner {

    static final int MAX_DIRECTORY_ENTRIES = 4096;
    static final int MAX_SUBTITLES = 16;

    private LocalSubtitleScanner() {
    }

    public static List<Sub> scan(Context context, String url) {
        Looper main = Looper.getMainLooper();
        if (context == null || url == null || url.isEmpty() || Thread.currentThread().isInterrupted() || main != null && main == Looper.myLooper()) return List.of();
        File video = FileChooser.getLocalFile(context, UrlUtil.uri(url));
        if (video == null) return List.of();
        List<Sub> result = new ArrayList<>();
        String stem = removeExtension(video.getName());
        for (File file : findFiles(video)) {
            if (Thread.currentThread().isInterrupted()) return List.of();
            Sub sub = Sub.from(file.getName(), file.toURI().toString(), language(stem, file.getName()), PlayerHelper.getSubtitleMimeType(file.getName()));
            sub.setFlag(result.isEmpty() ? C.SELECTION_FLAG_DEFAULT : C.SELECTION_FLAG_AUTOSELECT);
            result.add(sub);
        }
        return result;
    }

    static List<File> findFiles(File video) {
        if (video == null || Thread.currentThread().isInterrupted() || !video.isFile()) return List.of();
        File parent = video.getParentFile();
        if (parent == null || !parent.isDirectory()) return List.of();
        String stem = removeExtension(video.getName());
        if (stem.isEmpty()) return List.of();
        Set<File> matching = new LinkedHashSet<>();
        // Exact sidecars remain discoverable even when the surrounding directory is huge.
        for (String extension : SubtitleArchive.EXTENSIONS) {
            if (Thread.currentThread().isInterrupted()) return List.of();
            add(matching, new File(parent, stem + "." + extension), stem);
            add(matching, new File(parent, stem + "." + extension.toUpperCase(Locale.ROOT)), stem);
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent.toPath())) {
            int count = 0;
            for (Path entry : entries) {
                if (Thread.currentThread().isInterrupted()) return List.of();
                if (++count > MAX_DIRECTORY_ENTRIES || matching.size() >= MAX_SUBTITLES) break;
                add(matching, entry.toFile(), stem);
            }
        } catch (IOException | RuntimeException ignored) {
            // A failed directory enumeration must not prevent the original video playing.
        }
        if (Thread.currentThread().isInterrupted()) return List.of();
        return matching.stream().sorted(Comparator
                .comparingInt((File file) -> removeExtension(file.getName()).equalsIgnoreCase(stem) ? 0 : 1)
                .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER).thenComparing(File::getName))
                .limit(MAX_SUBTITLES).toList();
    }

    private static void add(Set<File> files, File candidate, String stem) {
        if (files.size() >= MAX_SUBTITLES || !SubtitleArchive.isSubtitle(candidate.getName()) || !matches(stem, candidate.getName())) return;
        if (candidate.isFile() && candidate.canRead()) files.add(candidate);
    }

    static boolean matches(String videoStem, String subtitleName) {
        String video = videoStem.toLowerCase(Locale.ROOT);
        String subtitle = removeExtension(subtitleName).toLowerCase(Locale.ROOT);
        return subtitle.equals(video) || subtitle.startsWith(video + ".");
    }

    private static String removeExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static String language(String stem, String subtitleName) {
        String name = removeExtension(subtitleName);
        if (name.length() <= stem.length()) return "";
        String language = name.substring(stem.length() + 1).split("\\.", 2)[0];
        return language.matches("(?i)[a-z]{2,3}(?:-[a-z]{2}|-[a-z]{4})?") ? language : "";
    }
}
