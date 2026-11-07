package com.fongmi.android.tv.server.process;

import static fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT;
import static fi.iki.elonen.NanoHTTPD.getMimeTypeForFile;
import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Formatters;
import com.github.catvod.utils.Path;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.zip.CRC32;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import okhttp3.HttpUrl;

public class Local implements Process {

    private static final Object FILE_MUTATIONS = new Object();
    private final File storageRoot;

    public Local() {
        this(null);
    }

    Local(File storageRoot) {
        this.storageRoot = storageRoot;
    }

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/file") || url.startsWith("/upload") || url.startsWith("/newFolder") || url.startsWith("/delFolder") || url.startsWith("/delFile")
                || url.equals("/rename") || url.equals("/move");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        if (url.equals("/rename") || url.equals("/move")) return relocate(session, url);
        if (url.startsWith("/file")) return getFile(session.getHeaders(), url);
        if (url.startsWith("/upload")) return upload(session.getParms(), files);
        if (url.startsWith("/newFolder")) return newFolder(session.getParms());
        if (url.startsWith("/delFolder") || url.startsWith("/delFile")) return delete(session.getParms());
        return null;
    }

    /** File-manager operations follow the FongMi /rename and /move contracts. */
    private Response relocate(IHTTPSession session, String url) {
        if (session.getMethod() != fi.iki.elonen.NanoHTTPD.Method.POST) {
            return Nano.error(Status.METHOD_NOT_ALLOWED, "POST required");
        }
        String origin = session.getHeaders().get("origin");
        if (origin != null) {
            HttpUrl source = HttpUrl.parse(origin);
            HttpUrl host = HttpUrl.parse("http://" + session.getHeaders().getOrDefault("host", ""));
            if (source == null || !source.equals(host)) return Nano.error(Status.FORBIDDEN, "Same origin required");
        }
        synchronized (FILE_MUTATIONS) {
            return relocate(session.getParms(), url);
        }
    }

    private Response relocate(Map<String, String> params, String url) {
        try {
            File root = (storageRoot == null ? Path.root() : storageRoot).getCanonicalFile();
            File source = resolve(root, params.get("path"));
            if (source.getCanonicalFile().equals(root)) throw new IllegalArgumentException("Cannot move the storage root");
            if (!source.exists()) throw new NoSuchFileException(source.getName());
            File directory = url.equals("/rename") ? source.getParentFile() : resolve(root, params.get("target"));
            if (!directory.isDirectory()) throw new NoSuchFileException("Target directory not found");
            String name = url.equals("/rename") ? params.get("name") : source.getName();
            requireName(name);
            File target = new File(directory, name).getAbsoluteFile();
            requireWithin(root, target);
            if (source.equals(target)) return Nano.ok();
            if (target.exists()) throw new FileAlreadyExistsException(name);
            if (source.isDirectory() && directory.getCanonicalFile().toPath().startsWith(source.getCanonicalFile().toPath())) {
                throw new IllegalArgumentException("Cannot move a directory into itself");
            }
            Files.move(source.toPath(), target.toPath());
            return Nano.ok();
        } catch (FileAlreadyExistsException e) {
            return Nano.error(Status.CONFLICT, "A file with that name already exists");
        } catch (NoSuchFileException e) {
            return Nano.error(Status.NOT_FOUND, "File or target directory not found");
        } catch (IllegalArgumentException | SecurityException e) {
            return Nano.error(Status.BAD_REQUEST, "Invalid file name or target path");
        } catch (IOException e) {
            return Nano.error("Unable to move this file");
        }
    }

    private static File resolve(File root, String path) throws IOException {
        if (path == null) throw new IllegalArgumentException("Missing path");
        while (path.startsWith("/")) path = path.substring(1);
        File file = new File(root, path).getAbsoluteFile();
        requireWithin(root, file);
        return file;
    }

    private static void requireWithin(File root, File file) throws IOException {
        if (!file.getCanonicalFile().toPath().startsWith(root.toPath())) throw new SecurityException("Path outside shared storage");
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid name");
        }
    }

    private Response getFile(Map<String, String> headers, String path) {
        try {
            File file = Path.local(path.substring(5));
            if (file.isDirectory()) return getFolder(file);
            if (file.isFile()) return getFile(headers, file, getMimeTypeForFile(path));
            return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "");
        } catch (FileNotFoundException e) {
            return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "");
        } catch (Exception e) {
            return Nano.error(e.getMessage());
        }
    }

    private Response upload(Map<String, String> params, Map<String, String> files) {
        String path = params.get("path");
        for (String k : files.keySet()) {
            String fn = params.get(k);
            File temp = new File(files.get(k));
            if (fn.toLowerCase().endsWith(".zip")) FileUtil.zipDecompress(temp, Path.root(path));
            else Path.copy(temp, Path.root(path, fn));
        }
        return Nano.ok();
    }

    private Response newFolder(Map<String, String> params) {
        String path = params.get("path");
        String name = params.get("name");
        Path.root(path, name).mkdirs();
        return Nano.ok();
    }

    private Response delete(Map<String, String> params) {
        String path = params.get("path");
        Path.clear(Path.root(path));
        return Nano.ok();
    }

    private Response getFolder(File dir) {
        File rootDir = Path.root();
        String rootPath = rootDir.getAbsolutePath();
        JsonArray files = new JsonArray();
        for (File file : Path.list(dir)) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", file.getName());
            obj.addProperty("path", relativeTo(file, rootPath));
            obj.addProperty("time", Formatters.LOCAL_DATETIME.format(Instant.ofEpochMilli(file.lastModified()).atZone(ZoneId.systemDefault())));
            obj.addProperty("dir", file.isDirectory() ? 1 : 0);
            files.add(obj);
        }
        JsonObject info = new JsonObject();
        info.addProperty("parent", parentOf(dir, rootDir, rootPath));
        info.add("files", files);
        return Nano.ok(info.toString());
    }

    private Response getFile(Map<String, String> headers, File file, String mime) throws IOException {
        long fileLen = file.length();
        String etag = etag(file, fileLen);
        String ifNoneMatch = headers.get("if-none-match");
        if (ifNoneMatch != null && (ifNoneMatch.equals("*") || ifNoneMatch.equals(etag))) {
            return newFixedLengthResponse(Status.NOT_MODIFIED, mime, "");
        }
        HttpRange range = HttpRange.from(fileLen, headers, etag);
        if (!range.valid()) return createRangeNotSatisfiableResponse(fileLen);
        FileInputStream fis = new FileInputStream(file);
        skip(fis, range.start);
        Response res;
        if (range.isPartial(fileLen)) {
            res = newFixedLengthResponse(Status.PARTIAL_CONTENT, mime, fis, range.length);
            res.addHeader("Content-Range", "bytes " + range.start + "-" + range.end + "/" + fileLen);
        } else {
            res = newFixedLengthResponse(Status.OK, mime, fis, range.length);
        }
        res.addHeader("Content-Length", String.valueOf(range.length));
        res.addHeader("Accept-Ranges", "bytes");
        res.addHeader("ETag", etag);
        return res;
    }

    private String etag(File file, long fileLen) {
        CRC32 crc = new CRC32();
        crc.update((file.getAbsolutePath() + file.lastModified() + fileLen).getBytes());
        return Long.toHexString(crc.getValue());
    }

    private Response createRangeNotSatisfiableResponse(long fileLen) {
        Response res = newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "");
        res.addHeader("Content-Range", "bytes */" + fileLen);
        return res;
    }

    private void skip(InputStream is, long bytesToSkip) throws IOException {
        if (bytesToSkip <= 0) return;
        long remaining = bytesToSkip;
        while (remaining > 0) {
            long skipped = is.skip(remaining);
            if (skipped <= 0) throw new IOException("Failed to skip desired number of bytes");
            remaining -= skipped;
        }
    }

    private static String relativeTo(File file, String rootPath) {
        String path = file.getAbsolutePath();
        return path.startsWith(rootPath) ? path.substring(rootPath.length()) : path;
    }

    private static String parentOf(File dir, File rootDir, String rootPath) {
        if (dir.equals(rootDir)) return ".";
        File parent = dir.getParentFile();
        if (parent == null || parent.equals(rootDir)) return "";
        return relativeTo(parent, rootPath);
    }

    private record HttpRange(long start, long end, long length, boolean valid) {

        public boolean isPartial(long total) {
            return length < total;
        }

        public static HttpRange invalid() {
            return new HttpRange(0, 0, 0, false);
        }

        public static HttpRange from(long fileLen, Map<String, String> headers, String etag) {
            long start = 0;
            long end = fileLen - 1;
            String rangeHeader = headers.get("range");
            String ifRange = headers.get("if-range");
            if (ifRange != null && !ifRange.equals(etag)) rangeHeader = null;
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                try {
                    String[] parts = rangeHeader.substring(6).split("-", 2);
                    if (!parts[0].isEmpty()) start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
                    if (start >= fileLen || start > end) return invalid();
                } catch (NumberFormatException e) {
                    return invalid();
                }
            }
            if (end >= fileLen) end = fileLen - 1;
            return new HttpRange(start, end, end - start + 1, true);
        }
    }
}
