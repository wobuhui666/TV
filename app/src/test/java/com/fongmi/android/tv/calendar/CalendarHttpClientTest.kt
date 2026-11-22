package com.fongmi.android.tv.calendar

import com.fongmi.android.tv.api.TmdbLibraryApi
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CalendarHttpClientTest {
    private val url = "https://api.bgm.tv/calendar".toHttpUrl()

    @Test(timeout = 10_000) fun shouldCoalesceWeeklyFeedWithoutCancellingOtherTags() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            entered.countDown()
            assertTrue(release.await(3, TimeUnit.SECONDS))
            response(chain.request(), 200, "[1,2]")
        }.build()
        val deliveries = LinkedBlockingQueue<() -> Unit>()
        val client = CalendarRequestClient(transport::newCall, { deliveries.add(it) })
        val first = Recorder()
        val second = Recorder()
        val firstTag = Any()
        try {
            client.get(url, firstTag, parser = { it }, listener = first)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            client.get(url, Any(), parser = { it }, listener = second)
            client.cancel(firstTag)
            release.countDown()
            drain(deliveries)
            assertEquals(1, calls.get())
            assertTrue(first.values.isEmpty())
            assertTrue(first.errors.isEmpty())
            assertEquals(listOf("[1,2]"), second.values)
        } finally { release.countDown(); client.close(); close(transport) }
    }

    @Test(timeout = 10_000) fun shouldCancelAQueuedDeliveryAfterParsingHasCompleted() {
        val transport = OkHttpClient.Builder().addInterceptor { chain -> response(chain.request(), 200, "body") }.build()
        val deliveries = LinkedBlockingQueue<() -> Unit>()
        val client = CalendarRequestClient(transport::newCall, { deliveries.add(it) })
        val recorder = Recorder()
        val tag = Any()
        try {
            client.get(url, tag, parser = { it }, listener = recorder)
            val deliver = deliveries.poll(3, TimeUnit.SECONDS)
            assertNotNull(deliver)
            client.cancel(tag)
            deliver!!()
            assertTrue(recorder.values.isEmpty())
            assertTrue(recorder.errors.isEmpty())
        } finally { client.close(); close(transport) }
    }

    @Test(timeout = 10_000) fun shouldIsolateCredentialsAndExpireCacheFromOriginalFetchTime() {
        val calls = AtomicInteger()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            response(chain.request(), 200, chain.request().header("Authorization").orEmpty())
        }.build()
        val deliveries = LinkedBlockingQueue<() -> Unit>()
        var now = 1_000L
        val client = CalendarRequestClient(transport::newCall, { deliveries.add(it) }, { now })
        try {
            val first = Recorder()
            client.get(url, Any(), mapOf("Authorization" to "Bearer first"), { it }, first)
            drain(deliveries)
            now += 29 * 60_000L
            client.get(url, Any(), mapOf("Authorization" to "Bearer first"), { it }, Recorder())
            drain(deliveries)
            assertEquals(1, calls.get())
            val second = Recorder()
            client.get(url, Any(), mapOf("Authorization" to "Bearer second"), { it }, second)
            drain(deliveries)
            assertEquals(listOf("Bearer second"), second.values)
            assertEquals(2, calls.get())
            now += 60_000L
            client.get(url, Any(), mapOf("Authorization" to "Bearer first"), { it }, Recorder())
            drain(deliveries)
            assertEquals(3, calls.get())
        } finally { client.close(); close(transport) }
    }

    @Test(timeout = 10_000) fun shouldRespectRateLimitAcrossCalendarDatesAndAllowRetryAfterCooldown() {
        val calls = AtomicInteger()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val count = calls.incrementAndGet()
            response(chain.request(), if (count == 1) 429 else 200, "body", if (count == 1) "120" else null)
        }.build()
        val deliveries = LinkedBlockingQueue<() -> Unit>()
        var now = 1_000L
        val client = CalendarRequestClient(transport::newCall, { deliveries.add(it) }, { now })
        try {
            val first = Recorder()
            client.get(url, Any(), parser = { it }, listener = first)
            drain(deliveries)
            assertEquals(1, first.errors.size)
            val second = Recorder()
            val next = url.newBuilder().addQueryParameter("date", "tomorrow").build()
            client.get(next, Any(), parser = { it }, listener = second)
            drain(deliveries)
            assertEquals(1, second.errors.size)
            assertEquals(1, calls.get())
            now += 120_000L
            val third = Recorder()
            client.get(next, Any(), parser = { it }, listener = third)
            drain(deliveries)
            assertEquals(listOf("body"), third.values)
            assertEquals(2, calls.get())
        } finally { client.close(); close(transport) }
    }

    @Test(timeout = 10_000) fun shouldBypassCalendarCacheForAccountRequests() {
        val calls = AtomicInteger()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), 200, calls.incrementAndGet().toString())
        }.build()
        val deliveries = LinkedBlockingQueue<() -> Unit>()
        val client = CalendarRequestClient(transport::newCall, { deliveries.add(it) })
        try {
            val first = Recorder()
            val second = Recorder()
            val request = Request.Builder().url(url).build()
            client.request(request, Any(), { body, _ -> body }, first)
            drain(deliveries)
            client.request(request, Any(), { body, _ -> body }, second)
            drain(deliveries)
            assertEquals(listOf("1"), first.values)
            assertEquals(listOf("2"), second.values)
            assertEquals(2, calls.get())
        } finally { client.close(); close(transport) }
    }

    @Test fun shouldParseRetryAfterWithoutInventingRapidRetryIntervals() {
        assertEquals(120_000L, CalendarRequestClient.retryDelay("120", 0))
        assertEquals(120_000L, CalendarRequestClient.retryDelay("Thu, 01 Jan 1970 00:02:00 GMT", 0))
        assertEquals(60_000L, CalendarRequestClient.retryDelay(null, 0))
        assertEquals(60_000L, CalendarRequestClient.retryDelay("invalid", 0))
        assertEquals(Long.MAX_VALUE, CalendarRequestClient.retryDelay(Long.MAX_VALUE.toString(), 0))
    }

    private fun drain(deliveries: LinkedBlockingQueue<() -> Unit>) {
        val first = deliveries.poll(3, TimeUnit.SECONDS)
        assertNotNull("Expected a main-thread callback", first)
        first!!()
        while (true) (deliveries.poll() ?: break).invoke()
    }

    private fun response(request: Request, code: Int, body: String, retryAfter: String? = null) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .body(body.toResponseBody("application/json".toMediaType())).apply {
                retryAfter?.let { header("Retry-After", it) }
            }.build()

    private fun close(transport: OkHttpClient) {
        transport.connectionPool.evictAll()
        transport.dispatcher.executorService.shutdownNow()
    }

    private class Recorder : TmdbLibraryApi.Listener<String> {
        val values = mutableListOf<String>()
        val errors = mutableListOf<Exception>()
        override fun onSuccess(value: String) { values.add(value) }
        override fun onError(error: Exception) { errors.add(error) }
    }
}
