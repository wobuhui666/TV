package com.fongmi.android.tv.server.process

import com.fongmi.android.tv.calendar.CalendarAccountPairing
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class CalendarAccountTest {
    private val pairing = CalendarAccountPairing()
    private val process = CalendarAccount(pairing)
    private var received = ""
    private val code = pairing.start { received = it }

    @Test fun shouldAcceptJsonTokenWithoutReturningCredentials() {
        val response = process.doResponse(session(Method.POST, "{\"token\":\" test-token \"}"), "/account/session", emptyMap())
        assertEquals(Status.OK, response.status)
        assertEquals("test-token", received)
        val body = response.data.readBytes().toString(Charsets.UTF_8)
        assertTrue(body.contains("pending"))
        assertFalse(body.contains(received))
        assertFalse(body.contains(code))
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"))
        assertNull(response.getHeader("Access-Control-Allow-Origin"))
    }

    @Test fun shouldRequireTvPairingAndPrivatePeer() {
        for (headers in listOf(mapOf("x-calendar-pairing" to ""), mapOf("x-calendar-pairing" to "wrong"))) {
            assertEquals(Status.FORBIDDEN, process.doResponse(session(Method.GET, extra = headers), "/account/session", emptyMap()).status)
        }
        assertEquals(Status.FORBIDDEN, process.doResponse(session(Method.GET, remote = "8.8.8.8"), "/account/session", emptyMap()).status)
        pairing.cancel(code)
        assertEquals(Status.GONE, process.doResponse(session(Method.GET), "/account/session", emptyMap()).status)
    }

    @Test fun shouldRejectCrossOriginWritesWithoutConsumingBody() {
        for (origin in listOf("https://example.com", "null", "http://127.0.0.1:9979")) {
            assertEquals(Status.FORBIDDEN, process.doResponse(session(Method.POST, "not-json", extra = mapOf("origin" to origin)), "/account/session", emptyMap()).status)
        }
        assertEquals("", received)
    }

    @Test fun shouldRejectMethodsFormsOversizedAndMalformedBodies() {
        for (method in listOf(Method.PUT, Method.DELETE, Method.OPTIONS)) {
            assertEquals(Status.METHOD_NOT_ALLOWED, process.doResponse(session(method), "/account/session", emptyMap()).status)
        }
        assertEquals(Status.UNSUPPORTED_MEDIA_TYPE, process.doResponse(session(Method.POST, "token=secret", extra = mapOf("content-type" to "application/x-www-form-urlencoded")), "/account/session", emptyMap()).status)
        for (headers in listOf(mapOf("content-length" to "4097"), mapOf("content-length" to "-1"), mapOf("transfer-encoding" to "chunked"))) {
            assertEquals(Status.BAD_REQUEST, process.doResponse(session(Method.POST, "{}", extra = headers), "/account/session", emptyMap()).status)
        }
        for (body in listOf("broken", "{}", "[]", "{\"token\":1}", "{\"token\":\"\"}", "{\"token\":\"a\\r\\nb\"}")) {
            assertEquals(body, Status.BAD_REQUEST, process.doResponse(session(Method.POST, body), "/account/session", emptyMap()).status)
        }
        assertEquals("", received)
    }

    @Test fun shouldReadBoundedBodyAndReportCompletionOverRealHttp() {
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession) = process.doResponse(session, session.uri, emptyMap())
        }
        val client = OkHttpClient()
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        val base = "http://127.0.0.1:${server.listeningPort}"
        fun request(body: String? = null, origin: String = base): okhttp3.Response {
            val request = Request.Builder().url("$base/account/session")
                .header("X-Calendar-Pairing", code).header("Origin", origin)
            if (body != null) request.post(body.toRequestBody("application/json".toMediaType()))
            return client.newCall(request.build()).execute()
        }
        try {
            request("{\"token\":\"real-http-fixture\"}").use {
                assertEquals(200, it.code)
                assertTrue(it.body.string().contains("pending"))
            }
            assertEquals("real-http-fixture", received)
            assertTrue(pairing.complete(code) {})
            request().use {
                assertEquals(200, it.code)
                assertTrue(it.body.string().contains("connected"))
            }
            request("{\"token\":\"blocked\"}", "https://example.com").use { assertEquals(403, it.code) }
            assertEquals("real-http-fixture", received)
            pairing.clear()
            request().use { assertEquals(410, it.code) }
        } finally {
            server.stop()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun session(method: Method, body: String = "", extra: Map<String, String> = emptyMap(), remote: String = "192.168.1.2"): IHTTPSession {
        val bytes = body.toByteArray()
        val input = ByteArrayInputStream(bytes)
        val headers = mapOf("host" to "127.0.0.1:9978", "origin" to "http://127.0.0.1:9978",
            "x-calendar-pairing" to code, "content-type" to "application/json", "content-length" to bytes.size.toString()) + extra
        return Proxy.newProxyInstance(IHTTPSession::class.java.classLoader, arrayOf(IHTTPSession::class.java)) { _, invoked, _ ->
            when (invoked.name) {
                "getMethod" -> method
                "getHeaders" -> headers
                "getRemoteIpAddress" -> remote
                "getInputStream" -> input
                else -> throw AssertionError("Unexpected session access: ${invoked.name}")
            }
        } as IHTTPSession
    }
}
