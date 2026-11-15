package com.fongmi.android.tv.playback.live

import com.fongmi.android.tv.bean.Channel
import java.util.function.IntFunction

/** Choices belong to the channel and ordered URLs present when the panel opened. */
class LiveLineSelection(private val channel: Channel, private val generation: Long) {
    private val urls = channel.urls.toList()
    val selectedIndex = channel.index.coerceIn(0, (urls.size - 1).coerceAtLeast(0))

    fun labels(fallback: IntFunction<String>): List<String> = urls.mapIndexed { index, url ->
        val label = url.orEmpty().substringAfter('$', "").substringBefore('$').trim()
        if (label.isNotEmpty() && !label.contains("://") && !label.startsWith("//")) label
        else fallback.apply(index + 1)
    }

    fun matches(current: Channel?, currentGeneration: Long): Boolean =
        current != null && current === channel && generation == currentGeneration && urls == current.urls

    fun contains(index: Int): Boolean =
        index in urls.indices && urls[index].orEmpty().substringBefore('$').isNotBlank()
}
