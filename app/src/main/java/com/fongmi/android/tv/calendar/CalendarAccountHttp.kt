package com.fongmi.android.tv.calendar

import com.fongmi.android.tv.App
import com.fongmi.android.tv.api.TmdbLibraryApi
import com.github.catvod.net.OkHttp
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

internal class CalendarAccountHttpException(val status: Int, val retryAfterSeconds: Long = 0) :
    IOException("Calendar account HTTP $status")

/** Account responses bypass public caches and site authentication/header overrides. */
internal object CalendarAccountHttp {
    private val client by lazy {
        CalendarRequestClient({ request ->
            OkHttp.trustedClient().newBuilder()
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).apply {
                    interceptors().removeAll { it.javaClass.simpleName in setOf(
                        "ProxyRedirectInterceptor", "AuthInterceptor", "RequestInterceptor") }
                    networkInterceptors().removeAll { it.javaClass.simpleName == "ResponseInterceptor" }
                }.build().newCall(request)
        }, { action -> App.post(Runnable { action() }) })
    }

    fun <T> request(url: HttpUrl, tag: Any, headers: Map<String, String>, body: String? = null,
        method: String = "GET", parser: (String, Headers) -> T, listener: TmdbLibraryApi.Listener<T>) {
        try {
            require(url.isHttps && url.host in setOf("api.trakt.tv", "auth.trakt.tv", "api.bgm.tv") &&
                url.username.isEmpty() && url.password.isEmpty()) { "Invalid calendar account endpoint" }
            val request = Request.Builder().url(url).tag(tag).apply {
                headers.forEach { (name, value) -> header(name, value) }
                method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType()))
            }.build()
            client.request(request, tag, parser, listener)
        } catch (error: Exception) { client.reject(tag, error, listener) }
    }

    fun cancel(tag: Any) = client.cancel(tag)
}
