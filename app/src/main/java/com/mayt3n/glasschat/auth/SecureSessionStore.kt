package com.mayt3n.glasschat.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("glasschat_vault", Context.MODE_PRIVATE)
    private val alias = "glasschat.session.aes.v1"

    @Synchronized
    fun write(session: ChatGPTSession) {
        val payload = JSONObject()
            .put("clientId", session.clientId)
            .put("subject", session.subject)
            .put("email", session.email)
            .put("name", session.name)
            .put("picture", session.picture)
            .put("idToken", session.idToken)
            .put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken)
            .put("expiresAt", session.expiresAt)
            .put("scopes", JSONArray(session.scopes.toList()))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(payload.toString().toByteArray(StandardCharsets.UTF_8))
        prefs.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized
    fun read(): ChatGPTSession? {
        val ivText = prefs.getString("iv", null) ?: return null
        val dataText = prefs.getString("data", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            val encrypted = Base64.decode(dataText, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(encrypted), StandardCharsets.UTF_8))
            val scopesJson = json.optJSONArray("scopes") ?: JSONArray()
            val scopes = (0 until scopesJson.length()).map { scopesJson.optString(it) }.filter { it.isNotBlank() }.toSet()
            ChatGPTSession(
                clientId = json.getString("clientId"),
                subject = json.getString("subject"),
                email = json.optString("email"),
                name = json.optString("name"),
                picture = json.optString("picture"),
                idToken = json.getString("idToken"),
                accessToken = json.getString("accessToken"),
                refreshToken = json.getString("refreshToken"),
                expiresAt = json.optLong("expiresAt"),
                scopes = scopes
            )
        } catch (_: Exception) {
            clear()
            null
        }
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        } catch (_: Exception) {
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}