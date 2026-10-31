package com.fongmi.android.tv.player.extractor;

import android.net.Uri;

import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.io.File;

import okhttp3.Response;
import okhttp3.ResponseBody;

public class Strm implements Source.Extractor {

    private static final long MAX_STRM_BYTES = 64 * 1024;

    @Override
    public boolean match(Uri uri) {
        return UrlUtil.path(uri).endsWith(".strm");
    }

    @Override
    public String fetch(String url) throws Exception {
        if (url.startsWith("http")) return http(url);
        if (url.startsWith("file")) url = url.substring(7);
        return Path.read(new File(url)).split("\\R", 2)[0];
    }

    private String http(String url) throws Exception {
        try (Response res = OkHttp.newCall(OkHttp.noRedirect(), url).execute()) {
            if (!res.isSuccessful()) return url;
            try (ResponseBody body = res.peekBody(MAX_STRM_BYTES)) {
                // A full probe may be a truncated video, playlist or unusually large pointer.
                if (body.contentLength() >= MAX_STRM_BYTES) return url;
                return parseTarget(body.string(), url);
            }
        }
    }

    static String parseTarget(String content, String originalUrl) {
        if (content.startsWith("\uFEFF")) content = content.substring(1);
        String line = content.split("\\R", 2)[0].trim();
        return line.matches("[a-zA-Z][a-zA-Z0-9+.-]*:\\S+") ? line : originalUrl;
    }

    @Override
    public void stop() {
    }

    @Override
    public void exit() {
    }
}
