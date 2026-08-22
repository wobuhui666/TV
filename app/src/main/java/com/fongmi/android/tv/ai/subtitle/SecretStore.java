package com.fongmi.android.tv.ai.subtitle;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.github.catvod.utils.Prefers;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecretStore {
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "fongmi_ai_subtitle_api_key";
    private static final String PREF_API_KEY = "ai_subtitle_api_key_cipher";
    private static final String PREF_MTRAN_TOKEN = "ai_subtitle_mtran_token_cipher";
    private static final String PREF_AI_SKIP_TOKEN = "ai_skip_token_cipher";

    private SecretStore() {
    }

    public static synchronized void putApiKey(String value) {
        put(PREF_API_KEY, value);
    }

    public static synchronized String getApiKey() {
        return get(PREF_API_KEY);
    }

    public static synchronized void putMTranToken(String value) {
        put(PREF_MTRAN_TOKEN, value);
    }

    public static synchronized String getMTranToken() {
        return get(PREF_MTRAN_TOKEN);
    }

    public static synchronized void putAiSkipToken(String value) {
        put(PREF_AI_SKIP_TOKEN, value);
    }

    public static synchronized String getAiSkipToken() {
        return get(PREF_AI_SKIP_TOKEN);
    }

    private static void put(String pref, String value) {
        if (value == null || value.isBlank()) {
            Prefers.remove(pref);
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[1 + cipher.getIV().length + encrypted.length];
            payload[0] = (byte) cipher.getIV().length;
            System.arraycopy(cipher.getIV(), 0, payload, 1, cipher.getIV().length);
            System.arraycopy(encrypted, 0, payload, 1 + cipher.getIV().length, encrypted.length);
            Prefers.put(pref, Base64.encodeToString(payload, Base64.NO_WRAP));
        } catch (Exception ignored) {
            Prefers.remove(pref);
        }
    }

    private static String get(String pref) {
        String encoded = Prefers.getString(pref, "");
        if (encoded.isEmpty()) return "";
        try {
            byte[] payload = Base64.decode(encoded, Base64.NO_WRAP);
            int ivLength = payload[0] & 0xff;
            if (ivLength < 12 || payload.length <= ivLength + 1) return "";
            byte[] iv = new byte[ivLength];
            byte[] encrypted = new byte[payload.length - 1 - ivLength];
            System.arraycopy(payload, 1, iv, 0, ivLength);
            System.arraycopy(payload, 1 + ivLength, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance(STORE);
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
