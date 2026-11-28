package com.fongmi.android.tv.calendar

import java.security.SecureRandom

/** A foreground TV grants one short-lived phone connection. Account tokens are never retained here. */
class CalendarAccountPairing(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Snapshot(val state: String, val remainingMillis: Long, val provider: String,
        val verificationUrl: String = "", val userCode: String = "")

    private class Session(val code: String, var deadline: Long, val provider: CalendarProvider, var submit: ((String) -> Unit)?) {
        var state = "ready"
        var verificationUrl = ""
        var userCode = ""
    }

    private val random = SecureRandom()
    private var session: Session? = null

    @Synchronized
    fun start(provider: CalendarProvider = CalendarProvider.BANGUMI, submit: (String) -> Unit): String {
        require(provider != CalendarProvider.TMDB)
        val code = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        session = Session(code, clock() + DURATION, provider, submit)
        return code
    }

    @Synchronized
    fun status(code: String?): Snapshot = snapshot(requireSession(code))

    /** The callback must dispatch to the main thread and check isPending before using the token. */
    @Synchronized
    fun submit(code: String?, token: String): Snapshot {
        val current = requireSession(code)
        require(token.length in 1..2048 && token.all { it in '!'..'~' }) { "Invalid token" }
        if (current.provider == CalendarProvider.TRAKT) require(token.length <= 256 && token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        if (current.state in setOf("pending", "authorizing", "connected")) return snapshot(current)
        current.state = "pending"
        current.submit?.invoke(token)
        return snapshot(current)
    }

    @Synchronized
    fun isPending(code: String): Boolean = active()?.let { it.code == code && it.state == "pending" } == true

    /** Only the public verification URL and user code are shared with the phone. */
    @Synchronized
    fun authorize(code: String, device: CalendarDeviceCode): Boolean {
        if (!isPending(code) || session?.provider != CalendarProvider.TRAKT) return false
        session!!.apply {
            deadline = minOf(deadline, clock() + device.expiresIn * 1000L)
            state = "authorizing"
            verificationUrl = device.verificationUrl
            userCode = device.userCode
        }
        return true
    }

    /** Cancellation and expiry are checked atomically with saving the account. */
    @Synchronized
    fun complete(code: String, save: () -> Unit): Boolean {
        val current = active() ?: return false
        if (current.code != code || current.state !in setOf("pending", "authorizing")) return false
        save()
        current.apply { state = "connected"; submit = null; verificationUrl = ""; userCode = "" }
        return true
    }

    @Synchronized
    fun failed(code: String, invalidToken: Boolean) {
        if (isPending(code)) session!!.state = if (invalidToken) "invalid_token" else "failed"
    }

    @Synchronized
    fun cancel(code: String) {
        if (session?.code == code && session?.state != "connected") session = null
    }

    @Synchronized
    fun clear() { session = null }

    private fun active(): Session? {
        if (session?.let { clock() >= it.deadline } == true) session = null
        return session
    }

    private fun requireSession(code: String?): Session {
        val current = active() ?: throw IllegalStateException("Pairing ended")
        if (code == null || current.code != code) throw SecurityException("Invalid pairing")
        return current
    }

    private fun snapshot(current: Session) = Snapshot(current.state, (current.deadline - clock()).coerceAtLeast(0),
        current.provider.id, current.verificationUrl, current.userCode)

    companion object {
        const val DURATION = 10 * 60 * 1000L
        private val instance by lazy { CalendarAccountPairing() }
        @JvmStatic fun get(): CalendarAccountPairing = instance
    }
}
