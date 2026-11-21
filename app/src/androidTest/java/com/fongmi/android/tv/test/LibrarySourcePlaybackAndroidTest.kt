package com.fongmi.android.tv.test

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fongmi.android.tv.api.SiteApi
import com.fongmi.android.tv.api.config.VodConfig
import com.fongmi.android.tv.bean.Config
import com.fongmi.android.tv.bean.Episode
import com.fongmi.android.tv.bean.Flag
import com.fongmi.android.tv.bean.History
import com.fongmi.android.tv.bean.Vod
import com.fongmi.android.tv.impl.Callback
import com.fongmi.android.tv.playback.vod.LibraryPlaybackPrefetch
import com.fongmi.android.tv.playback.vod.VodPlayRequest
import com.fongmi.android.tv.player.PlayerManager
import com.fongmi.android.tv.setting.ExperienceMode
import com.fongmi.android.tv.setting.ExperienceSettings
import com.fongmi.android.tv.source.EpisodeTarget
import com.fongmi.android.tv.source.LibrarySourceMatcher
import com.fongmi.android.tv.source.SmartSourceSelector
import com.fongmi.android.tv.ui.activity.HomeActivity
import com.fongmi.android.tv.ui.activity.PlaybackActivity
import com.fongmi.android.tv.ui.activity.VideoActivity
import com.github.catvod.utils.CallTimeout
import com.github.catvod.utils.Prefers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real public-source playback through the production library Intent and VideoActivity. */
@RunWith(AndroidJUnit4::class)
class LibrarySourcePlaybackAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val workers = Executors.newSingleThreadExecutor()

    @Test
    fun requestedGuaziLibrarySelectionPlaysAndPreservesItsPreparedPlaylist() {
        assertEquals("Only the separate validation app", "com.fongmi.android.tv.sourceprobe", context.packageName)
        assumeTrue("Explicit opt-in for real decoded playback", arguments.getString("source_playback") == "true")
        assertEquals(CONFIG_URL, arguments.getString("source_config_url"))
        val title = arguments.getString("source_title", "庆余年")!!
        val movie = arguments.getString("source_movie", "false").toBoolean()
        val requirePlayback = arguments.getString("source_expect_playback", "false").toBoolean()
        val seekMs = arguments.getString("source_seek_ms", "0")!!.toLongOrNull()?.coerceAtLeast(0) ?: 0
        val observeMs = arguments.getString("source_observe_ms", "5000")!!.toLongOrNull()?.coerceIn(1, 60_000) ?: 5000
        val timedObservation = seekMs > 0 || arguments.containsKey("source_observe_ms")
        val run = arguments.getString("source_run", "playback")!!.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val directory = context.getExternalFilesDir("library-source-playback")!!
        directory.mkdirs()
        val report = JSONObject().put("schema", "tv.library-source-playback.v1")
            .put("configUrl", CONFIG_URL).put("title", title).put("movie", movie)
            .put("requestedSeekMs", seekMs).put("requestedObservationMs", observeMs).put("seekApplied", false)
            .put("networkFixtures", false).put("cookieSubmission", false).put("timestampMs", System.currentTimeMillis())
        val observed = JSONArray()
        report.put("playbackSamples", observed)
        var home: Activity? = null
        var video: VideoActivity? = null
        var manager: PlayerManager? = null
        val firstFrame = AtomicBoolean()
        val listener = object : Player.Listener { override fun onRenderedFirstFrame() { firstFrame.set(true) } }
        try {
            Prefers.put("incognito", true)
            Prefers.put("debug", false)
            val configReady = CountDownLatch(1)
            val loaded = AtomicBoolean()
            workers.submit {
                VodConfig.load(Config.find(CONFIG_URL, 0), object : Callback() {
                    override fun success() { loaded.set(true); configReady.countDown() }
                    override fun error(message: String?) { configReady.countDown() }
                })
            }
            assertTrue("Load the actual source configuration", configReady.await(90, TimeUnit.SECONDS) && loaded.get())
            val site = VodConfig.get().getSite("WexAiGuaZi")
            val search = call { SiteApi.searchRawContent(site, title, false, "1") }
            report.put("sourceSearchItems", search.list.size)
            val candidate = search.list.filter { !it.isFolder && !it.isAction }
                .maxByOrNull { LibrarySourceMatcher.match(it, title, emptyList(), "2019", movie, if (movie) null else 1).score() }
                ?: throw AssertionError("The requested source returned no media sample")
            val detail = call { SiteApi.detailContent(site.key, candidate.id) }.vod
            detail.site = site
            detail.id = candidate.id
            val match = LibrarySourceMatcher.match(detail, title, emptyList(), "2019", movie, if (movie) null else 1)
            report.put("detailTitle", detail.name).put("detailTypeName", detail.typeName).put("detailYear", detail.year)
                .put("libraryMatch", match.matches()).put("libraryAutomatic", match.automatic())
            val selection = if (movie) SmartSourceSelector.selectMovie(detail, "", "") else
                detail.flags.firstNotNullOfOrNull { flag ->
                    SmartSourceSelector.findEpisode(flag, EpisodeTarget.coordinate(1, 1), SmartSourceSelector.implicitEpisodeSeason(detail))
                        ?.let { SmartSourceSelector.FlagSelection(flag, it, 0, flag.episodes.size) }
                }
            val chosen = selection ?: throw AssertionError("The requested episode could not be selected")
            report.put("selectedEpisodeName", chosen.episode().name).put("selectedFlag", chosen.flag().flag)
            verifyPreparedDetailIsolation(detail, chosen.flag(), chosen.episode(), report)
            instrumentation.runOnMainSync { ExperienceSettings.putMode(ExperienceMode.LIBRARY) }
            home = instrumentation.startActivitySync(Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val homeActivity = home ?: throw AssertionError("The launch Activity was not started")
            val libraryIntent = main {
                // The shared AndroidTest source set also compiles for mobile variants.
                // Resolve this TV entry point without making those variants depend on it.
                val entry = VideoActivity::class.java.getMethod("libraryIntent", Activity::class.java, Vod::class.java,
                    String::class.java, Int::class.javaObjectType, Int::class.javaObjectType, Flag::class.java,
                    Episode::class.java, History::class.java)
                entry.invoke(null, homeActivity, detail, if (movie) "tmdb:movie:535167" else "tmdb:tv:95842",
                    if (movie) null else 1, if (movie) null else 1, chosen.flag(), chosen.episode(), null) as Intent
            }
            report.put("preparedDetailTokenAttached", libraryIntent.hasExtra("libraryDetail"))
            video = instrumentation.startActivitySync(libraryIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VideoActivity
            val started = SystemClock.elapsedRealtime()
            val positionSamples = mutableListOf<Long>()
            var decoded = false
            var seekApplied = false
            var seekReached = false
            var seekAt = 0L
            var seekCounters: Any? = null
            var seekRenderedBaseline = 0
            var observationStarted = 0L
            fun positionAdvance(): Long = if (positionSamples.size >= 2) positionSamples.last() - positionSamples.first() else 0
            fun observationElapsed(): Long = if (observationStarted > 0) SystemClock.elapsedRealtime() - observationStarted else 0
            fun observationComplete(): Boolean = positionSamples.size >= 2 && positionAdvance() >= observeMs
                && (!timedObservation || observationElapsed() >= observeMs)
            while (SystemClock.elapsedRealtime() - started < 60_000) {
                val activity = video ?: throw AssertionError("The playback Activity was not started")
                val sample = main {
                    val snapshot = JSONObject().put("afterMs", SystemClock.elapsedRealtime() - started)
                        .put("finishing", activity.isFinishing).put("destroyed", activity.isDestroyed)
                    if (manager == null && !activity.isDestroyed) {
                        val accessor = PlaybackActivity::class.java.getDeclaredMethod("player").apply { isAccessible = true }
                        try { manager = accessor.invoke(activity) as PlayerManager } catch (_: Exception) { }
                        manager?.player?.addListener(listener)
                    }
                    manager?.let { current ->
                        if (!current.isReleased) {
                            val player = current.player
                            val counters = (player as? ExoPlayer)?.videoDecoderCounters
                            val rendered = counters?.renderedOutputBufferCount ?: 0
                            snapshot.put("state", current.playbackState).put("playing", current.isPlaying)
                                .put("positionMs", current.position).put("durationMs", current.duration)
                                .put("videoWidth", player.videoSize.width).put("videoHeight", player.videoSize.height)
                                .put("renderedOutputBuffers", rendered).put("renderedFirstFrameEvent", firstFrame.get())
                                .put("playerClass", player.javaClass.simpleName)
                            if (player.playerError != null) snapshot.put("errorCode", player.playerError!!.errorCodeName)
                            val frameObserved = rendered > 0 || firstFrame.get()
                            if (seekMs > 0 && !seekApplied && player.playbackState == Player.STATE_READY
                                && frameObserved && current.canSeek()) {
                                seekCounters = counters
                                seekRenderedBaseline = rendered
                                positionSamples.clear()
                                firstFrame.set(false)
                                decoded = false
                                observationStarted = 0L
                                seekAt = SystemClock.elapsedRealtime()
                                seekApplied = true
                                report.put("seekApplied", true).put("seekAppliedAfterMs", seekAt - started)
                                    .put("seekPositionMs", seekMs).put("positionBeforeSeekMs", current.position)
                                current.seekTo(seekMs)
                                snapshot.put("seekRequested", true).put("positionMs", current.position)
                                    .put("renderedFirstFrameEvent", false)
                            } else {
                                if (seekApplied) {
                                    seekReached = current.position >= (seekMs - 1500).coerceAtLeast(0)
                                    val newRenderedFrame = if (counters === seekCounters) rendered > seekRenderedBaseline else rendered > 0
                                    decoded = decoded || seekReached && (firstFrame.get() || newRenderedFrame)
                                } else decoded = decoded || frameObserved
                                if (current.isPlaying && (seekMs == 0L || seekApplied && seekReached && decoded)) {
                                    if (positionSamples.isEmpty()) observationStarted = SystemClock.elapsedRealtime()
                                    positionSamples += current.position
                                }
                            }
                            snapshot.put("afterSeekMs", if (seekApplied) SystemClock.elapsedRealtime() - seekAt else JSONObject.NULL)
                                .put("postSeekFrameConfirmed", seekApplied && decoded)
                                .put("naturalObservationMs", observationElapsed()).put("sampledPositionAdvanceMs", positionAdvance())
                        }
                    }
                    snapshot
                }
                observed.put(sample)
                File(directory, "$run.json").writeText(report.toString(2))
                if (sample.optBoolean("finishing") || sample.optBoolean("destroyed")) break
                if (decoded && (seekMs == 0L || seekApplied && seekReached) && observationComplete()) break
                SystemClock.sleep(1000)
            }
            val advanced = observationComplete()
            val verified = decoded && advanced && (seekMs == 0L || seekApplied && seekReached)
            report.put("decodedVideoVerified", decoded).put("playingPositionAdvancedByAtLeast5Seconds", positionAdvance() >= 5000)
                .put("playingPositionAdvancedByObservationMs", advanced).put("sampledPositionAdvanceMs", positionAdvance())
                .put("naturalObservationMs", observationElapsed()).put("postSeekFrameConfirmed", seekApplied && decoded)
                .put("decodedPlaybackVerified", verified)
            NativeUiEvidence.capture("guazi-library-$run".lowercase().replace(Regex("[^a-z0-9-]"), "-").take(64))
            if (requirePlayback) assertTrue("The fixed library route must decode video and advance playback after the requested seek", verified)
        } catch (failure: Throwable) {
            report.put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally {
            instrumentation.runOnMainSync {
                manager?.let { current -> if (!current.isReleased) { current.player.removeListener(listener); current.stop() } }
                video?.let { if (!it.isDestroyed) it.finish() }
                home?.let { if (!it.isDestroyed) it.finish() }
            }
            workers.shutdownNow()
            File(directory, "$run.json").writeText(report.toString(2))
        }
    }

    private fun verifyPreparedDetailIsolation(source: Vod, flag: Flag, episode: Episode, report: JSONObject) {
        val handoff = LibraryPlaybackPrefetch::class.java.methods.firstOrNull { it.name == "handoffDetail" }
        val consume = LibraryPlaybackPrefetch::class.java.methods.firstOrNull { it.name == "consumeDetail" }
        report.put("detailHandoffMethodPresent", handoff != null && consume != null)
        if (handoff == null || consume == null) return
        val request = VodPlayRequest.create(source.siteKey, flag, episode)
        val token = handoff.invoke(null, "validation-detail", source, flag, episode) as String
        val transferred = consume.invoke(null, token, "validation-detail", VodConfig.getCid(), source.id, request) as Vod?
        assertTrue("The selected actual source detail must transfer", transferred != null)
        val isolated = transferred!!.flags !== source.flags && transferred.flags.first() !== source.flags.first()
            && transferred.flags.first().episodes !== source.flags.first().episodes
            && transferred.flags.first().episodes.first() !== source.flags.first().episodes.first()
        report.put("handoffFlagsAndEpisodesIndependent", isolated)
            .put("handoffPreservedExactEpisodeUrl", transferred.flags.any { line -> line.flag == flag.flag &&
                line.episodes.any { item -> item.name == episode.name && item.url == episode.url } })
            .put("detailTokenSingleUse", consume.invoke(null, token, "validation-detail", VodConfig.getCid(), source.id, request) == null)
        assertTrue("The player must own its flags and episodes independently", isolated)
    }

    private fun <T> main(body: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = body() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun <T> call(body: () -> T): T = workers.submit(Callable {
        CallTimeout(30_000, false).use { deadline ->
            val thread = Thread.currentThread()
            deadline.start { thread.interrupt() }
            val restore = deadline.enter()
            try { body() } finally { restore.run() }
        }
    }).get(32, TimeUnit.SECONDS)

    companion object { private const val CONFIG_URL = "https://9280.kstore.vip/aiwex.json" }
}
