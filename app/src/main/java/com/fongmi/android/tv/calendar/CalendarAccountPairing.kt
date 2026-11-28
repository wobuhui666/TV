package com.fongmi.android.tv.calendar

import java.security.SecureRandom

/** A foreground TV grants one short-lived phone connection. Account tokens are never retained here. */
class CalendarAccountPairing(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Snapshot(val state: String, val remainingMillis: Long)

    private class Session(val code: String, val deadline: Long, var submit: ((String) -> Unit)?) {
        var state = "ready"
    }

    private val random = SecureRandom()
    private var session: Session? = null

    @Synchronized
    fun start(submit: (String) -> Unit): String {
        val code = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        session = Session(code, clock() + DURATION, submit)
        return code
    }

    @Synchronized
    fun status(code: String?): Snapshot = snapshot(requireSession(code))

    /** The callback must dispatch to the main thread and check isPending before using the token. */
    @Synchronized
    fun submit(code: String?, token: String): Snapshot {
        val current = requireSession(code)
        require(token.length in 1..2048 && token.all { it in '!'..'~' }) { "Invalid token" }
        if (current.state == "pending" || current.state == "connected") return snapshot(current)
        current.state = "pending"
        current.submit?.invoke(token)
        return snapshot(current)
    }

    @Synchronized
    fun isPending(code: String): Boolean = active()?.let { it.code == code && it.state == "pending" } == true

    /** Cancellation and expiry are checked atomically with saving the account. */
    @Synchronized
    fun complete(code: String, save: () -> Unit): Boolean {
        if (!isPending(code)) return false
        save()
        session!!.apply { state = "connected"; submit = null }
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

    private fun snapshot(current: Session) = Snapshot(current.state, (current.deadline - clock()).coerceAtLeast(0))

    companion object {
        const val DURATION = 10 * 60 * 1000L
        private val instance by lazy { CalendarAccountPairing() }
        @JvmStatic fun get(): CalendarAccountPairing = instance
    }
}
