package com.fongmi.android.tv.ui.calendar

import android.text.InputType
import android.os.SystemClock
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.fongmi.android.tv.App
import com.fongmi.android.tv.R
import com.fongmi.android.tv.api.TmdbLibraryApi
import com.fongmi.android.tv.calendar.BangumiAccountApi
import com.fongmi.android.tv.calendar.CalendarAccountHttpException
import com.fongmi.android.tv.calendar.CalendarAccountPairing
import com.fongmi.android.tv.calendar.CalendarAccountSession
import com.fongmi.android.tv.calendar.CalendarAccountStore
import com.fongmi.android.tv.calendar.CalendarAccountToken
import com.fongmi.android.tv.calendar.CalendarDeviceCode
import com.fongmi.android.tv.calendar.CalendarFollow
import com.fongmi.android.tv.calendar.CalendarProvider
import com.fongmi.android.tv.calendar.TraktAccountApi
import com.fongmi.android.tv.server.Server
import com.fongmi.android.tv.ui.custom.JetStreamDialogDecor
import com.fongmi.android.tv.utils.Notify
import com.fongmi.android.tv.utils.QRCode
import com.fongmi.android.tv.utils.ResUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Foreground TV authorization; credentials never enter favorites or source configuration. */
internal class CalendarAccountDialogs(private val activity: AppCompatActivity,
    private val changed: (Boolean) -> Unit, private val busy: (Boolean) -> Unit) {
    private val tag = Any()
    private var generation = 0
    private var closed = false
    private var authDialog: AlertDialog? = null
    private var polling: Runnable? = null
    private var pairingCode: String? = null
    private var pairingTimeout: Runnable? = null

    fun show(provider: CalendarProvider) {
        if (provider == CalendarProvider.TMDB) return
        val connected = CalendarAccountStore.token(provider) != null
        val actions = buildList {
            if (connected) add("sync" to R.string.calendar_sync)
            add("connect" to if (connected) R.string.calendar_reconnect else R.string.calendar_connect)
            if (provider == CalendarProvider.TRAKT) add("client" to R.string.calendar_trakt_client_id)
            if (connected) add("disconnect" to R.string.calendar_disconnect)
        }
        JetStreamDialogDecor.tintButtons(MaterialAlertDialogBuilder(activity).setTitle(providerName(provider))
            .setItems(actions.map { activity.getString(it.second) }.toTypedArray()) { _, index ->
                when (actions[index].first) {
                    "connect" -> connect(provider)
                    "sync" -> changed(true)
                    "client" -> { cancel(); startPhone(CalendarProvider.TRAKT) }
                    "disconnect" -> { cancel(); CalendarAccountStore.disconnect(provider); changed(false) }
                }
            }.setNegativeButton(R.string.dialog_negative, null).show())
    }

    private fun inputTrakt() {
        input(R.string.calendar_trakt_client_id, CalendarAccountStore.traktClientId, false) { value ->
            if (value.isNotBlank()) startTrakt(value)
        }
    }

    private fun connect(provider: CalendarProvider) {
        cancel()
        if (provider == CalendarProvider.TRAKT) {
            if (CalendarAccountStore.traktClientId.isBlank()) startPhone(provider)
            else startTrakt(CalendarAccountStore.traktClientId)
        } else startPhone(provider)
    }

    private fun startPhone(provider: CalendarProvider) {
        val manual = { if (provider == CalendarProvider.TRAKT) inputTrakt() else inputBangumi() }
        Server.get().start()
        if (!Server.get().isRunning) { manual(); return }
        val ticket = generation
        val builder = MaterialAlertDialogBuilder(activity).setTitle(providerName(provider))
        val status = TextView(builder.context).apply {
            setPadding(0, ResUtil.dp2px(12), 0, 0)
            setText(R.string.calendar_phone_waiting)
        }
        val pairing = CalendarAccountPairing.get()
        val code = pairing.start(provider) { value ->
            App.post {
                val activeCode = pairingCode
                if (activeCode != null && current(ticket) && pairing.isPending(activeCode)) {
                    status.setText(R.string.calendar_phone_checking)
                    if (provider == CalendarProvider.TRAKT) startTrakt(value, activeCode, status)
                    else connectBangumi(value, ticket, activeCode, status)
                }
            }
        }
        pairingCode = code
        showQr(builder, Server.get().address + "/account.html#" + code,
            activity.getString(if (provider == CalendarProvider.TRAKT) R.string.calendar_trakt_client_help else R.string.calendar_phone_help),
            status, manual)
        pairingTimeout = Runnable { if (current(ticket)) fail(R.string.calendar_auth_expired) }
            .also { App.post(it, CalendarAccountPairing.DURATION) }
    }

    private fun showQr(builder: MaterialAlertDialogBuilder, url: String, help: String, status: TextView, manual: (() -> Unit)? = null) {
        val content = LinearLayout(builder.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = ResUtil.dp2px(24)
            setPadding(pad, ResUtil.dp2px(8), pad, ResUtil.dp2px(8))
            addView(ImageView(builder.context).apply {
                contentDescription = activity.getString(R.string.calendar_phone_connect)
                setImageBitmap(QRCode.getLightBitmap(url, 180, 4))
            }, LinearLayout.LayoutParams(ResUtil.dp2px(180), ResUtil.dp2px(180)))
            addView(LinearLayout(builder.context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(builder.context).apply {
                    textSize = 16f
                    text = help
                })
                addView(status)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ResUtil.dp2px(20)
            })
        }
        manual?.let { builder.setNeutralButton(R.string.calendar_phone_manual) { _, _ -> cancel(); it() } }
        authDialog?.setOnDismissListener(null)
        authDialog?.dismiss()
        val dialog = builder.setView(content)
            .setNegativeButton(R.string.dialog_negative, null).show()
        JetStreamDialogDecor.tintButtons(dialog)
        authDialog = dialog.also { it.setOnDismissListener { cancel() } }
    }

    private fun inputBangumi() {
        input(R.string.calendar_bangumi_token, "", true, R.string.calendar_bangumi_token_help) { value ->
            if (value.isBlank()) return@input
            connectBangumi(value, generation)
        }
    }

    private fun connectBangumi(value: String, ticket: Int, code: String? = null, status: TextView? = null) {
        busy(true)
        val token = CalendarAccountToken(value)
        val pairing = CalendarAccountPairing.get()
        fun failed(error: Exception) {
            if (!current(ticket)) return
            if (code == null) { fail(); return }
            val invalid = (error as? CalendarAccountHttpException)?.status in listOf(401, 403)
            pairing.failed(code, invalid)
            status?.setText(R.string.calendar_phone_failed)
            busy(false)
        }
        BangumiAccountApi.following(token, tag, object : TmdbLibraryApi.Listener<List<CalendarFollow>> {
            override fun onSuccess(follows: List<CalendarFollow>) {
                if (!current(ticket)) return
                try {
                    val save = {
                        CalendarAccountStore.saveToken(CalendarProvider.BANGUMI, token)
                        CalendarAccountStore.saveFollows(CalendarProvider.BANGUMI, follows)
                    }
                    if (code == null) save()
                    else if (!pairing.complete(code, save)) return
                    cancel()
                    changed(true)
                } catch (error: Exception) { failed(error) }
            }
            override fun onError(error: Exception) = failed(error)
        })
    }

    private fun startTrakt(clientId: String, phoneCode: String? = null, status: TextView? = null) {
        val ticket = generation
        busy(true)
        TraktAccountApi.deviceCode(clientId, tag, object : TmdbLibraryApi.Listener<CalendarDeviceCode> {
            override fun onSuccess(value: CalendarDeviceCode) {
                if (!current(ticket)) return
                if (phoneCode != null && !CalendarAccountPairing.get().authorize(phoneCode, value)) return
                val deadline = SystemClock.elapsedRealtime() + value.expiresIn * 1000L
                val builder = MaterialAlertDialogBuilder(activity).setTitle("Trakt")
                val userCode = TextView(builder.context).apply {
                    text = value.userCode
                    textSize = 26f
                    setPadding(0, ResUtil.dp2px(12), 0, 0)
                }
                showQr(builder, value.verificationUrl,
                    activity.getString(R.string.calendar_trakt_scan, value.verificationUrl), userCode)
                poll(value, deadline, value.interval.coerceAtLeast(1), ticket, clientId, phoneCode)
            }
            override fun onError(error: Exception) {
                if (!current(ticket)) return
                if (phoneCode == null) { fail(); return }
                CalendarAccountPairing.get().failed(phoneCode, false)
                status?.setText(R.string.calendar_phone_failed)
                busy(false)
            }
        })
    }

    private fun poll(code: CalendarDeviceCode, deadline: Long, interval: Int, ticket: Int, clientId: String, phoneCode: String?) {
        polling = Runnable {
            if (!current(ticket)) return@Runnable
            if (SystemClock.elapsedRealtime() >= deadline) { fail(R.string.calendar_auth_expired); return@Runnable }
            TraktAccountApi.deviceToken(clientId, code.deviceCode, tag,
                object : TmdbLibraryApi.Listener<CalendarAccountToken> {
                    override fun onSuccess(value: CalendarAccountToken) {
                        if (!current(ticket)) return
                        if (SystemClock.elapsedRealtime() >= deadline) { fail(R.string.calendar_auth_expired); return }
                        try {
                            val save = {
                                CalendarAccountStore.saveToken(CalendarProvider.TRAKT, value)
                                CalendarAccountStore.traktClientId = clientId
                                CalendarAccountStore.saveFollows(CalendarProvider.TRAKT, emptyList())
                            }
                            if (phoneCode == null) save()
                            else if (!CalendarAccountPairing.get().complete(phoneCode, save)) return
                            cancel()
                            changed(true)
                        } catch (_: Exception) { fail() }
                    }
                    override fun onError(error: Exception) {
                        if (!current(ticket)) return
                        val http = error as? CalendarAccountHttpException
                        when (http?.status) {
                            400 -> poll(code, deadline, interval, ticket, clientId, phoneCode)
                            429 -> {
                                val delay = maxOf(interval.toLong() + 5, http.retryAfterSeconds)
                                if (delay >= (deadline - SystemClock.elapsedRealtime() + 999) / 1000) fail(R.string.calendar_auth_expired)
                                else poll(code, deadline, delay.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), ticket, clientId, phoneCode)
                            }
                            410 -> fail(R.string.calendar_auth_expired)
                            else -> fail()
                        }
                    }
                })
        }.also { App.post(it, interval * 1000L) }
    }

    fun setFollowing(follow: CalendarFollow, following: Boolean) {
        cancel()
        val ticket = generation
        CalendarAccountStore.invalidateSync(follow.provider)
        busy(true)
        CalendarAccountSession.withToken(follow.provider, object : TmdbLibraryApi.Listener<CalendarAccountToken> {
            override fun onSuccess(value: CalendarAccountToken) {
                if (!current(ticket)) return
                val callback = object : TmdbLibraryApi.Listener<Boolean> {
                    override fun onSuccess(value: Boolean) {
                        if (!current(ticket)) return
                        busy(false)
                        if (value) {
                            val follows = CalendarAccountStore.follows(follow.provider).filter { it.remoteId != follow.remoteId }
                            CalendarAccountStore.saveFollows(follow.provider, if (following) follows + follow else follows)
                            changed(true)
                        } else Notify.show(R.string.calendar_action_failed)
                    }
                    override fun onError(error: Exception) { if (current(ticket)) fail(R.string.calendar_action_failed) }
                }
                when (follow.provider) {
                    CalendarProvider.TRAKT -> TraktAccountApi.setFollowing(follow, following, CalendarAccountStore.traktClientId, value, tag, callback)
                    CalendarProvider.BANGUMI -> BangumiAccountApi.setFollowing(follow, following, value, tag, callback)
                    else -> busy(false)
                }
            }
            override fun onError(error: Exception) { if (current(ticket)) fail() }
        })
    }

    private fun input(title: Int, value: String, secret: Boolean, message: Int? = null, done: (String) -> Unit) {
        val input = EditText(activity).apply {
            setSingleLine(true); setText(value); setSelection(value.length)
            inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            val pad = ResUtil.dp2px(24); setPadding(pad, pad / 2, pad, pad / 2)
            minHeight = ResUtil.dp2px(56)
        }
        val builder = MaterialAlertDialogBuilder(activity).setTitle(title).setView(input)
            .setPositiveButton(R.string.dialog_positive) { _, _ -> done(input.text.toString().trim()) }
            .setNegativeButton(R.string.dialog_negative, null)
        message?.let { builder.setMessage(it) }
        JetStreamDialogDecor.tintButtons(builder.show())
    }

    private fun fail(message: Int = R.string.calendar_auth_failed) {
        cancel()
        Notify.show(message)
    }
    private fun providerName(provider: CalendarProvider) = if (provider == CalendarProvider.TRAKT) "Trakt" else "Bangumi"
    private fun current(ticket: Int) = !closed && ticket == generation && !activity.isFinishing && !activity.isDestroyed
    fun cancel() {
        generation++
        polling?.let { App.removeCallbacks(it) }; polling = null
        pairingTimeout?.let { App.removeCallbacks(it) }; pairingTimeout = null
        pairingCode?.let { CalendarAccountPairing.get().cancel(it) }; pairingCode = null
        TraktAccountApi.cancel(tag); BangumiAccountApi.cancel(tag)
        authDialog?.setOnDismissListener(null); authDialog?.dismiss(); authDialog = null
        busy(false)
    }
    fun close() { closed = true; cancel() }
}
