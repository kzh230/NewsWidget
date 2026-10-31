package com.example.newswidget // 패키지명 확인

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class NewsUpdateWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        // 사라진 함수 대신 위젯에 "새로고침 신호(ACTION_REFRESH)"를 쏩니다
        val intent = Intent(context, NewsWidgetProvider::class.java).apply {
            action = NewsWidgetProvider.ACTION_REFRESH
        }
        context.sendBroadcast(intent)

        return Result.success()
    }
}