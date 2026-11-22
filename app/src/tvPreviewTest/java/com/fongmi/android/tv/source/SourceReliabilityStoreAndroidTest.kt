package com.fongmi.android.tv.source

import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fongmi.android.tv.bean.Site
import com.fongmi.android.tv.bean.Vod
import com.github.catvod.utils.Prefers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SourceReliabilityStoreAndroidTest {
    private val prefix = "source_reliability_"
    private lateinit var preferences: SharedPreferences
    private var saved: Map<String, *> = emptyMap<String, Any>()

    @Before
    fun setup() {
        assertEquals("com.fongmi.android.tv.preview", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
        preferences = Prefers.getPrefers()
        saved = preferences.all.filterKeys { it.startsWith(prefix) }
        val editor = preferences.edit()
        saved.keys.forEach(editor::remove)
        assertTrue(editor.commit())
    }

    @After
    fun restore() {
        if (!::preferences.isInitialized) return
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        saved.forEach { (key, value) ->
            when (value) {
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
            }
        }
        assertTrue(editor.commit())
    }

    @Test
    fun repeatedReadsDoNotWritePreferencesAndStillSeeNewScores() {
        assertTrue(preferences.edit().putInt(prefix + "fixture_read", 2).commit())
        val changes = AtomicInteger()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith(prefix) == true) changes.incrementAndGet()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val store = SourceReliabilityStore()
                repeat(500) {
                    assertEquals(2, store.score("fixture_read"))
                    assertEquals(0, store.score("fixture_missing"))
                }
                assertEquals(0, changes.get())
                assertFalse(preferences.contains(prefix + "keys"))
                assertTrue(preferences.edit().putInt(prefix + "fixture_read", 3).commit())
                assertEquals("Reads do not return a stale cached score", 3, store.score("fixture_read"))
            }
        } finally { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    @Test
    fun writesKeepOneEntryPerSourceAndClearRemovesEveryScore() {
        val first = Vod().apply { site = Site.get("fixture-a", "Fixture"); id = "one/part" }
        val second = Vod().apply { site = Site.get("fixture-b", "Fixture"); id = "two" }
        val store = SourceReliabilityStore()
        repeat(5) { store.recordSuccess(first) }
        repeat(5) { store.recordFailure(second) }
        assertEquals(3, SourceReliabilityStore().score(first))
        assertEquals(-3, SourceReliabilityStore().score(second))
        assertEquals(listOf("fixture-a_one_part", "fixture-b_two"),
            preferences.getString(prefix + "keys", "")!!.split('\n'))
        store.clear()
        assertTrue(preferences.all.keys.none { it.startsWith(prefix) })
        assertEquals(0, store.score(first))
        assertEquals(0, SourceReliabilityStore().score(second))
    }
}
