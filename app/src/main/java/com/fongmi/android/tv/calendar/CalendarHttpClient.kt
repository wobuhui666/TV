package com.fongmi.android.tv.calendar

import com.fongmi.android.tv.App
import com.fongmi.android.tv.api.TmdbLibraryApi
import com.github.catvod.net.OkHttp
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Shared metadata transport. Cache identities include authentication headers. */
internal object CalendarHttpClient {
    private val client by lazy {
        CalendarRequestClient({ request -> OkHttp.trustedClient().newCall(request) },
            { action -> App.post(Runnable { action() }) })
    }

    fun <T> get(url: HttpUrl, tag: Any, headers: Map<String, String> = emptyMap(),
        parser: (String) -> T, listener: TmdbLibraryApi.Listener<T>) =
        client.get(url, tag, headers, parser, listener)

    fun cancel(tag: Any) = client.cancel(tag)

}

/** Coalesces a weekly feed while keeping each caller independently cancellable. */
internal class CalendarRequestClient(
    private val newCall: (Request) -> Call,
    private val post: (() -> Unit) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) : AutoCloseable {
    private data class RequestKey(val url: HttpUrl, val headers: Map<String, String>, val nonce: Long = 0)
    private data class RateScope(val host: String, val headers: Map<String, String>)
    private data class Cached(val time: Long, val body: String)
    private class Flight(val key: RequestKey, val scope: RateScope, val request: Request) {
        val subscribers = linkedSetOf<Pending>()
        var call: Call? = null
    }
    private abstract class Pending(val tag: Any, val key: RequestKey?, val cacheEnabled: Boolean) {
        var cancelled = false
        var flight: Flight? = null
        var task: FutureTask<Unit>? = null
        abstract fun parse(body: String, headers: Headers): Any?
        abstract fun success(value: Any?)
        abstract fun failure(error: Exception)
    }

    private val lock = Any()
    private val requests = mutableMapOf<Any, MutableSet<Pending>>()
    private val flights = mutableMapOf<RequestKey, Flight>()
    private val cooldowns = object : LinkedHashMap<RateScope, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RateScope, Long>?): Boolean = size > 32
    }
    private val cache = object : LinkedHashMap<RequestKey, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RequestKey, Cached>?): Boolean = size > 64
    }
    private val parsers = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        { task -> Thread(task, "calendar-parser").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    private var closed = false
    private var nonce = 0L

    fun <T> get(url: HttpUrl, tag: Any, headers: Map<String, String> = emptyMap(),
        parser: (String) -> T, listener: TmdbLibraryApi.Listener<T>) {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        submit(request, tag, true, { body, _ -> parser(body) }, listener)
    }

    /** Private requests retain response headers and bypass feed caches and coalescing. */
    fun <T> request(request: Request, tag: Any, parser: (String, Headers) -> T,
        listener: TmdbLibraryApi.Listener<T>) = submit(request, tag, false, parser, listener)

    fun <T> reject(tag: Any, error: Exception, listener: TmdbLibraryApi.Listener<T>) {
        val pending = object : Pending(tag, null, false) {
            override fun parse(body: String, headers: Headers): Any? = null
            override fun success(value: Any?) = Unit
            override fun failure(error: Exception) = listener.onError(error)
        }
        synchronized(lock) {
            if (closed) return
            requests.getOrPut(tag) { linkedSetOf() }.add(pending)
        }
        deliver(pending) { pending.failure(error) }
    }

    private fun <T> submit(request: Request, tag: Any, cacheEnabled: Boolean,
        parser: (String, Headers) -> T, listener: TmdbLibraryApi.Listener<T>) {
        val url = request.url
        val headers = request.headers.toMultimap().mapValues { it.value.joinToString(",") }
        val key = RequestKey(url, headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).toMap(),
            if (cacheEnabled) 0 else synchronized(lock) { ++nonce })
        val scope = RateScope(url.host, key.headers)
        val pending = object : Pending(tag, key, cacheEnabled) {
            override fun parse(body: String, headers: Headers): Any? = parser(body, headers)
            @Suppress("UNCHECKED_CAST") override fun success(value: Any?) = listener.onSuccess(value as T)
            override fun failure(error: Exception) = listener.onError(error)
        }
        var cached: Cached? = null
        var failure: Exception? = null
        var start: Flight? = null
        synchronized(lock) {
            if (closed) return
            requests.getOrPut(tag) { linkedSetOf() }.add(pending)
            cached = if (cacheEnabled) cache[key]?.takeIf { clock() - it.time < CACHE_TTL } else null
            if (cached == null) {
                val wait = (cooldowns[scope] ?: 0L) - clock()
                if (wait > 0) failure = CalendarAccountHttpException(429, seconds(wait))
                else {
                    val existing = flights[key]
                    val flight = existing ?: Flight(key, scope, request).also { flights[key] = it; start = it }
                    flight.subscribers.add(pending)
                    pending.flight = flight
                }
            }
        }
        cached?.let { parse(pending, it.body, Headers.Builder().build(), it.time) }
        failure?.let { deliver(pending) { pending.failure(it) } }
        start?.let(::start)
    }

    private fun start(flight: Flight) {
        try {
            val call = newCall(flight.request)
            synchronized(lock) {
                if (closed || flights[flight.key] !== flight || flight.subscribers.isEmpty()) {
                    call.cancel()
                    return
                }
                flight.call = call
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) = finish(flight, null, null, error)
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            var retryAfter = 0L
                            if (response.code == 429) {
                                val delay = retryDelay(response.header("Retry-After"), clock())
                                retryAfter = seconds(delay)
                                synchronized(lock) {
                                    cooldowns[flight.scope] = clock().let { now ->
                                        if (Long.MAX_VALUE - now < delay) Long.MAX_VALUE else now + delay
                                    }
                                }
                            }
                            if (!response.isSuccessful) {
                                throw CalendarAccountHttpException(response.code, retryAfter)
                            }
                            finish(flight, response.body?.string().orEmpty(), response.headers, null)
                        } catch (error: Exception) { finish(flight, null, null, error) }
                    }
                }
            })
        } catch (error: Exception) { finish(flight, null, null, error) }
    }

    private fun finish(flight: Flight, body: String?, headers: Headers?, error: Exception?) {
        val subscribers = synchronized(lock) {
            if (flights[flight.key] !== flight) return
            flights.remove(flight.key)
            flight.subscribers.toList().also { listeners ->
                flight.subscribers.clear()
                listeners.forEach { it.flight = null }
            }
        }
        subscribers.forEach { pending ->
            if (body != null) parse(pending, body, headers ?: Headers.Builder().build())
            else deliver(pending) { pending.failure(error ?: IOException("Calendar response unavailable")) }
        }
    }

    private fun parse(pending: Pending, body: String, headers: Headers, cachedTime: Long? = null) {
        val task = FutureTask<Unit> {
            if (!active(pending)) return@FutureTask
            try {
                val value = pending.parse(body, headers)
                synchronized(lock) {
                    if (!activeLocked(pending)) return@FutureTask
                    if (pending.cacheEnabled && pending.key != null) cache[pending.key] = Cached(cachedTime ?: clock(), body)
                }
                deliver(pending) { pending.success(value) }
            } catch (error: Exception) { deliver(pending) { pending.failure(error) } }
        }
        synchronized(lock) {
            if (!activeLocked(pending)) return
            pending.task = task
        }
        try {
            parsers.execute(task)
            if (!active(pending)) { task.cancel(true); parsers.remove(task) }
        }
        catch (error: RejectedExecutionException) {
            deliver(pending) { pending.failure(IOException("Calendar parser busy", error)) }
        }
    }

    private fun active(pending: Pending) = synchronized(lock) { activeLocked(pending) }
    private fun activeLocked(pending: Pending) = !closed && !pending.cancelled && requests[pending.tag]?.contains(pending) == true

    private fun deliver(pending: Pending, action: () -> Unit) {
        if (!active(pending)) return
        post {
            val registered = synchronized(lock) {
                val active = activeLocked(pending)
                requests[pending.tag]?.let { group ->
                    group.remove(pending)
                    if (group.isEmpty()) requests.remove(pending.tag)
                }
                active
            }
            if (registered) action()
        }
    }

    fun cancel(tag: Any) {
        synchronized(lock) {
            requests.remove(tag).orEmpty().forEach { pending ->
                pending.cancelled = true
                pending.task?.let { it.cancel(true); parsers.remove(it) }
                pending.flight?.let { flight ->
                    flight.subscribers.remove(pending)
                    if (flight.subscribers.isEmpty() && flights[flight.key] === flight) {
                        flights.remove(flight.key)
                        flight.call?.cancel()
                    }
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            requests.keys.toList().forEach(::cancel)
            closed = true
            cache.clear()
            cooldowns.clear()
        }
        parsers.shutdownNow()
    }

    companion object {
        private const val CACHE_TTL = 30 * 60_000L

        private fun seconds(milliseconds: Long) = milliseconds / 1000 + if (milliseconds % 1000 > 0) 1 else 0

        internal fun retryDelay(value: String?, now: Long): Long {
            value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let {
                return if (it > Long.MAX_VALUE / 1000) Long.MAX_VALUE else it * 1000
            }
            return try {
                value?.let { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now }
                    ?.coerceAtLeast(0) ?: 60_000L
            } catch (_: RuntimeException) { 60_000L }
        }
    }
}
