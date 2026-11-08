package com.mayt3n.glasschat.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

data class ChatGPTSession(
    val clientId: String,
    val subject: String,
    val email: String,
    val name: String,
    val picture: String,
    val idToken: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val scopes: Set<String>
) {
    fun canUsePlan(): Boolean =
        scopes.contains("chatgpt.tokens.use.direct") && scopes.contains("resource.invoke")
}

class ChatGPTAuth(private val activity: Activity) {
    private val store = SecureSessionStore(activity)
    private val devicePrefs = activity.getSharedPreferences("glasschat_device", Context.MODE_PRIVATE)

    fun savedSession(): ChatGPTSession? = store.read()

    fun signIn(): ChatGPTSession {
        checkAuthDns()
        val previousSession = store.read()
        val registeredClientId = devicePrefs.getString("issued_client_id", null) ?: previousSession?.clientId
        val expectedSubject = previousSession?.subject ?: devicePrefs.getString("registered_subject", null)
        val previousEmail = previousSession?.email ?: devicePrefs.getString("registered_email", null).orEmpty()
        val isFirstRegistration = registeredClientId.isNullOrBlank()
        val requestedClientId = if (isFirstRegistration) "dynamic_agent_client" else registeredClientId!!
        val hostId = devicePrefs.getString("host_id", null) ?: "urn:uuid:${UUID.randomUUID()}".also {
            devicePrefs.edit().putString("host_id", it).apply()
        }

        val verifier = randomUrlSafe(48)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.UTF_8)))
        val state = randomUrlSafe(32)
        val nonce = randomUrlSafe(32)

        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        server.soTimeout = 240_000
        val redirectUri = "http://127.0.0.1:${server.localPort}/auth/callback"

        try {
            val params = linkedMapOf(
                "client_id" to requestedClientId,
                "ext_agent_host_id" to hostId,
                "response_type" to "code",
                "redirect_uri" to redirectUri,
                "scope" to "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
                "resource" to "https://api.openai.com/v1",
                "state" to state,
                "nonce" to nonce,
                "code_challenge_method" to "S256",
                "code_challenge" to challenge
            )
            if (isFirstRegistration) {
                params["agent_name_hint"] = "GlassChat"
            } else {
                if (previousEmail.isNotBlank()) params["login_hint"] = previousEmail
                if (!previousSession?.idToken.isNullOrBlank()) params["id_token_hint"] = previousSession!!.idToken
            }
            val authUrl = "https://auth.openai.com/api/accounts/authorize?" +
                params.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }

            openSecureBrowser(authUrl)
            val callback = acceptCallback(server)
            val returnedState = callback.optString("state")
            if (!constantTimeEquals(state, returnedState)) {
                throw SecurityException("پاسخ ورود معتبر نبود. دوباره تلاش کن.")
            }
            val error = callback.optString("error")
            if (error.isNotBlank()) {
                throw IllegalStateException(callback.optString("error_description", error))
            }
            val code = callback.optString("code")
            val callbackClientId = callback.optString("client_id")
            val clientId = when {
                isFirstRegistration && callbackClientId.isBlank() ->
                    throw IllegalStateException("ثبت اولیه کامل نشد؛ شناسه کلاینت از OpenAI برنگشت.")
                !isFirstRegistration && callbackClientId.isNotBlank() && callbackClientId != requestedClientId ->
                    throw SecurityException("شناسه کلاینت برگشتی با حساب ثبت‌شده یکسان نیست.")
                isFirstRegistration -> callbackClientId
                else -> requestedClientId
            }
            if (code.isBlank()) throw IllegalStateException("کد ورود از مرورگر دریافت نشد.")

            val token = postForm(
                "https://auth.openai.com/api/accounts/oauth/token",
                mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to clientId,
                    "code" to code,
                    "code_verifier" to verifier,
                    "redirect_uri" to redirectUri,
                    "resource" to "https://api.openai.com/v1"
                )
            )

            val idToken = token.optString("id_token")
            if (idToken.isBlank()) throw SecurityException("توکن هویت از OpenAI دریافت نشد.")
            val identity = verifyIdentity(idToken, clientId, nonce)
            if (!expectedSubject.isNullOrBlank() && expectedSubject != identity.subject) {
                throw SecurityException("حساب برگشتی با حساب قبلی این اتصال مطابقت ندارد.")
            }
            val scopes = token.optString("scope").split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
            val access = token.optString("access_token")
            val refresh = token.optString("refresh_token")
            if (access.isBlank() || refresh.isBlank()) {
                throw IllegalStateException("توکن‌های لازم دریافت نشدند.")
            }

            val session = ChatGPTSession(
                clientId = clientId,
                subject = identity.subject,
                email = identity.email,
                name = identity.name,
                picture = identity.picture,
                idToken = idToken,
                accessToken = access,
                refreshToken = refresh,
                expiresAt = System.currentTimeMillis() / 1000L + token.optLong("expires_in", 3600L),
                scopes = scopes
            )
            store.write(session)
            devicePrefs.edit()
                .putString("issued_client_id", clientId)
                .putString("registered_subject", identity.subject)
                .putString("registered_email", identity.email)
                .apply()
            return session
        } finally {
            try { server.close() } catch (_: Exception) { }
        }
    }

    private fun checkAuthDns() {
        try {
            val addresses = InetAddress.getAllByName("auth.openai.com")
            if (addresses.isEmpty()) throw UnknownHostException("No DNS records returned")
        } catch (e: UnknownHostException) {
            throw IllegalStateException(
                "DNS گوشی نتوانست auth.openai.com را پیدا کند. این مشکل از رمز یا حساب نیست. در Chrome همین آدرس را باز کن؛ سپس شبکه را عوض کن یا تنظیم Private DNS گوشی را بررسی کن و دوباره تلاش کن.",
                e
            )
        }
    }

    fun validSessionOrRefresh(session: ChatGPTSession): ChatGPTSession {
        if (session.expiresAt > System.currentTimeMillis() / 1000L + 90L) return session

        val token = postForm(
            "https://auth.openai.com/api/accounts/oauth/token",
            mapOf(
                "grant_type" to "refresh_token",
                "client_id" to session.clientId,
                "refresh_token" to session.refreshToken,
                "resource" to "https://api.openai.com/v1"
            )
        )
        val refreshed = session.copy(
            idToken = token.optString("id_token").ifBlank { session.idToken },
            accessToken = token.optString("access_token").ifBlank { throw IllegalStateException("نشست ChatGPT منقضی شده است.") },
            refreshToken = token.optString("refresh_token").ifBlank { session.refreshToken },
            expiresAt = System.currentTimeMillis() / 1000L + token.optLong("expires_in", 3600L),
            scopes = token.optString("scope").takeIf { it.isNotBlank() }?.split(Regex("\\s+"))?.toSet() ?: session.scopes
        )
        store.write(refreshed)
        return refreshed
    }

    fun signOut(session: ChatGPTSession?) {
        if (session != null && session.refreshToken.isNotBlank()) {
            try {
                postForm(
                    "https://auth.openai.com/api/accounts/oauth/revoke",
                    mapOf(
                        "token" to session.refreshToken,
                        "token_type_hint" to "refresh_token",
                        "client_id" to session.clientId
                    )
                )
            } catch (_: Exception) {
                // Local credentials are still cleared if the network is unavailable.
            }
        }
        store.clear()
    }

    private fun openSecureBrowser(url: String) {
        val latch = CountDownLatch(1)
        var launchError: Exception? = null
        activity.runOnUiThread {
            try {
                val customTab = CustomTabsIntent.Builder()
                    .setToolbarColor(Color.rgb(15, 20, 34))
                    .setShowTitle(false)
                    .build()
                customTab.launchUrl(activity, Uri.parse(url))
            } catch (e: Exception) {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (fallback: Exception) {
                    launchError = fallback
                }
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(20, TimeUnit.SECONDS)) throw IllegalStateException("مرورگر باز نشد.")
        launchError?.let { throw IllegalStateException("مرورگر امنی برای ورود پیدا نشد.", it) }
    }

    private fun acceptCallback(server: ServerSocket): JSONObject {
        server.accept().use { socket ->
            socket.soTimeout = 20_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: throw IllegalStateException("پاسخی از مرورگر دریافت نشد.")
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
            }
            val pieces = requestLine.split(" ")
            if (pieces.size < 2 || !pieces[1].startsWith("/auth/callback")) {
                sendCallbackPage(socket.getOutputStream(), false)
                throw SecurityException("مسیر بازگشت ورود معتبر نبود.")
            }

            val uri = Uri.parse("http://127.0.0.1${pieces[1]}")
            val result = JSONObject()
            for (key in listOf("code", "state", "client_id", "error", "error_description")) {
                uri.getQueryParameter(key)?.let { result.put(key, it) }
            }
            sendCallbackPage(socket.getOutputStream(), result.has("code") && !result.has("error"))
            return result
        }
    }

    private fun sendCallbackPage(output: OutputStream, success: Boolean) {
        val message = if (success) {
            "ورود تأیید شد. به GlassChat برگردید."
        } else {
            "ورود کامل نشد. به GlassChat برگردید و دوباره تلاش کنید."
        }
        val body = """<!doctype html><html lang="fa"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><body style="margin:0;background:#0b1020;color:#f4f6ff;font:16px sans-serif;display:grid;place-items:center;min-height:100vh;text-align:center"><div style="padding:32px"><div style="font-size:36px">✦</div><h2>GlassChat</h2><p>${message}</p></div></body></html>"""
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private data class Identity(val subject: String, val email: String, val name: String, val picture: String)

    private fun verifyIdentity(idToken: String, clientId: String, expectedNonce: String): Identity {
        val parts = idToken.split(".")
        if (parts.size != 3) throw SecurityException("ساختار توکن هویت معتبر نیست.")
        val header = JSONObject(String(decodeBase64Url(parts[0]), StandardCharsets.UTF_8))
        if (header.optString("alg") != "RS256") throw SecurityException("الگوریتم امضای توکن پشتیبانی نمی‌شود.")
        val kid = header.optString("kid")
        if (kid.isBlank()) throw SecurityException("شناسه کلید امضای توکن وجود ندارد.")

        val discovery = getJson("https://auth.openai.com/.well-known/openid-configuration")
        val issuer = discovery.getString("issuer")
        val jwks = getJson(discovery.getString("jwks_uri"))
        val keys = jwks.getJSONArray("keys")
        var selected: JSONObject? = null
        for (i in 0 until keys.length()) {
            val key = keys.getJSONObject(i)
            if (key.optString("kid") == kid && key.optString("kty") == "RSA") {
                selected = key
                break
            }
        }
        val key = selected ?: throw SecurityException("کلید تأیید امضای OpenAI پیدا نشد.")
        val modulus = BigInteger(1, decodeBase64Url(key.getString("n")))
        val exponent = BigInteger(1, decodeBase64Url(key.getString("e")))
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII))
        if (!verifier.verify(decodeBase64Url(parts[2]))) {
            throw SecurityException("امضای توکن هویت معتبر نیست.")
        }

        val claims = JSONObject(String(decodeBase64Url(parts[1]), StandardCharsets.UTF_8))
        if (claims.optString("iss") != issuer) throw SecurityException("صادرکننده توکن معتبر نیست.")
        val audience = claims.opt("aud")
        val audienceMatches = when (audience) {
            is String -> audience == clientId
            is JSONArray -> (0 until audience.length()).any { audience.optString(it) == clientId }
            else -> false
        }
        if (!audienceMatches) throw SecurityException("این توکن برای GlassChat صادر نشده است.")
        if (claims.optLong("exp", 0L) <= System.currentTimeMillis() / 1000L) {
            throw SecurityException("توکن هویت منقضی شده است.")
        }
        if (!constantTimeEquals(expectedNonce, claims.optString("nonce"))) {
            throw SecurityException("تأیید nonce ناموفق بود.")
        }
        val subject = claims.optString("sub")
        if (subject.isBlank()) throw SecurityException("شناسه حساب در توکن وجود ندارد.")
        return Identity(
            subject = subject,
            email = claims.optString("email"),
            name = claims.optString("name").ifBlank { claims.optString("preferred_username") },
            picture = claims.optString("picture")
        )
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        return try {
            val response = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            JSONObject(response)
        } catch (e: UnknownHostException) {
            throw IllegalStateException("DNS نتوانست دامنه OpenAI را پیدا کند. اتصال اینترنت و Private DNS را بررسی کن.", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun postForm(url: String, values: Map<String, String>): JSONObject {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.setRequestProperty("Accept", "application/json")
        val body = values.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = try { JSONObject(response).optString("error_description").ifBlank { JSONObject(response).optString("error") } } catch (_: Exception) { response }
                throw IllegalStateException("OpenAI پاسخ ${code} داد: ${detail.take(220)}")
            }
            if (response.isBlank()) JSONObject() else JSONObject(response)
        } catch (e: UnknownHostException) {
            throw IllegalStateException("DNS نتوانست دامنه OpenAI را پیدا کند. اتصال اینترنت و Private DNS را بررسی کن.", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun randomUrlSafe(bytes: Int): String {
        val value = ByteArray(bytes)
        SecureRandom().nextBytes(value)
        return base64Url(value)
    }
    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun decodeBase64Url(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))
}