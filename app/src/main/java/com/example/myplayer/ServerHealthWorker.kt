package com.example.myplayer

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class ServerHealthWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            Log.d("ServerHealthWorker", "🚀 백그라운드 헬스체크")
            ServerHealthChecker.forceCheck(applicationContext)
            ServerHealthChecker.printStatus(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e("ServerHealthWorker", "err: ${e.message}", e)
            Result.retry()
        }
    }
}
