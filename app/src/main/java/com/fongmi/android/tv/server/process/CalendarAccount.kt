package com.fongmi.android.tv.server.process

import com.fongmi.android.tv.calendar.CalendarAccountPairing
import com.fongmi.android.tv.diagnostics.DiagnosticManager
import com.fongmi.android.tv.server.impl.Process
import com.github.catvod.utils.Asset
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.EOFException

/** Credentials use a bounded JSON body, never the generic source-settings endpoint or query string. */
class CalendarAccount @JvmOverloads constructor(private val pairing: CalendarAccountPairing = CalendarAccountPairing.get()) : Process {
    override fun isRequest(session: IHTTPSession, url: String) = url == "/account/session" || url == "/account.html"

    override fun doResponse(session: IHTTPSession, url: String, files: Map<String, String>): Response {
        if (!DiagnosticManager.isLocalPeer(session.remoteIpAddress) || !sameOrigin(session.headers))
            return response(Status.FORBIDDEN)
        if (url == "/account.html") {
            if (session.method != Method.GET) return response(Status.METHOD_NOT_ALLOWED)
            return secure(NanoHTTPD.newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", Asset.open("account.html"), -1))
        }
        if (url != "/account/session") return response(Status.NOT_FOUND)
        if (session.method != Method.GET && session.method != Method.POST) return response(Status.METHOD_NOT_ALLOWED)
        return try {
            val code = session.headers["x-calendar-pairing"]
            val current = pairing.status(code)
            val result = if (session.method == Method.GET) current else {
                if (session.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json")
                    return response(Status.UNSUPPORTED_MEDIA_TYPE)
                val size = session.headers["content-length"]?.toIntOrNull()
                if (size == null || size !in 1..4096 || session.headers.containsKey("transfer-encoding"))
                    return response(Status.BAD_REQUEST)
                val bytes = ByteArray(size)
                var offset = 0
                while (offset < size) {
                    val read = session.inputStream.read(bytes, offset, size - offset)
                    if (read <= 0) throw EOFException()
                    offset += read
                }
                val body = JsonParser.parseString(bytes.toString(Charsets.UTF_8))
                require(body.isJsonObject)
                val token = body.asJsonObject.get(if (current.provider == "trakt") "clientId" else "token")
                require(token != null && token.isJsonPrimitive && token.asJsonPrimitive.isString)
                pairing.submit(code, token.asString.trim())
            }
            response(Status.OK, JsonObject().apply {
                addProperty("state", result.state)
                addProperty("remainingMillis", result.remainingMillis)
                addProperty("provider", result.provider)
                if (result.state == "authorizing") {
                    addProperty("verificationUrl", result.verificationUrl)
                    addProperty("userCode", result.userCode)
                }
            }.toString())
        } catch (_: SecurityException) { response(Status.FORBIDDEN) }
        catch (_: IllegalStateException) { response(Status.GONE) }
        catch (_: Exception) { response(Status.BAD_REQUEST) }
    }

    private fun sameOrigin(headers: Map<String, String>): Boolean {
        val origin = headers["origin"] ?: return true
        val source = origin.toHttpUrlOrNull() ?: return false
        val target = ("http://" + (headers["host"] ?: return false)).toHttpUrlOrNull()
        return source == target
    }

    private fun response(status: Status, body: String = "{}") = secure(
        NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", body))

    private fun secure(response: Response): Response = response.apply {
        addHeader("Cache-Control", "no-store")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        addHeader("Connection", "close")
    }
}
