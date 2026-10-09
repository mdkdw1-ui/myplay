package com.example.myplayer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * 서버 헬스체크
 * - 하루 2번(12시간 간격) Invidious/Piped 서버 상태 확인
 * - 살아있는 서버만 SharedPreferences에 저장
 * - 앱은 저장된 서버만 사용
 */
object ServerHealthChecker {

    private const val TAG = "ServerHealthChecker"
    private const val PREF = "server_health"
    private const val KEY_INVIDIOUS = "alive_invidious"
    private const val KEY_PIPED = "alive_piped"
    private const val KEY_LAST_CHECK = "last_check"

    private const val CHECK_INTERVAL = 12 * 60 * 60 * 1000L  // 12시간

    // 전체 서버 풀 (체크 대상)
    val ALL_INVIDIOUS = listOf(
        "https://invidious.f5.si",
        "https://invidious.nerdvpn.de",
        "https://yewtu.be",
        "https://invidious.privacyredirect.com",
        "https://invidious.jing.rocks",
        "https://iv.datura.network",
        "https://invidious.einfachzocken.eu",
        "https://iv.melmac.space",
        "https://invidious.reallyaweso.me",
        "https://inv.nadeko.net",
        "https://invidious.privacydev.net",
        "https://iv.ggtyler.dev",
        "https://invidious.perennialte.ch",
        "https://inv.tux.pizza"
    )

    val ALL_PIPED = listOf(
        "https://api.piped.private.coffee",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.drgns.space",
        "https://pipedapi.ducks.party",
        "https://pipedapi.nosebs.ru",
        "https://pipedapi.reallyaweso.me",
        "https://pipedapi.leptons.xyz",
        "https://api.piped.projectsegfau.lt"
    )

    /**
     * 저장된 살아있는 서버 반환 (필요시 체크 실행)
     */
    suspend fun getAliveInvidious(ctx: Context): List<String> {
        ensureChecked(ctx)
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_INVIDIOUS, null)
            ?.toList()
            ?: emptyList()
        return if (saved.isNotEmpty()) saved else ALL_INVIDIOUS
    }

    suspend fun getAlivePiped(ctx: Context): List<String> {
        ensureChecked(ctx)
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_PIPED, null)
            ?.toList()
            ?: emptyList()
        return if (saved.isNotEmpty()) saved else ALL_PIPED
    }

    /**
     * 12시간 지났으면 재체크
     */
    suspend fun ensureChecked(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)
        val now = System.currentTimeMillis()

        if (now - lastCheck < CHECK_INTERVAL) {
            Log.d(TAG, "체크 skip (${(now - lastCheck) / 1000 / 60}분 전 체크)")
            return
        }

        Log.d(TAG, "🚀 헬스체크 시작")
        checkAll(ctx)
    }

    /**
     * 강제 재체크 (수동 or 앱 시작 시)
     */
    suspend fun forceCheck(ctx: Context) {
        checkAll(ctx)
    }

    private suspend fun checkAll(ctx: Context) = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()

        // ★ 병렬 체크 (모든 서버 동시)
        val (aliveInv, alivePiped) = coroutineScope {
            val invDeferred = ALL_INVIDIOUS.map { server ->
                async { if (checkInvidious(server)) server else null }
            }
            val pipedDeferred = ALL_PIPED.map { server ->
                async { if (checkPiped(server)) server else null }
            }
            Pair(
                invDeferred.awaitAll().filterNotNull(),
                pipedDeferred.awaitAll().filterNotNull()
            )
        }

        val elapsed = System.currentTimeMillis() - start
        Log.d(TAG, "✅ 체크 완료 (${elapsed}ms)")
        Log.d(TAG, "   살아있는 Invidious: ${aliveInv.size}/${ALL_INVIDIOUS.size}")
        Log.d(TAG, "   살아있는 Piped: ${alivePiped.size}/${ALL_PIPED.size}")
        if (aliveInv.isNotEmpty()) {
            Log.d(TAG, "   Invidious 목록: ${aliveInv.take(3)}")
        }

        // 저장
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet(KEY_INVIDIOUS, aliveInv.toSet())
            .putStringSet(KEY_PIPED, alivePiped.toSet())
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()
    }

    /**
     * Invidious 서버 상태 확인
     * - /api/v1/stats 에 2초 timeout curl
     * - 200 OK면 살아있음
     */
    private suspend fun checkInvidious(server: String): Boolean =
        withTimeoutOrNull(2500) {
            try {
                val conn = URL("$server/api/v1/stats").openConnection() as HttpURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                val code = conn.responseCode
                conn.disconnect()
                code in 200..299
            } catch (e: Exception) {
                Log.d(TAG, "❌ $server: ${e.message?.take(40)}")
                false
            }
        } ?: false

    /**
     * Piped 서버 상태 확인
     * - /healthcheck 또는 /streams/{더미ID}
     */
    private suspend fun checkPiped(server: String): Boolean =
        withTimeoutOrNull(2500) {
            try {
                val conn = URL("$server/healthcheck").openConnection() as HttpURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                val code = conn.responseCode
                conn.disconnect()
                code in 200..299
            } catch (e: Exception) {
                false
            }
        } ?: false

    /**
     * 디버깅용: 저장된 서버 목록 출력
     */
    fun printStatus(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val inv = prefs.getStringSet(KEY_INVIDIOUS, emptySet()) ?: emptySet()
        val piped = prefs.getStringSet(KEY_PIPED, emptySet()) ?: emptySet()
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        val ago = (System.currentTimeMillis() - last) / 1000 / 60
        Log.d(TAG, "═══ 서버 상태 (${ago}분 전) ═══")
        Log.d(TAG, "Invidious (${inv.size}): ${inv.take(5)}")
        Log.d(TAG, "Piped (${piped.size}): ${piped.take(5)}")
    }
}