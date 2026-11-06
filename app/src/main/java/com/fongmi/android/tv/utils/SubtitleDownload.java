package com.fongmi.android.tv.utils;

import android.os.ParcelFileDescriptor;

import androidx.media3.common.C;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.AssrtSubtitleFile;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.cache.CacheLease;
import com.fongmi.android.tv.player.util.PlayerHelper;
import com.github.catvod.net.OkHttp;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import me.zhanghai.android.libarchive.Archive;
import me.zhanghai.android.libarchive.ArchiveEntry;
import me.zhanghai.android.libarchive.ArchiveException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Downloads selected subtitle files without forwarding the API credential to file hosts. */
public final class SubtitleDownload {

    static final long MAX_BYTES = 64L * 1024 * 1024;
    static final long MAX_EXPANDED_BYTES = 128L * 1024 * 1024;
    private static final int MAX_ENTRIES = 256;
    private static final Set<String> FORMATS = Set.of("srt", "ass", "ssa", "vtt", "ttml", "xml", "dfxp", "sup");
    private static final Pattern TIMING = Pattern.compile("(?:[0-9]{1,2}:)?[0-9]{2}:[0-9]{2}[,.][0-9]+\\s*-->");
    private static final Pattern TTML = Pattern.compile("<(?:(?:[A-Za-z_][A-Za-z0-9_.-]*):)?tt(?:\\s|>)");
    private static final ConcurrentHashMap<Object, Set<Job>> ACTIVE = new ConcurrentHashMap<>();

    private SubtitleDownload() {
    }

    public interface Listener {
        void onSuccess(List<Sub> subtitles);
        void onError(Exception error);
    }

    public static boolean isSupported(String name) {
        String format = extension(name);
        return FORMATS.contains(format) || "zip".equals(format) || "rar".equals(format);
    }

    public static void download(AssrtSubtitleFile file, Object tag, Listener listener) {
        Object key = tag == null ? new Object() : tag;
        Job job = new Job(key);
        ACTIVE.compute(key, (ignored, jobs) -> {
            if (jobs == null) jobs = ConcurrentHashMap.newKeySet();
            jobs.add(job);
            return jobs;
        });
        try {
            if (file == null) throw new IOException("没有可下载的字幕文件");
            HttpUrl url = HttpUrl.parse(file.getUrl());
            if (url == null || !(url.scheme().equals("https") || url.scheme().equals("http"))) {
                throw new IOException("字幕下载地址不可用，请重新打开文件列表");
            }
            String name = file.getName();
            if (!isSupported(name)) name = url.pathSegments().get(url.pathSegments().size() - 1);
            if (!isSupported(name)) throw new IOException("此字幕格式暂不支持，请选择 SRT、ASS、VTT 或其它可用文件");
            if (file.getSizeBytes() > MAX_BYTES) throw new IOException("字幕文件过大，请选择单个字幕文件");
            String selectedName = name;
            // Temporary ASSRT file URLs may use a CDN; API Authorization is deliberately absent.
            Request request = new Request.Builder().url(url).tag(job).build();
            job.call = OkHttp.client().newCall(request);
            if (job.cancelled) { job.call.cancel(); return; }
            job.call.enqueue(new Callback() {
                @Override
                public void onResponse(Call call, Response response) {
                    try (ResponseBody body = response.body()) {
                        if (!response.isSuccessful() || body == null) throw new IOException("字幕下载失败，请重新打开文件列表后重试");
                        if (body.contentLength() > MAX_BYTES) throw new IOException("字幕文件过大，请选择单个字幕文件");
                        File root = new File(App.get().getCacheDir(), "assrt-subtitles");
                        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法创建字幕缓存");
                        job.directory = new File(root, UUID.randomUUID().toString());
                        job.lease = CacheLease.acquire(job.directory);
                        if (!job.directory.mkdir()) throw new IOException("无法创建字幕缓存");
                        List<Sub> subtitles = save(body.byteStream(), selectedName, job.directory, job);
                        deliver(job, () -> listener.onSuccess(subtitles), true);
                    } catch (Exception error) {
                        delete(job.directory);
                        deliver(job, () -> listener.onError(error), false);
                    }
                }

                @Override
                public void onFailure(Call call, IOException error) {
                    delete(job.directory);
                    deliver(job, () -> listener.onError(new IOException("字幕下载失败，请稍后重试")), false);
                }
            });
        } catch (Exception error) {
            deliver(job, () -> listener.onError(error), false);
        }
    }

    public static void cancel(Object tag) {
        if (tag == null) return;
        Set<Job> jobs = ACTIVE.remove(tag);
        if (jobs == null) return;
        for (Job job : jobs) {
            job.cancelled = true;
            if (job.call != null) job.call.cancel();
        }
    }

    /** Remove an unselected download only when its directory belongs to this provider. */
    public static void discard(List<Sub> subtitles) {
        try {
            File root = new File(App.get().getCacheDir(), "assrt-subtitles").getCanonicalFile();
            for (Sub subtitle : subtitles) {
                File directory = new File(subtitle.getUrl()).getCanonicalFile().getParentFile();
                if (directory != null && root.equals(directory.getParentFile())) delete(directory);
            }
        } catch (IOException ignored) {
        }
    }

    private static void deliver(Job job, Runnable callback, boolean success) {
        App.post(() -> {
            boolean[] accepted = {false};
            ACTIVE.computeIfPresent(job.tag, (ignored, jobs) -> {
                accepted[0] = !job.cancelled && jobs.remove(job);
                return jobs.isEmpty() ? null : jobs;
            });
            if (!accepted[0]) {
                delete(job.directory);
                if (job.lease != null) job.lease.close();
                return;
            }
            if (!success) delete(job.directory);
            try {
                callback.run();
            } finally {
                if (job.lease != null) job.lease.close();
            }
        });
    }

    static List<Sub> save(InputStream input, String name, File directory) throws IOException {
        return save(input, name, directory, null);
    }

    private static List<Sub> save(InputStream input, String name, File directory, Job job) throws IOException {
        File raw = new File(directory, "download.part");
        try {
            copy(input, raw, MAX_BYTES, job);
            byte[] header = prefix(raw, 8);
            boolean zip = header.length >= 4 && header[0] == 'P' && header[1] == 'K'
                    && (header[2] == 3 && header[3] == 4 || header[2] == 5 && header[3] == 6);
            boolean rar = header.length >= 7 && header[0] == 'R' && header[1] == 'a'
                    && header[2] == 'r' && header[3] == '!' && header[4] == 0x1a && header[5] == 7;
            List<Sub> result = new ArrayList<>();
            if (rar || extension(name).equals("rar")) {
                result.addAll(extractRar(raw, directory, job));
            } else if (zip || extension(name).equals("zip")) {
                long extracted = 0;
                int entries = 0;
                try (ZipInputStream archive = new ZipInputStream(new BufferedInputStream(new FileInputStream(raw)))) {
                    ZipEntry entry;
                    while ((entry = archive.getNextEntry()) != null) {
                        checkCancelled(job);
                        if (++entries > MAX_ENTRIES) throw new IOException("字幕包文件太多，请选择单个字幕文件");
                        validateEntry(entry.getName());
                        if (entry.isDirectory() || !FORMATS.contains(extension(entry.getName()))) {
                            extracted += discard(archive, MAX_EXPANDED_BYTES - extracted, job);
                            archive.closeEntry();
                            continue;
                        }
                        File target = new File(directory, "subtitle-" + entries + "." + extension(entry.getName()));
                        extracted += copy(archive, target, MAX_EXPANDED_BYTES - extracted, job);
                        validate(target, extension(entry.getName()));
                        result.add(subtitle(entry.getName(), target));
                        archive.closeEntry();
                    }
                }
            } else {
                String format = extension(name);
                if (!FORMATS.contains(format)) throw new IOException("此文件不是支持的字幕格式");
                validate(raw, format);
                File target = new File(directory, "subtitle." + format);
                if (!raw.renameTo(target)) throw new IOException("无法保存字幕文件");
                result.add(subtitle(name, target));
            }
            if (result.isEmpty()) throw new IOException("字幕包里没有支持的字幕文件");
            checkCancelled(job);
            return List.copyOf(result);
        } catch (IOException | RuntimeException error) {
            delete(directory);
            throw error;
        } finally {
            if (raw.exists()) raw.delete();
        }
    }

    private static List<Sub> extractRar(File raw, File directory, Job job) throws IOException {
        List<Sub> result = new ArrayList<>();
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(raw, ParcelFileDescriptor.MODE_READ_ONLY)) {
            long archive = Archive.readNew();
            try {
                Archive.readSupportFormatRar(archive);
                Archive.readSupportFormatRar5(archive);
                Archive.readOpenFd(archive, descriptor.getFd(), 16_384);
                long extracted = 0;
                int entries = 0;
                long entry;
                while ((entry = Archive.readNextHeader(archive)) != 0) {
                    checkCancelled(job);
                    if (++entries > MAX_ENTRIES) throw new IOException("字幕包文件太多，请选择单个字幕文件");
                    if (ArchiveEntry.isEncrypted(entry) || Archive.readHasEncryptedEntries(archive) > 0) {
                        throw new IOException("暂不支持加密字幕包，请选择单个字幕文件");
                    }
                    String name = ArchiveEntry.pathnameUtf8(entry);
                    if (name == null) {
                        byte[] bytes = ArchiveEntry.pathname(entry);
                        if (bytes == null) throw new IOException("字幕包路径无效");
                        name = new String(bytes, StandardCharsets.UTF_8);
                    }
                    validateEntry(name);
                    int type = ArchiveEntry.filetype(entry);
                    if ((type != ArchiveEntry.AE_IFREG && type != ArchiveEntry.AE_IFDIR)
                            || ArchiveEntry.symlink(entry) != null || ArchiveEntry.hardlink(entry) != null) {
                        throw new IOException("字幕包包含不支持的文件类型");
                    }
                    if (ArchiveEntry.size(entry) > MAX_EXPANDED_BYTES - extracted) throw new IOException("字幕包展开后过大，请选择单个字幕文件");
                    String format = extension(name);
                    File target = type == ArchiveEntry.AE_IFREG && FORMATS.contains(format)
                            ? new File(directory, "subtitle-" + entries + "." + format) : null;
                    extracted += readRarEntry(archive, target, MAX_EXPANDED_BYTES - extracted, job);
                    if (target != null) {
                        validate(target, format);
                        result.add(subtitle(name, target));
                    }
                }
            } finally {
                Archive.free(archive);
            }
        } catch (ArchiveException error) {
            throw new IOException("字幕包无法解开，请选择单个字幕文件或其它字幕包", error);
        }
        return result;
    }

    private static long readRarEntry(long archive, File target, long maximum, Job job) throws IOException {
        long total = 0;
        ByteBuffer buffer = ByteBuffer.allocateDirect(16_384);
        byte[] bytes = new byte[buffer.capacity()];
        try (FileOutputStream output = target == null ? null : new FileOutputStream(target)) {
            while (true) {
                checkCancelled(job);
                buffer.clear();
                Archive.readData(archive, buffer);
                int length = buffer.position();
                if (length == 0) break;
                total += length;
                if (total > maximum) throw new IOException("字幕包展开后过大，请选择单个字幕文件");
                if (output != null) {
                    buffer.flip();
                    buffer.get(bytes, 0, length);
                    output.write(bytes, 0, length);
                }
            }
        }
        if (target != null && total == 0) throw new IOException("字幕文件为空");
        return total;
    }

    private static Sub subtitle(String name, File file) {
        String display = name.replace('\\', '/');
        display = display.substring(display.lastIndexOf('/') + 1);
        Sub sub = Sub.from(display, file.getAbsolutePath(), "", PlayerHelper.getSubtitleMimeType(file.getName()));
        sub.setFlag(C.SELECTION_FLAG_FORCED);
        return sub;
    }

    private static long copy(InputStream input, File destination, long maximum, Job job) throws IOException {
        long total = 0;
        byte[] bytes = new byte[16_384];
        try (FileOutputStream output = new FileOutputStream(destination)) {
            int length;
            while ((length = input.read(bytes)) != -1) {
                checkCancelled(job);
                total += length;
                if (total > maximum) throw new IOException("字幕文件过大，请选择单个字幕文件");
                output.write(bytes, 0, length);
            }
        }
        if (total == 0) throw new IOException("字幕文件为空");
        return total;
    }

    private static long discard(InputStream input, long maximum, Job job) throws IOException {
        long total = 0;
        byte[] bytes = new byte[16_384];
        int length;
        while ((length = input.read(bytes)) != -1) {
            checkCancelled(job);
            total += length;
            if (total > maximum) throw new IOException("字幕包展开后过大，请选择单个字幕文件");
        }
        return total;
    }

    private static void validateEntry(String name) throws IOException {
        String path = name.replace('\\', '/');
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*") || path.indexOf('\0') >= 0) throw new IOException("字幕包路径无效");
        for (String part : path.split("/")) if (part.equals("..")) throw new IOException("字幕包路径无效");
    }

    private static void validate(File file, String format) throws IOException {
        byte[] bytes = prefix(file, 65_536);
        if (format.equals("sup")) {
            if (bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'G') return;
            throw new IOException("此文件不是有效的 PGS 字幕");
        }
        String text;
        if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) text = new String(bytes, StandardCharsets.UTF_16LE);
        else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) text = new String(bytes, StandardCharsets.UTF_16BE);
        else text = new String(bytes, StandardCharsets.UTF_8);
        String trimmed = text.replace("\ufeff", "").trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("<html") || lower.startsWith("<!doctype html")) throw new IOException("下载链接可能已失效，请重新打开文件列表");
        boolean valid = switch (format) {
            case "srt" -> TIMING.matcher(text).find();
            case "ass", "ssa" -> lower.contains("[script info]") || lower.contains("[events]");
            case "vtt" -> trimmed.startsWith("WEBVTT");
            case "ttml", "xml", "dfxp" -> TTML.matcher(text).find();
            default -> false;
        };
        if (!valid) throw new IOException("此文件不是有效的字幕，请选择其它文件");
    }

    private static byte[] prefix(File file, int maximum) throws IOException {
        byte[] bytes = new byte[(int) Math.min(file.length(), maximum)];
        int offset = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while (offset < bytes.length && (count = input.read(bytes, offset, bytes.length - offset)) != -1) offset += count;
        }
        return offset == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, offset);
    }

    private static String extension(String name) {
        if (name == null) return "";
        String path = name.toLowerCase(Locale.ROOT);
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1);
    }

    private static void checkCancelled(Job job) throws IOException {
        if (job != null && job.cancelled) throw new IOException("字幕下载已取消");
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    private static final class Job {
        final Object tag;
        volatile Call call;
        volatile boolean cancelled;
        volatile File directory;
        volatile CacheLease lease;

        Job(Object tag) {
            this.tag = tag;
        }
    }
}
