package com.fongmi.android.tv.playback.vod

import androidx.media3.common.C
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fongmi.android.tv.api.config.VodConfig
import com.fongmi.android.tv.bean.Episode
import com.fongmi.android.tv.bean.Flag
import com.fongmi.android.tv.bean.History
import com.fongmi.android.tv.bean.Site
import com.fongmi.android.tv.bean.Vod
import com.github.catvod.utils.Prefers
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Parcel handoffs and controller requests, with all source calls held by a local host. */
@RunWith(AndroidJUnit4::class)
class LibraryDetailHandoffAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val preferenceKeys = listOf("incognito", "community_skip_enabled", "preload_next", "source_selection_mode")
    private var savedPreferences: Map<String, Any?>? = null
    private val fixtures = mutableListOf<Fixture>()
    private var controller: VodPlaybackController? = null

    @Before
    fun isolatePlaybackPreferences() = main {
        assertEquals("com.fongmi.android.tv.preview", instrumentation.targetContext.packageName)
        val preferences = Prefers.getPrefers().all
        savedPreferences = preferenceKeys.associateWith { preferences[it] }
        Prefers.put("incognito", true)
        Prefers.put("community_skip_enabled", false)
        Prefers.put("preload_next", false)
        Prefers.put("source_selection_mode", "LEGACY")
    }

    @After
    fun restorePlaybackPreferences() = main {
        controller?.reset()
        fixtures.forEach { it.consume() }
        savedPreferences?.let { values ->
            val editor = Prefers.getPrefers().edit()
            values.forEach { (key, value) ->
                when (value) {
                    null -> editor.remove(key)
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                }
            }
            editor.apply()
        }
    }

    @Test
    fun shouldDeepCopyTheSelectedPlaylistAndConsumeItsTokenOnce() = main {
        val fixture = fixture()
        fixture.selected.isGeneratedLabel = true
        fixture.handoff()

        // Changes on the work page after handoff must never alter the player's snapshot.
        fixture.selected.name = "Changed source label"
        fixture.flag.episodes.removeAt(1)
        fixture.source.flags.clear()
        val copied = requireNotNull(fixture.consume())
        val copiedFlag = copied.flags.single()
        val copiedEpisode = copiedFlag.episodes.first()

        assertNotSame(fixture.source, copied)
        assertNotSame(fixture.source.flags, copied.flags)
        assertNotSame(fixture.flag, copiedFlag)
        assertNotSame(fixture.flag.episodes, copiedFlag.episodes)
        assertNotSame(fixture.selected, copiedEpisode)
        assertEquals(2, copiedFlag.episodes.size)
        assertEquals(fixture.request.title, copiedEpisode.name)
        assertEquals(fixture.request.id, copiedEpisode.url)
        assertTrue(copiedEpisode.isGeneratedLabel)
        copiedEpisode.name = "Changed player label"
        assertEquals("Changed source label", fixture.selected.name)
        assertNull("A detail token can only be consumed once", fixture.consume())
    }

    @Test
    fun shouldInvalidateAHandedOffDetailWhenItsPlaybackContextChanges() = main {
        for (changed in listOf("work", "vod", "site", "line", "url", "name")) {
            val fixture = fixture()
            fixture.handoff()
            val request = VodPlayRequest.create(
                if (changed == "site") "other-site" else fixture.request.key,
                Flag(if (changed == "line") "other-line" else fixture.request.flag),
                Episode.create(
                    if (changed == "name") "S01E02" else fixture.request.title,
                    if (changed == "url") "other-file" else fixture.request.id
                )
            )
            assertNull(changed, LibraryPlaybackPrefetch.consumeDetail(
                fixture.token,
                if (changed == "work") "other-work" else fixture.reference,
                VodConfig.getCid(),
                if (changed == "vod") "other-vod" else fixture.source.id,
                request
            ))
            assertNull("Rejected $changed context cannot reuse its token", fixture.consume())
        }
    }

    @Test
    fun shouldReusePreparedDetailWithoutRepeatingTheSourceRequest() = main {
        val fixture = fixture()
        fixture.handoff()
        val historyKey = "detail-handoff-" + UUID.randomUUID()
        val history = History().apply {
            key = historyKey
            vodFlag = fixture.request.flag
            vodRemarks = fixture.request.title
            episodeUrl = fixture.request.id
            opening = C.TIME_UNSET
            position = 42_000
            duration = 120_000
        }
        val state = VodPlaybackState().apply { this.history = history }
        var detailRequests = 0
        var playId = fixture.request.id
        var requestedPlayer: VodPlayRequest? = null
        var renderedDetail: Vod? = null
        val host = Proxy.newProxyInstance(
            VodPlaybackHost::class.java.classLoader,
            arrayOf(VodPlaybackHost::class.java)
        ) { _, method, arguments ->
            when (method.name) {
                "takePreparedDetail" -> fixture.consume()
                "getVodKey" -> fixture.request.key
                "getVodId" -> fixture.source.id
                "getVodName" -> fixture.source.name
                "getVodPlayId" -> playId
                "getVodPlayName" -> fixture.request.title
                "getHistoryKey" -> historyKey
                "getSourceCandidates" -> emptyList<Vod>()
                "isPlayerEmpty" -> true
                "clearVodPlayId" -> { playId = ""; null }
                "requestDetail" -> { detailRequests++; null }
                "requestPlayer" -> { requestedPlayer = arguments!![0] as VodPlayRequest; null }
                "renderDetail" -> { renderedDetail = arguments!![0] as Vod; null }
                else -> when (method.returnType) {
                    String::class.java -> ""
                    java.lang.Boolean.TYPE -> false
                    java.lang.Long.TYPE -> 0L
                    java.lang.Integer.TYPE -> 0
                    else -> null
                }
            }
        } as VodPlaybackHost
        val playback = VodPlaybackController(host, state)
        controller = playback
        playback.requestDetail()

        assertEquals("Prepared detail avoids a second source detail call", 0, detailRequests)
        assertNotSame(fixture.source, renderedDetail)
        assertNotSame(fixture.flag, state.flag)
        assertEquals(fixture.request.id, state.episode.url)
        assertEquals(fixture.request.id, requestedPlayer?.id)
        assertEquals(fixture.request.title, requestedPlayer?.title)
        assertEquals("The confirmed episode keeps its resume position", 42_000L, state.history.position)
        assertFalse("Player selection cannot mutate the work page's episode", fixture.selected.isSelected)
        assertNull("The controller consumed the real detail token", fixture.consume())

        playback.requestDetail()
        assertEquals("A later refresh requests new details after the snapshot is used", 1, detailRequests)
    }

    private fun fixture() = Fixture().also { fixtures.add(it) }

    private class Fixture {
        val reference = "handoff-work-" + UUID.randomUUID()
        val selected = Episode.create("S01E01", "vod_id=42&mobile_time=100&_client_ts=500||1080@720")
        val flag = Flag("handoff-line").apply {
            episodes.add(selected)
            episodes.add(Episode.create("S01E02", selected.url))
        }
        val source = Vod().apply {
            id = "handoff-vod-" + UUID.randomUUID()
            name = "Offline handoff series"
            typeName = "tv"
            site = Site.get("handoff-site", "Offline handoff source")
            flags.add(flag)
        }
        val request = VodPlayRequest.create(source.siteKey, flag, selected)
        var token = ""

        fun handoff() {
            token = LibraryPlaybackPrefetch.handoffDetail(reference, source, flag, selected)
            assertFalse("Production handoff must create a detail token", token.isEmpty())
        }

        fun consume(): Vod? = LibraryPlaybackPrefetch.consumeDetail(
            token, reference, VodConfig.getCid(), source.id, request
        )
    }

    private fun main(action: () -> Unit) {
        val error = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try { action() } catch (failure: Throwable) { error.set(failure) }
        }
        error.get()?.let { throw it }
    }
}
