package com.example.newswidget // <-- 본인 패키지명이 com.example.xxx 라면 그 이름으로 맞춰주세요!

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class NewsUpdateWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        // 15분 주기로 깨어나 모든 위젯 화면을 최신 뉴스로 갱신
        NewsWidgetProvider.updateAllWidgets(context)
        return Result.success()
    }
}