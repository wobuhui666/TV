package com.fongmi.android.tv.test

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fongmi.android.tv.api.SiteApi
import com.fongmi.android.tv.api.config.VodConfig
import com.fongmi.android.tv.api.loader.BaseLoader
import com.fongmi.android.tv.bean.Config
import com.fongmi.android.tv.bean.Result
import com.fongmi.android.tv.bean.Site
import com.fongmi.android.tv.bean.Vod
import com.fongmi.android.tv.impl.Callback
import com.fongmi.android.tv.player.media.MediaUrlGuard
import com.fongmi.android.tv.setting.BrowseExperienceSettings
import com.fongmi.android.tv.source.EpisodeTarget
import com.fongmi.android.tv.source.LibrarySourceMatcher
import com.fongmi.android.tv.source.SmartSourceSelector
import com.fongmi.android.tv.utils.SearchResultFilter
import com.github.catvod.crawler.SpiderNull
import com.github.catvod.net.OkHttp
import com.github.catvod.utils.CallTimeout
import com.github.catvod.utils.Prefers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in public-source sampling in a separate package; never exports URLs, headers or credentials. */
@RunWith(AndroidJUnit4::class)
class LibrarySourceCompatibilityAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val timeout = arguments.getString("source_timeout_ms", "15000")!!.toLong().coerceIn(1000, 30000)
    private val title = arguments.getString("source_title", "庆余年")!!
    private val year = arguments.getString("source_year", "2019")!!
    private val movie = arguments.getString("source_movie", "false").toBoolean()
    private lateinit var output: File

    @Test
    fun compareEachRequestedSourceWithClassicAndLibrarySelection() {
        assertEquals("A separate disposable package is required", "com.fongmi.android.tv.sourceprobe", context.packageName)
        assertEquals(CONFIG_URL, arguments.getString("source_config_url"))
        val indices = arguments.getString("source_indices", "22")!!.split(',').map(String::toInt).distinct()
        assertTrue("Use batches of at most eight, with no requests queued behind a global deadline", indices.size in 1..8)
        val label = arguments.getString("source_run", "sample")!!.replace(Regex("[^A-Za-z0-9_-]"), "_")
        output = File(context.getExternalFilesDir("library-source-compatibility"), "$label.json")
        output.parentFile!!.mkdirs()
        val report = JSONObject().put("schema", "tv.library-source-compatibility.v1")
            .put("configUrl", CONFIG_URL).put("timestampMs", System.currentTimeMillis())
            .put("deviceAbis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("sdk", Build.VERSION.SDK_INT)
            .put("timeoutPerCallMs", timeout).put("requestedIndices", JSONArray(indices))
            .put("requestedTitle", title).put("requestedYear", year).put("requestedMovie", movie)
            .put("networkFixtures", false).put("cookieSubmission", false)
            .put("decodedPlaybackVerified", false).put("sourceSamples", JSONArray())
        val configExecutor = Executors.newSingleThreadExecutor()
        try {
            Prefers.put("incognito", true)
            Prefers.put("debug", false)
            val signal = CountDownLatch(1)
            val loaded = AtomicBoolean()
            val began = SystemClock.elapsedRealtime()
            configExecutor.submit {
                VodConfig.load(Config.find(CONFIG_URL, 0), object : Callback {
                    override fun success() { loaded.set(true); signal.countDown() }
                    override fun error(message: String?) { signal.countDown() }
                })
            }
            val completed = signal.await(90, TimeUnit.SECONDS)
            report.put("configStatus", if (!completed) "timeout" else if (loaded.get()) "loaded" else "failed")
                .put("configElapsedMs", SystemClock.elapsedRealtime() - began)
            save(report)
            assertTrue("The live configuration must load before sampling", loaded.get())
            val sites = VodConfig.get().sites
            report.put("configuredSourceCount", sites.size)
            val callers = Executors.newFixedThreadPool(minOf(4, indices.size))
            try {
                val jobs = indices.map { index -> callers.submit(Callable { sample(sites[index], index) }) }
                jobs.forEach { job ->
                    report.getJSONArray("sourceSamples").put(job.get())
                    save(report)
                }
            } finally { callers.shutdownNow() }
            report.put("completed", true)
        } finally {
            configExecutor.shutdownNow()
            // This process and its loaders belong only to the disposable sourceprobe app.
            BaseLoader.get().clear()
            save(report)
        }
    }

    private fun sample(site: Site, index: Int): JSONObject {
        val result = JSONObject().put("index", index).put("key", site.key).put("name", site.name)
            .put("searchable", site.isSearchable).put("type", site.type)
        val keyword = keyword(site)
        result.put("keyword", keyword)
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "source-sample-$index").apply { isDaemon = true } }
        var blocked = false
        fun <T> phase(name: String, body: () -> T): T? {
            val stage = JSONObject()
            result.put(name, stage)
            if (blocked) { stage.put("status", "skipped-after-unresponsive-call"); return null }
            val started = SystemClock.elapsedRealtime()
            val denied = AtomicBoolean()
            var future: Future<T>? = null
            try {
                future = worker.submit(Callable {
                    CallTimeout(timeout, false).use { deadline ->
                        val caller = Thread.currentThread()
                        deadline.start { caller.interrupt() }
                        val restore = deadline.enter()
                        try { body() } finally {
                            denied.set(CallTimeout.currentDeniedDialog())
                            restore.run()
                        }
                    }
                })
                val value = future.get(timeout + 1500, TimeUnit.MILLISECONDS)
                stage.put("status", "returned")
                return value
            } catch (failure: Exception) {
                future?.cancel(true)
                stage.put("status", if (denied.get()) "interaction-required" else if (failure is TimeoutException) "timeout" else "failed")
                    .put("failureTypes", exceptionTypes(failure))
                if (failure is TimeoutException) {
                    val drain = worker.submit { }
                    try { drain.get(1000, TimeUnit.MILLISECONDS) } catch (_: Exception) { blocked = true; drain.cancel(true) }
                }
                return null
            } finally {
                stage.put("elapsedMs", SystemClock.elapsedRealtime() - started).put("dialogRequired", denied.get())
            }
        }
        try {
            val spider = phase("plugin") { site.spider() }
            if (spider == null || spider is SpiderNull) {
                result.put("availability", "plugin-not-loaded")
                return result
            }
            result.getJSONObject("plugin").put("initialized", true).put("spiderClass", spider.javaClass.name)
            var home: Result? = null
            var classic: Result? = null
            var raw: Result? = null
            if (site.isSearchable) {
                raw = phase("libraryRawSearch") { SiteApi.searchRawContent(site, keyword, false, "1") }
                raw?.let { summarizeResult(result.getJSONObject("libraryRawSearch"), it) }
                // SiteApi.searchContent delegates to this exact filter. Sharing one response
                // isolates the UI-path difference from changes between two live searches.
                result.put("classicSearch", JSONObject().put("status", if (raw == null) "raw-search-did-not-return" else "same-response-filtered")
                    .put("sharedNetworkResponseWithLibrary", true))
                if (raw != null) {
                    classic = SearchResultFilter.apply(Result.list(raw.list.toMutableList()), keyword, BrowseExperienceSettings.getSearchFilterMode())
                    summarizeResult(result.getJSONObject("classicSearch"), classic)
                }
            } else {
                result.put("classicSearch", JSONObject().put("status", "disabled-in-config"))
                result.put("libraryRawSearch", JSONObject().put("status", "disabled-in-config"))
            }
            if (raw?.list.orEmpty().isEmpty()) {
                home = phase("home") { SiteApi.homeContent(site) }
                home?.let { summarizeResult(result.getJSONObject("home"), it) }
            } else result.put("home", JSONObject().put("status", "search-content-sufficient"))
            val candidates = raw?.list.orEmpty().ifEmpty { classic?.list.orEmpty() }.ifEmpty { home?.list.orEmpty() }
            var candidate = candidates.filter { !it.isFolder && !it.isAction && it.id.isNotEmpty() }
                .maxByOrNull { LibrarySourceMatcher.match(it, title, emptyList(), year, movie, if (movie) null else 1).score() }
            val sampledHome = home
            if (candidate == null && sampledHome != null && sampledHome.types.isNotEmpty()) {
                val category = phase("firstCategory") {
                    SiteApi.categoryContent(site.key, sampledHome.types.first().typeId, "1", false, hashMapOf())
                }
                category?.let {
                    summarizeResult(result.getJSONObject("firstCategory"), it)
                    candidate = it.list.firstOrNull { vod -> !vod.isFolder && !vod.isAction && vod.id.isNotEmpty() }
                }
            }
            val selected = candidate
            if (selected == null) {
                result.put("detail", JSONObject().put("status", "no-playable-sample"))
                result.put("player", JSONObject().put("status", "no-playable-sample"))
                result.put("availability", if (candidates.any { it.isFolder || it.isAction }) "catalog-or-action-returned"
                    else if (home?.types.orEmpty().isNotEmpty()) "categories-returned" else "no-content-in-sample")
                return result
            }
            selected.site = site
            result.put("searchCandidate", metadata(selected))
            val detail = phase("detail") { SiteApi.detailContent(site.key, selected.id) }
            detail?.let { summarizeResult(result.getJSONObject("detail"), it) }
            val vod = detail?.list?.firstOrNull()
            if (vod == null) {
                result.put("player", JSONObject().put("status", "no-detail"))
                result.put("availability", "search-or-home-content-returned")
                return result
            }
            vod.site = site
            result.put("detailCandidate", metadata(vod))
            val selection = vod.flags.firstNotNullOfOrNull { flag ->
                SmartSourceSelector.findEpisode(flag, EpisodeTarget.coordinate(1, 1), SmartSourceSelector.implicitEpisodeSeason(vod))
                    ?.let { episode -> flag to episode }
            }
            result.put("libraryS1E1Selectable", selection != null)
            result.put("libraryMovieSelectable", SmartSourceSelector.selectMovie(vod, "", "") != null)
            val first = vod.flags.firstOrNull { it.episodes.isNotEmpty() }?.let { it to it.episodes.first() }
            val playback = selection ?: first
            if (playback == null) {
                result.put("player", JSONObject().put("status", "no-episodes-in-detail"))
                result.put("availability", "detail-without-episodes")
                return result
            }
            result.put("playerSampleLabel", playback.second.name)
            if (site.key in setOf("WexAiGuaZi", "WexAiYiYs", "WexAiReBo", "WexAiIkanBot")) {
                val repeated = phase("repeatedDetail") { SiteApi.detailContent(site.key, selected.id) }
                val replacement = repeated?.list?.firstOrNull()
                if (replacement != null) {
                    val repeatedStage = result.getJSONObject("repeatedDetail")
                    repeatedStage.put("originalUrlSelectable", SmartSourceSelector.selectExplicitEpisode(replacement, playback.second.url, playback.first.flag) != null)
                    val same = replacement.flags.firstOrNull { it.flag == playback.first.flag }
                        ?.episodes?.firstOrNull { it.name == playback.second.name }
                    val replacementFlag = replacement.flags.firstOrNull { it.flag == playback.first.flag }
                    repeatedStage.put("originalEpisodeTargetSelectable", replacementFlag != null && SmartSourceSelector.findEpisode(
                        replacementFlag, EpisodeTarget.of(playback.second, SmartSourceSelector.implicitEpisodeSeason(vod)),
                        SmartSourceSelector.implicitEpisodeSeason(replacement)) != null)
                    val librarySelector = SmartSourceSelector::class.java.methods.firstOrNull { it.name == "selectLibraryEpisode" }
                    repeatedStage.put("updatedLibrarySelectorPresent", librarySelector != null)
                    if (librarySelector != null) repeatedStage.put("originalLibrarySelectionSelectable", librarySelector.invoke(
                        null, replacement, playback.second.url, playback.second.name, playback.first.flag) != null)
                    repeatedStage.put("sameEpisodeLabelFound", same != null)
                        .put("sameEpisodeUrlChanged", same != null && same.url != playback.second.url)
                        .put("replacementCandidate", metadata(replacement))
                    result.put("repeatedEpisodeSelection", if (same == null) "label-not-found" else if (same.url == playback.second.url) "url-stable" else "url-changed")
                }
            }
            val player = phase("player") { SiteApi.preloadContent(site.key, playback.first.flag, playback.second.url) }
            if (player != null) {
                val url = player.url.v()
                result.getJSONObject("player").put("responseOnly", true).put("urlReturned", url.isNotBlank())
                    .put("parse", player.parse).put("jx", player.jx).put("messagePresent", player.msg.isNotEmpty())
                    .put("format", safe(player.format)).put("mediaGuardRejected", MediaUrlGuard.shouldReject(url, player.format))
                if (url.startsWith("http") && !player.needParse() && !MediaUrlGuard.shouldReject(url, player.format)) {
                    phase("mediaResponse") {
                        val headers = player.header.toMutableMap().apply { put("Range", "bytes=0-1023") }
                        OkHttp.newCall(url, headers).execute().use { response ->
                            val stage = result.getJSONObject("mediaResponse")
                            val bytes = response.body?.byteStream()?.readNBytes(1024) ?: byteArrayOf()
                            stage.put("httpStatus", response.code).put("contentType", safe(response.header("Content-Type", "")))
                                .put("sampledBytes", bytes.size).put("hlsHeader", bytes.take(16).toByteArray().toString(Charsets.UTF_8).contains("#EXTM3U"))
                                .put("mp4Signature", bytes.size >= 8 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp")
                                .put("mpegTsSyncByte", bytes.firstOrNull()?.toInt()?.and(255) == 0x47)
                        }
                    }
                } else result.put("mediaResponse", JSONObject().put("status", "resolver-or-player-required"))
            }
            result.put("availability", if (player?.url?.v().isNullOrBlank()) "detail-returned" else "player-url-returned")
            return result
        } catch (failure: Exception) {
            result.put("driverFailureTypes", exceptionTypes(failure))
            return result
        } finally { worker.shutdownNow() }
    }

    private fun summarizeResult(stage: JSONObject, result: Result) {
        stage.put("items", result.list.size).put("categories", result.types.size)
            .put("folders", result.list.count { it.isFolder }).put("actions", result.list.count { it.isAction })
            .put("messagePresent", result.msg.isNotEmpty())
            .put("sampleMetadata", JSONArray(result.list.take(3).map(::metadata)))
    }

    private fun metadata(vod: Vod): JSONObject {
        val match = LibrarySourceMatcher.match(vod, title, emptyList(), year, movie, if (movie) null else 1)
        return JSONObject().put("title", safe(vod.name)).put("year", safe(vod.year)).put("typeName", safe(vod.typeName))
            .put("folder", vod.isFolder).put("action", vod.isAction).put("flags", vod.flags.size)
            .put("episodeCount", vod.flags.sumOf { it.episodes.size })
            .put("firstLabels", JSONArray(vod.flags.flatMap { it.episodes }.take(3).map { safe(it.name) }))
            .put("generatedLabels", vod.flags.flatMap { it.episodes }.take(3).count { it.isGeneratedLabel })
            .put("libraryMatchesRequested", match.matches()).put("libraryAutomaticRequested", match.automatic())
            .put("libraryScoreRequested", match.score())
    }

    private fun keyword(site: Site): String = arguments.getString("source_keyword") ?: when {
        site.key.startsWith("Anime") -> "海贼王"
        site.key.startsWith("Sport") -> "足球"
        site.key.startsWith("Music") -> "周杰伦"
        site.key.startsWith("Children") -> "宝宝"
        site.key.startsWith("Book") -> "三体"
        site.key.startsWith("ManJu") || site.key.startsWith("DuanJu") -> "逆袭"
        else -> title
    }

    private fun safe(value: String?): String = value.orEmpty().replace(Regex("https?://\\S+"), "[url]").take(160)
    private fun exceptionTypes(problem: Throwable): JSONArray {
        val types = mutableListOf<String>()
        var current: Throwable? = problem
        while (current != null && types.size < 8) { types += current.javaClass.simpleName; current = current.cause }
        return JSONArray(types)
    }
    private fun save(report: JSONObject) { output.writeText(report.toString(2), Charsets.UTF_8) }

    companion object { private const val CONFIG_URL = "https://9280.kstore.vip/aiwex.json" }
}
