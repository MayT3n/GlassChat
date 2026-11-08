package com.mayt3n.glasschat.chat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class StoredChatMessage(val role: String, val text: String)

data class StoredConversation(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "گفت‌وگوی جدید",
    var updatedAt: Long = System.currentTimeMillis(),
    val messages: MutableList<StoredChatMessage> = mutableListOf()
)

class ConversationStore(context: Context) {
    private val prefs = context.getSharedPreferences("glasschat_conversations", Context.MODE_PRIVATE)
    private val keyAlias = "glasschat.conversations.aes.v1"

    @Synchronized
    fun read(): MutableList<StoredConversation> {
        val ivText = prefs.getString("iv", null) ?: return mutableListOf()
        val dataText = prefs.getString("data", null) ?: return mutableListOf()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
            )
            val json = JSONObject(String(
                cipher.doFinal(Base64.decode(dataText, Base64.NO_WRAP)),
                StandardCharsets.UTF_8
            ))
            val chats = json.optJSONArray("chats") ?: JSONArray()
            MutableList(chats.length()) { index ->
                val item = chats.getJSONObject(index)
                val messages = item.optJSONArray("messages") ?: JSONArray()
                val rows = MutableList(messages.length()) { messageIndex ->
                    val message = messages.getJSONObject(messageIndex)
                    StoredChatMessage(message.optString("role"), message.optString("text"))
                }
                StoredConversation(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    title = item.optString("title").ifBlank { "گفت‌وگوی جدید" },
                    updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
                    messages = rows
                )
            }.sortedByDescending { it.updatedAt }.toMutableList()
        } catch (_: Exception) {
            clear()
            mutableListOf()
        }
    }

    @Synchronized
    fun write(chats: List<StoredConversation>) {
        val rows = JSONArray()
        chats.sortedByDescending { it.updatedAt }.take(50).forEach { chat ->
            val messages = JSONArray()
            chat.messages.takeLast(80).forEach { message ->
                messages.put(JSONObject().put("role", message.role).put("text", message.text))
            }
            rows.put(
                JSONObject()
                    .put("id", chat.id)
                    .put("title", chat.title)
                    .put("updatedAt", chat.updatedAt)
                    .put("messages", messages)
            )
        }
        val payload = JSONObject().put("chats", rows).toString().toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(payload)
        prefs.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        } catch (_: Exception) {
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}