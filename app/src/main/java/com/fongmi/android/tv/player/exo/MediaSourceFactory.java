package com.fongmi.android.tv.player.exo;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.TsExtractor;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.cache.LeasedCache;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.utils.FileUtil;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.Map;

import okhttp3.Call;

public class MediaSourceFactory implements MediaSource.Factory {

    private static final int CACHE_SPACE_PERCENT = 80;

    private static StandaloneDatabaseProvider databaseProvider;
    private static Cache cache;
    private static SimpleCache cacheOwner;

    private final DefaultMediaSourceFactory defaultMediaSourceFactory;
    private HttpDataSource.Factory httpDataSourceFactory;
    private DataSource.Factory dataSourceFactory;
    private ExtractorsFactory extractorsFactory;
    private final com.fongmi.android.tv.player.subtitle.AdvancedSubtitleController subtitles;

    public MediaSourceFactory() {
        this(null);
    }

    public MediaSourceFactory(com.fongmi.android.tv.player.subtitle.AdvancedSubtitleController subtitles) {
        this.subtitles = subtitles;
        defaultMediaSourceFactory = new DefaultMediaSourceFactory(getDataSourceFactory(), getExtractorsFactory());
    }

    static DataSource.Factory createUpstreamDataSourceFactory(Map<String, String> headers, Call.Factory callFactory) {
        HttpDataSource.Factory factory = new OkHttpDataSource.Factory(callFactory);
        factory.setDefaultRequestProperties(headers);
        return new DefaultDataSource.Factory(App.get(), factory);
    }

    static synchronized Cache getCache() {
        if (cache != null) return cache;
        File dir = Path.exoCache();
        cacheOwner = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(getMaxCacheSize(dir)), getDatabaseProvider());
        return cache = new LeasedCache(cacheOwner, dir);
    }

    /** Called only by CacheManager after its shared playback/download ownership gate. */
    public static synchronized void clearCacheResources() throws Cache.CacheException {
        getCache();
        Cache owner = cacheOwner;
        for (String key : new java.util.ArrayList<>(owner.getKeys())) {
            if (Thread.currentThread().isInterrupted()) return;
            owner.removeResource(key);
        }
    }

    private static StandaloneDatabaseProvider getDatabaseProvider() {
        if (databaseProvider == null) databaseProvider = new StandaloneDatabaseProvider(App.get());
        return databaseProvider;
    }

    private static long getMaxCacheSize(File dir) {
        long usedBytes = FileUtil.getDirectorySize(dir);
        long availableBytes = Math.max(0, FileUtil.getAvailableStorageSpace(dir));
        long storageBudget = (usedBytes + availableBytes) * CACHE_SPACE_PERCENT / 100;
        return Math.min(PreloadSetting.getPreloadSizeBytes(), storageBudget);
    }

    @NonNull
    @Override
    public MediaSource.Factory setDrmSessionManagerProvider(@NonNull DrmSessionManagerProvider drmSessionManagerProvider) {
        return this;
    }

    @NonNull
    @Override
    public MediaSource.Factory setLoadErrorHandlingPolicy(@NonNull LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
        return this;
    }

    @NonNull
    @Override
    public @C.ContentType int[] getSupportedTypes() {
        return defaultMediaSourceFactory.getSupportedTypes();
    }

    @NonNull
    @Override
    public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
        return createMediaSource(mediaItem, OkHttp.player());
    }

    MediaSource createMediaSource(@NonNull MediaItem mediaItem, Call.Factory callFactory) {
        // Every item owns an immutable header snapshot. Preparing the next item must not
        // change the cookies/authorization used by current playback or its retries.
        DataSource.Factory upstream = createUpstreamDataSourceFactory(ExoUtil.extractHeaders(mediaItem), callFactory);
        DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(getCacheDataSource(upstream), subtitles == null ? getExtractorsFactory() : subtitles.extractors(getExtractorsFactory(), mediaItem));
        if (subtitles != null) factory.setSubtitleParserFactory(subtitles.parserFactory());
        if (subtitles == null) return factory.createMediaSource(mediaItem);
        java.util.List<MediaItem.SubtitleConfiguration> normal = new java.util.ArrayList<>();
        java.util.List<MediaSource> sources = new java.util.ArrayList<>();
        for (MediaItem.SubtitleConfiguration sub : mediaItem.localConfiguration.subtitleConfigurations) {
            androidx.media3.common.Format format = new androidx.media3.common.Format.Builder()
                    .setId(sub.id).setSampleMimeType(sub.mimeType).setLanguage(sub.language)
                    .setLabel(sub.label).setSelectionFlags(sub.selectionFlags).setRoleFlags(sub.roleFlags)
                    .setMetadata(com.fongmi.android.tv.player.subtitle.LocalSubtitleOrigin.forUrl(sub.uri.toString())).build();
            boolean sup = androidx.media3.common.MimeTypes.APPLICATION_PGS.equals(sub.mimeType);
            boolean nativeAss = subtitles.usesNativeAss() && androidx.media3.common.MimeTypes.TEXT_SSA.equals(sub.mimeType);
            androidx.media3.extractor.text.SubtitleParser.Factory parsers = subtitles.parserFactory();
            if (!nativeAss && !parsers.supportsFormat(format)) { normal.add(sub); continue; }
            // Keep the extractor's own SeekMap. The default lazy sidecar path passes a null
            // format, so it cannot publish the final cue END time. Its estimated duration
            // then ends at the last cue START and seeking into that last cue produces EOS.
            sources.add(new androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(upstream,
                    () -> new androidx.media3.extractor.Extractor[]{nativeAss
                            ? new com.fongmi.android.tv.player.subtitle.ExternalAssExtractor(format)
                            : sup ? new com.fongmi.android.tv.player.subtitle.SupExtractor(format)
                            : new androidx.media3.extractor.text.SubtitleExtractor(parsers.create(format), format)})
                    .createMediaSource(MediaItem.fromUri(sub.uri)));
        }
        if (sources.isEmpty()) return factory.createMediaSource(mediaItem);
        sources.add(0, factory.createMediaSource(mediaItem.buildUpon().setSubtitleConfigurations(normal).build()));
        return new androidx.media3.exoplayer.source.MergingMediaSource(sources.toArray(new MediaSource[0]));
    }

    private ExtractorsFactory getExtractorsFactory() {
        if (extractorsFactory == null) {
            DefaultExtractorsFactory factory = new DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 10);
            if (subtitles != null) {
                factory.setSubtitleParserFactory(subtitles.parserFactory());
                extractorsFactory = factory;
            } else extractorsFactory = factory;
        }
        return extractorsFactory;
    }

    private DataSource.Factory getDataSourceFactory() {
        if (dataSourceFactory == null) dataSourceFactory = () -> getCacheDataSource(new DefaultDataSource.Factory(App.get(), getHttpDataSourceFactory())).createDataSource();
        return dataSourceFactory;
    }

    private CacheDataSource.Factory getCacheDataSource(DataSource.Factory upstreamFactory) {
        return new CacheDataSource.Factory().setCache(getCache()).setUpstreamDataSourceFactory(upstreamFactory).setCacheWriteDataSinkFactory(null).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    private HttpDataSource.Factory getHttpDataSourceFactory() {
        if (httpDataSourceFactory == null) httpDataSourceFactory = new OkHttpDataSource.Factory(OkHttp.player());
        return httpDataSourceFactory;
    }
}
