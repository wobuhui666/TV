package com.fongmi.android.tv.ui.search

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader
import java.io.StringReader

/** Search input only needs a bounded set of labels, even when an old cache is large. */
object SearchInputParser {
    const val MAX_WORDS = 30
    const val MAX_RECORDS = 9
    private const val MAX_LABEL_LENGTH = 255

    @JvmStatic fun records(json: String): List<String> {
        val labels = linkedSetOf<String>()
        try {
            JsonReader(StringReader(json)).use { reader ->
                if (reader.peek() != JsonToken.BEGIN_ARRAY) return emptyList()
                reader.beginArray()
                while (!Thread.currentThread().isInterrupted && labels.size < MAX_RECORDS && reader.hasNext()) {
                    if (reader.peek() == JsonToken.STRING) add(labels, reader.nextString()) else reader.skipValue()
                }
            }
        } catch (_: Exception) {
            // Preserve valid entries preceding a corrupt tail without blocking the input page.
        }
        return labels.toList()
    }

    @JvmStatic fun words(json: String): List<String> = words(StringReader(json))

    @JvmStatic fun mergeRecords(newer: List<String>, cached: List<String>, removed: Collection<String>): List<String> {
        val records = linkedSetOf<String>()
        for (label in newer) {
            if (records.size == MAX_RECORDS) break
            add(records, label)
        }
        for (label in cached) {
            if (records.size == MAX_RECORDS) break
            if (label !in removed) add(records, label)
        }
        return records.toList()
    }

    @JvmStatic fun words(source: Reader): List<String> {
        val labels = linkedSetOf<String>()
        try {
            JsonReader(source).use { reader ->
                if (reader.peek() != JsonToken.BEGIN_OBJECT) return emptyList()
                reader.beginObject()
                while (!Thread.currentThread().isInterrupted && labels.size < MAX_WORDS && reader.hasNext()) {
                    if (reader.nextName() != "data" || reader.peek() != JsonToken.BEGIN_ARRAY) {
                        reader.skipValue()
                        continue
                    }
                    reader.beginArray()
                    while (!Thread.currentThread().isInterrupted && labels.size < MAX_WORDS && reader.hasNext()) {
                        when (reader.peek()) {
                            JsonToken.STRING -> add(labels, reader.nextString())
                            JsonToken.BEGIN_OBJECT -> {
                                var title: String? = null
                                var name: String? = null
                                reader.beginObject()
                                while (!Thread.currentThread().isInterrupted && reader.hasNext()) {
                                    val field = reader.nextName()
                                    if ((field == "title" || field == "name") && reader.peek() == JsonToken.STRING) {
                                        val value = reader.nextString()
                                        if (field == "title") title = value else name = value
                                    } else reader.skipValue()
                                }
                                reader.endObject()
                                add(labels, title?.takeIf { it.isNotBlank() } ?: name.orEmpty())
                            }
                            else -> reader.skipValue()
                        }
                    }
                    if (labels.size < MAX_WORDS) reader.endArray()
                }
            }
        } catch (_: Exception) {
            // A malformed or cancelled response can only supply labels already read.
        }
        return labels.toList()
    }

    private fun add(labels: MutableSet<String>, value: String) {
        val label = value.trim().take(MAX_LABEL_LENGTH)
        if (label.isNotEmpty()) labels.add(label)
    }
}
