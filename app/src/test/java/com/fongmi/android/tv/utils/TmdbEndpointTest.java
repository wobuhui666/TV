package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.setting.Setting;

import org.junit.Test;

import okhttp3.HttpUrl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TmdbEndpointTest {

    @Test
    public void shouldKeepDefaultEndpoint() {
        assertEquals("https://tapi.coolmarket.eu.org/", TmdbEndpoint.DEFAULT_ROOT);
        assertEquals("https://tapi.coolmarket.eu.org/3/", TmdbEndpoint.DEFAULT_API_BASE);
        assertEquals("https://tapi.coolmarket.eu.org/t/p/", TmdbEndpoint.DEFAULT_IMAGE_BASE);
    }

    @Test
    public void shouldNormalizeProxyRoot() {
        assertEquals("https://example.com/", TmdbEndpoint.normalizeRoot("example.com"));
        assertEquals("http://example.com/base/", TmdbEndpoint.normalizeRoot("http://example.com/base"));
        assertEquals("https://example.com/", TmdbEndpoint.normalizeRoot("https://example.com/3/"));
        assertEquals("https://example.com/base/", TmdbEndpoint.normalizeRoot("https://example.com/base/t/p/"));
    }

    @Test
    public void shouldIgnoreEmptyOrInvalidProxyRoot() {
        assertEquals("", TmdbEndpoint.normalizeRoot(""));
        assertEquals("", TmdbEndpoint.normalizeRoot("ftp://example.com/"));
        assertEquals("", TmdbEndpoint.normalizeRoot("https:///missing-host"));
    }

    @Test
    public void shouldUseDistinctOfficialHostsInDirectMode() {
        String storedProxy = "https://saved-proxy.example/base/";
        assertEquals("https://api.themoviedb.org/3/", TmdbEndpoint.getApiBase(Setting.TMDB_DIRECT, storedProxy));
        assertEquals("https://image.tmdb.org/t/p/", TmdbEndpoint.getImageBase(Setting.TMDB_DIRECT, storedProxy));
        assertEquals(storedProxy + "3/", TmdbEndpoint.getApiBase(Setting.TMDB_CUSTOM_PROXY, storedProxy));
        assertEquals(storedProxy + "t/p/", TmdbEndpoint.getImageBase(Setting.TMDB_CUSTOM_PROXY, storedProxy));
    }

    @Test
    public void shouldIgnoreSavedCustomRootWhenDefaultProxyIsSelected() {
        assertEquals(TmdbEndpoint.DEFAULT_API_BASE, TmdbEndpoint.getApiBase(Setting.TMDB_DEFAULT_PROXY, "https://custom.example/"));
        assertEquals(TmdbEndpoint.DEFAULT_IMAGE_BASE, TmdbEndpoint.getImageBase(Setting.TMDB_DEFAULT_PROXY, "https://custom.example/"));
        assertEquals(TmdbEndpoint.DEFAULT_API_BASE, TmdbEndpoint.getApiBase(Setting.TMDB_CUSTOM_PROXY, "ftp://invalid.example/"));
    }

    @Test
    public void shouldRecognizeOnlyTheSelectedTmdbPathsAndHosts() {
        assertTrue(TmdbNetwork.isTmdbUrl(HttpUrl.get("https://api.themoviedb.org/3/trending/all/day"), Setting.TMDB_DIRECT, ""));
        assertTrue(TmdbNetwork.isTmdbUrl(HttpUrl.get("https://image.tmdb.org/t/p/w500/poster.jpg"), Setting.TMDB_DIRECT, ""));
        assertFalse(TmdbNetwork.isTmdbUrl(HttpUrl.get("https://api.themoviedb.org/t/p/poster.jpg"), Setting.TMDB_DIRECT, ""));
        assertFalse(TmdbNetwork.isTmdbUrl(HttpUrl.get("https://image.tmdb.org/3/movie/1"), Setting.TMDB_DIRECT, ""));
        assertFalse(TmdbNetwork.isTmdbUrl(HttpUrl.get("https://api.themoviedb.org:8443/3/movie/1"), Setting.TMDB_DIRECT, ""));
        assertFalse(TmdbNetwork.isTmdbUrl(HttpUrl.get("http://api.themoviedb.org/3/movie/1"), Setting.TMDB_DIRECT, ""));
        assertFalse(TmdbNetwork.isTmdbUrl(HttpUrl.get("https://api.themoviedb.org.example/3/movie/1"), Setting.TMDB_DIRECT, ""));
        assertFalse(TmdbNetwork.isTmdbUrl(HttpUrl.get(TmdbEndpoint.DEFAULT_API_BASE + "movie/1"), Setting.TMDB_DIRECT, ""));
        assertTrue(TmdbNetwork.isTmdbUrl(HttpUrl.get("https://proxy.example/base/3/movie/1"), Setting.TMDB_CUSTOM_PROXY, "https://proxy.example/base/"));
        assertTrue(TmdbNetwork.isTmdbUrl(HttpUrl.get("https://proxy.example/base/t/p/w500/poster.jpg"), Setting.TMDB_CUSTOM_PROXY, "https://proxy.example/base/"));
        assertFalse(TmdbNetwork.isTmdbUrl(HttpUrl.get("https://proxy.example/other/3/movie/1"), Setting.TMDB_CUSTOM_PROXY, "https://proxy.example/base/"));
    }
}
