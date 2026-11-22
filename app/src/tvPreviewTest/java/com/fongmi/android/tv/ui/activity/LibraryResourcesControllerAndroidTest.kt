package com.fongmi.android.tv.ui.activity

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fongmi.android.tv.bean.Episode
import com.fongmi.android.tv.bean.Flag
import com.fongmi.android.tv.bean.Site
import com.fongmi.android.tv.bean.Vod
import com.fongmi.android.tv.source.EpisodeLabel
import com.fongmi.android.tv.source.EpisodeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class LibraryResourcesControllerAndroidTest {
    @Test
    fun episodeMatchingLeavesMainThreadFreeAndDeliversSelectionOnMain() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("com.fongmi.android.tv.preview", instrumentation.targetContext.packageName)
        val entered = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val failures = AtomicInteger()
        val block = CountDownLatch(1)
        val matching = object : Episode() {
            override fun getName() = "第2集"
            override fun getUrl() = "fixture-episode-2"
            override fun getLabel(): EpisodeLabel {
                assertFalse("Episode matching runs off main", Looper.myLooper() == Looper.getMainLooper())
                entered.countDown()
                assertTrue("Matching is released", block.await(10, TimeUnit.SECONDS))
                return super.getLabel()
            }
        }
        val flag = Flag("fixture-line").apply { episodes.add(matching) }
        val source = Vod().apply {
            name = "Fixture Show"
            id = "episode-worker"
            year = "2026"
            typeName = "电视剧"
            site = Site.get("fixture-worker", "Fixture")
            flags = listOf(flag)
        }
        val controller = LibraryResourcesController(object : LibraryResourcesController.Listener {
            override fun onResources(resources: List<Vod>, running: Boolean, partialError: Boolean) = Unit
            override fun onSelected(source: Vod, selectedFlag: Flag, selectedEpisode: Episode) {
                assertTrue("Selection is delivered on main", Looper.myLooper() == Looper.getMainLooper())
                assertEquals("fixture-episode-2", selectedEpisode.url)
                completed.countDown()
            }
            override fun onSelectionFailed(source: Vod?) { failures.incrementAndGet(); completed.countDown() }
        })
        try {
            instrumentation.runOnMainSync {
                controller.seed(listOf(source))
                controller.metadata(source.name, emptyList(), source.year, false, 1, null, null)
                controller.choose(EpisodeTarget.coordinate(1, 2), null)
            }
            assertTrue("Background matching starts", entered.await(5, TimeUnit.SECONDS))
            val heartbeat = CountDownLatch(1)
            instrumentation.runOnMainSync { heartbeat.countDown() }
            assertTrue("Main remains responsive while matching waits", heartbeat.await(1, TimeUnit.SECONDS))
            block.countDown()
            assertTrue("Selection completes", completed.await(5, TimeUnit.SECONDS))
            assertEquals(0, failures.get())
        } finally {
            block.countDown()
            instrumentation.runOnMainSync { controller.close() }
        }
    }
}
