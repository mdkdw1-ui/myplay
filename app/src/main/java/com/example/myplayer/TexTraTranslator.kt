package com.example.myplayer

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * TexTra (みんなの自動翻訳) API
 * - 일→한 전용 엔진 (generalNT_ja_ko)
 * - OAuth2 client_credentials
 * - resultset.code == 0 이면 성공
 */
object TexTraTranslator {

    private const val TAG = "TexTraTranslator"
    private const val TOKEN_URL = "https://mt-auto-minhon-mlt.ucri.jgn-x.jp/oauth2/token.php"
    private const val API_URL = "https://mt-auto-minhon-mlt.ucri.jgn-x.jp/api/"

    // 토큰 캐시
    private var cachedToken: String? = null
    private var tokenExpiry: Long = 0L

    private suspend fun getToken(): String? = withContext(Dispatchers.IO) {
        // 캐시 유효 (만료 60초 전까지)
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiry - 60_000) {
            return@withContext cachedToken
        }

        try {
            val clientId = BuildConfig.TEXTRA_CLIENT_ID
            val clientSecret = BuildConfig.TEXTRA_CLIENT_SECRET
            if (clientId.isBlank() || clientSecret.isBlank()) {
                Log.e(TAG, "no credentials")
                return@withContext null
            }

            val bodyStr = "grant_type=client_credentials" +
                    "&client_id=${URLEncoder.encode(clientId, "UTF-8")}" +
                    "&client_secret=${URLEncoder.encode(clientSecret, "UTF-8")}"

            val conn = URL(TOKEN_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.outputStream.use { it.write(bodyStr.toByteArray()) }

            val code = conn.responseCode
            Log.d(TAG, "token HTTP $code")
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "token err body: ${err?.take(200)}")
                return@withContext null
            }

            val resp = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(resp)
            val token = json.optString("access_token")
            val expiresIn = json.optLong("expires_in", 3600)

            if (token.isBlank()) {
                Log.e(TAG, "empty token: $resp")
                return@withContext null
            }

            cachedToken = token
            tokenExpiry = System.currentTimeMillis() + expiresIn * 1000
            Log.d(TAG, "token ok, expires_in=$expiresIn")
            token
        } catch (e: Exception) {
            Log.e(TAG, "getToken err: ${e.message}", e)
            null
        }
    }

    /**
     * 단일 텍스트 번역
     * @param engine "generalNT_ja_ko" (일→한) 등
     */
    suspend fun translate(text: String, engine: String = "generalNT_ja_ko"): String? =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext ""
            val token = getToken() ?: return@withContext null

            try {
                val clientId = BuildConfig.TEXTRA_CLIENT_ID
                val name = BuildConfig.TEXTRA_NAME
                if (clientId.isBlank() || name.isBlank()) {
                    Log.e(TAG, "no clientId/name")
                    return@withContext null
                }

                val params = listOf(
                    "access_token" to token,
                    "key" to clientId,
                    "api_name" to "mt",
                    "api_param" to engine,
                    "name" to name,
                    "type" to "json",
                    "text" to text
                )
                val bodyStr = params.joinToString("&") {
                    "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}"
                }

                val conn = URL(API_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.doOutput = true
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.outputStream.use { it.write(bodyStr.toByteArray()) }

                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.e(TAG, "api HTTP $code")
                    return@withContext null
                }

                val resp = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(resp)
                val rs = json.optJSONObject("resultset") ?: return@withContext null
                val rcode = rs.optInt("code", -1)
                if (rcode != 0) {
                    Log.e(TAG, "resultset code=$rcode")
                    return@withContext null
                }

                rs.optJSONObject("result")?.optString("text", "") ?: ""
            } catch (e: Exception) {
                Log.e(TAG, "translate err: ${e.message}", e)
                null
            }
        }

    /**
     * 배치 번역 — 각 라인 개별 요청 (TexTra는 단일 텍스트만)
     * 요청 사이 300ms 지연 (rate limit)
     */
    suspend fun translateBatch(texts: List<String>, engine: String = "generalNT_ja_ko"): List<String>? =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext emptyList()

            val out = mutableListOf<String>()
            for ((i, t) in texts.withIndex()) {
                val result = translate(t, engine)
                if (result == null) {
                    Log.e(TAG, "batch failed at $i")
                    return@withContext null
                }
                out.add(result)
                // rate limit 대응
                if (i < texts.size - 1) delay(300)
            }
            out
        }
}
