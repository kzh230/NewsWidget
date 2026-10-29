package com.example.newswidget // ⚠️ 본인의 실제 패키지명 확인!

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// 뉴스 제목과 상세 기사 링크를 보관하는 데이터 클래스
data class NewsArticle(val title: String, val link: String)

class NewsWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.example.newswidget.ACTION_REFRESH"
        private const val RSS_URL = "https://news.google.com/rss?hl=ko&gl=KR&ceid=KR:ko"
        private const val WORK_NAME = "NewsWidgetPeriodicUpdate"
        private const val GOOGLE_NEWS_PACKAGE = "com.google.android.apps.magazines"

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, NewsWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            for (appWidgetId in appWidgetIds) {
                updateSingleWidget(context, appWidgetManager, appWidgetId)
            }
        }

        private fun updateSingleWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_news)

            // 1. 위젯 전체(배경, 제목 등) 터치 시 -> 새로고침
            val refreshIntent = Intent(context, NewsWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

            // 2. 네트워크로 최신 뉴스 1건 가져오기
            CoroutineScope(Dispatchers.IO).launch {
                val article = fetchRandomArticle()
                val currentTime = SimpleDateFormat("HH:mm:ss 갱신", Locale.KOREA).format(Date())

                withContext(Dispatchers.Main) {
                    views.setTextViewText(R.id.text_news_headline, article.title)
                    views.setTextViewText(R.id.text_update_time, currentTime)

                    // 3. '뉴스 보기 >' 터치 시 -> 브라우저 대신 '구글 뉴스 앱의 검색 화면'으로 직행!
                    // 제목 끝의 " - 언론사명"을 분리하여 핵심 검색어로 만듭니다.
                    val searchKeyword = article.title.substringBeforeLast(" - ")
                    val searchUrl = "https://news.google.com/search?q=${Uri.encode(searchKeyword)}&hl=ko&gl=KR&ceid=KR:ko"

                    val isAppInstalled = context.packageManager.getLaunchIntentForPackage(GOOGLE_NEWS_PACKAGE) != null

                    val articleIntent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        if (isAppInstalled) {
                            setPackage(GOOGLE_NEWS_PACKAGE) // 구글 뉴스 앱으로 강제 실행
                        }
                    }

                    val appPendingIntent = PendingIntent.getActivity(
                        context,
                        appWidgetId,
                        articleIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(R.id.btn_open_news, appPendingIntent)

                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            }
        }

        private fun fetchRandomArticle(): NewsArticle {
            val blockedPublishers = listOf(
                "조선일보",
                "세계일보"
            )

            return try {
                val doc = Jsoup.connect(RSS_URL)
                    .parser(Parser.xmlParser())
                    .timeout(5000)
                    .get()

                val items = doc.select("item")
                if (items.isNotEmpty()) {
                    val articleList = items.map { item ->
                        NewsArticle(
                            title = item.selectFirst("title")?.text().orEmpty(),
                            link = item.selectFirst("link")?.text().orEmpty()
                        )
                    }.filter { article ->
                        article.title.isNotEmpty() &&
                                blockedPublishers.none { publisher -> article.title.contains(publisher) }
                    }

                    if (articleList.isNotEmpty()) {
                        articleList.random()
                    } else {
                        NewsArticle("필터링 후 표시할 뉴스가 없습니다.", "https://news.google.com")
                    }
                } else {
                    NewsArticle("최신 뉴스를 가져올 수 없습니다.", "https://news.google.com")
                }
            } catch (e: Exception) {
                NewsArticle("네트워크 연결을 확인하세요.", "https://news.google.com")
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        startPeriodicUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            updateAllWidgets(context)
        }
    }

    private fun startPeriodicUpdate(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<NewsUpdateWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
    }
}