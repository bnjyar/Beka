package dev.beka.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom

/** The OpenRouter login could not be completed (bad callback, lost session, rejected code). */
class OAuthException(message: String) : Exception(message)

/** OpenRouter's OAuth PKCE flow: the result is a user-scoped API key. */
object OpenRouterOAuth {
    const val CALLBACK = "beka://oauth/openrouter"
    private const val PREF_VERIFIER = "openrouter_verifier"

    /** Throws [android.content.ActivityNotFoundException] when no browser can open the login page. */
    fun start(context: Context) {
        val verifier = randomVerifier()
        context.getSharedPreferences("oauth", Context.MODE_PRIVATE).edit().putString(PREF_VERIFIER, verifier).apply()
        val url = Uri.parse("https://openrouter.ai/auth").buildUpon()
            .appendQueryParameter("callback_url", CALLBACK)
            .appendQueryParameter("code_challenge", challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun isCallback(uri: Uri?): Boolean = uri?.scheme == "beka" && uri.host == "oauth" && uri.path == "/openrouter"

    /** Exchanges the callback code for an API key. */
    suspend fun complete(context: Context, uri: Uri, http: OkHttpClient): String = withContext(Dispatchers.IO) {
        val code = uri.getQueryParameter("code") ?: throw OAuthException("Callback tanpa code")
        val prefs = context.getSharedPreferences("oauth", Context.MODE_PRIVATE)
        val verifier = prefs.getString(PREF_VERIFIER, null) ?: throw OAuthException("Sesi login tidak ditemukan, ulangi login")
        val body = buildJsonObject {
            put("code", code)
            put("code_verifier", verifier)
            put("code_challenge_method", "S256")
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("https://openrouter.ai/api/v1/auth/keys").post(body).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw OAuthException("OpenRouter HTTP ${response.code}: ${text.take(300)}")
            prefs.edit().remove(PREF_VERIFIER).apply()
            Json.parseToJsonElement(text).jsonObject["key"]?.jsonPrimitive?.content ?: throw OAuthException("Respons tanpa key")
        }
    }

    private fun randomVerifier(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
