package com.mayt3n.glasschat.network

import com.mayt3n.glasschat.auth.ChatGPTSession
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class ModelOption(val slug: String, val displayName: String)
data class PromptMessage(val role: String, val content: String)

object ResponsesClient {
    fun listModels(session: ChatGPTSession): List<ModelOption> {
        val connection = URL("https://api.openai.com/v1/models").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException(apiError(code, body))
            val root = JSONObject(body)
            val models = root.optJSONArray("models") ?: root.optJSONArray("data") ?: JSONArray()
            val output = mutableListOf<ModelOption>()
            for (i in 0 until models.length()) {
                val item = models.optJSONObject(i) ?: continue
                if (item.has("visibility") && item.optString("visibility") != "list") continue
                val slug = item.optString("slug").ifBlank { item.optString("id") }
                if (slug.isBlank()) continue
                val name = item.optString("display_name").ifBlank { item.optString("name") }.ifBlank { slug }
                output.add(ModelOption(slug, name))
            }
            return output
        } finally {
            connection.disconnect()
        }
    }

    fun streamReply(
        session: ChatGPTSession,
        model: String,
        history: List<PromptMessage>,
        onDelta: (String) -> Unit
    ) {
        val input = JSONArray()
        history.takeLast(24).forEach { message ->
            input.put(JSONObject().put("role", message.role).put("content", message.content))
        }
        val payload = JSONObject()
            .put("model", model)
            .put("input", input)
            .put("store", false)
            .put("stream", true)

        val connection = URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "text/event-stream")
        try {
            connection.outputStream.use {
                it.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw IllegalStateException(apiError(code, body))
            }

            var completed = false
            var eventName = ""
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    when {
                        line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            val data = line.removePrefix("data:").trim()
                            if (data.isBlank() || data == "[DONE]") return@forEach
                            val event = try { JSONObject(data) } catch (_: Exception) { return@forEach }
                            val type = event.optString("type").ifBlank { eventName }
                            when (type) {
                                "response.output_text.delta" -> {
                                    val delta = event.optString("delta")
                                    if (delta.isNotEmpty()) onDelta(delta)
                                }
                                "response.completed" -> completed = true
                                "response.failed" -> {
                                    val response = event.optJSONObject("response")
                                    val error = response?.optJSONObject("error")
                                    val codeValue = error?.optString("code").orEmpty()
                                    val detail = error?.optString("message").orEmpty()
                                    val message = when (codeValue) {
                                        "subscription_sharing_usage_limit_exceeded",
                                        "subscription_sharing_usage_unavailable" ->
                                            "سهمیه استفاده از پلن ChatGPT برای این اپ در دسترس نیست. تنظیمات Usage حساب را بررسی کن."
                                        else -> detail.ifBlank { "پاسخ مدل با خطا متوقف شد." }
                                    }
                                    throw IllegalStateException(message)
                                }
                                "response.incomplete" -> throw IllegalStateException("پاسخ مدل کامل نشد.")
                                "error" -> {
                                    val err = event.optJSONObject("error")
                                    throw IllegalStateException(err?.optString("message").orEmpty().ifBlank { "خطای ارتباط با مدل." })
                                }
                            }
                        }
                        line.isBlank() -> eventName = ""
                    }
                }
            }
            if (!completed) throw IllegalStateException("ارتباط پیش از تکمیل پاسخ قطع شد. دوباره تلاش کن.")
        } finally {
            connection.disconnect()
        }
    }

    private fun apiError(code: Int, body: String): String {
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        val error = json?.optJSONObject("error")
        val message = error?.optString("message").orEmpty()
        val detail = json?.optString("detail").orEmpty()
        return when {
            code == 401 -> "نشست ChatGPT نیاز به ورود دوباره دارد."
            code == 403 -> message.ifBlank { detail }.ifBlank { "این درخواست برای حساب یا منطقه فعلی مجاز نیست." }
            code == 429 -> "محدودیت استفاده فعال شده است. بعداً دوباره امتحان کن."
            message.isNotBlank() -> message
            detail.isNotBlank() -> detail
            else -> "خطای ارتباط با OpenAI (کد $code)."
        }
    }
}