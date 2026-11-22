package com.fongmi.android.tv.calendar

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.fongmi.android.tv.App
import com.fongmi.android.tv.BuildConfig
import com.google.gson.Gson
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-only account credentials; deliberately separate from favorites/WebDAV backups. */
object CalendarAccountStore {
    private const val KEY_ALIAS = "calendar-account-token-v1"
    private val prefs get() = App.get().getSharedPreferences("calendar_accounts", Context.MODE_PRIVATE)
    private val gson = Gson()
    private data class TokenSnapshot(val encoded: String?, val token: CalendarAccountToken?)
    private data class FollowSnapshot(val encoded: String, val follows: List<CalendarFollow>)
    private val tokenSnapshots = mutableMapOf<CalendarProvider, TokenSnapshot>()
    private val followSnapshots = mutableMapOf<CalendarProvider, FollowSnapshot>()

    var provider: CalendarProvider
        get() = CalendarProvider.fromId(prefs.getString("provider", "tmdb"))
        set(value) { prefs.edit().putString("provider", value.id).apply() }

    var traktClientId: String
        get() = prefs.getString("trakt_client_id", "").orEmpty().ifBlank { BuildConfig.TRAKT_CLIENT_ID }
        set(value) { prefs.edit().putString("trakt_client_id", value.trim()).apply() }

    @Synchronized fun token(provider: CalendarProvider): CalendarAccountToken? {
        val encoded = prefs.getString("${provider.id}_token", null)
        tokenSnapshots[provider]?.takeIf { it.encoded == encoded }?.let { return it.token }
        if (encoded == null) {
            tokenSnapshots[provider] = TokenSnapshot(null, null)
            return null
        }
        return try {
            val parts = encoded.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            val token = gson.fromJson(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8), CalendarAccountToken::class.java)
                ?.takeIf { it.accessToken.isNotBlank() }
            tokenSnapshots[provider] = TokenSnapshot(encoded, token)
            token
        } catch (_: Exception) { null }
    }

    @Synchronized fun saveToken(provider: CalendarProvider, token: CalendarAccountToken) {
        require(token.accessToken.isNotBlank())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(gson.toJson(token).toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("${provider.id}_token", encoded).commit()) { "Cannot save account credentials" }
        tokenSnapshots[provider] = TokenSnapshot(encoded, token)
    }

    @Synchronized fun follows(provider: CalendarProvider): List<CalendarFollow> {
        val encoded = prefs.getString("${provider.id}_follows", "[]").orEmpty()
        followSnapshots[provider]?.takeIf { it.encoded == encoded }?.let { return it.follows }
        val follows = try {
            gson.fromJson(encoded, Array<CalendarFollow>::class.java)
                ?.filter { it.provider == provider && it.remoteId > 0 && it.media.id.isNotBlank() }.orEmpty()
        } catch (_: Exception) { emptyList() }
        val snapshot = java.util.Collections.unmodifiableList(follows.toList())
        followSnapshots[provider] = FollowSnapshot(encoded, snapshot)
        return snapshot
    }

    fun saveFollows(provider: CalendarProvider, follows: List<CalendarFollow>) {
        prefs.edit().putString("${provider.id}_follows", gson.toJson(follows))
            .putLong("${provider.id}_synced", System.currentTimeMillis()).apply()
    }

    fun lastSync(provider: CalendarProvider): Long = prefs.getLong("${provider.id}_synced", 0)

    /** A canceled write can already have reached the server; the next view must import again. */
    fun invalidateSync(provider: CalendarProvider) {
        prefs.edit().remove("${provider.id}_synced").apply()
    }

    @Synchronized fun disconnect(provider: CalendarProvider) {
        prefs.edit().remove("${provider.id}_token").remove("${provider.id}_follows")
            .remove("${provider.id}_synced").apply()
        tokenSnapshots.remove(provider)
        followSnapshots.remove(provider)
    }

    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
