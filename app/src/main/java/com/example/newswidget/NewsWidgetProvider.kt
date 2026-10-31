package com.example.newswidget // 패키지명 확인

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NewsArticle(val title: String, val link: String)

class NewsWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.example.newswidget.ACTION_REFRESH"
        const val ACTION_ROTATE = "com.example.newswidget.ACTION_ROTATE"
        private const val RSS_URL = "https://news.google.com/rss?hl=ko&gl=KR&ceid=KR:ko"
        private const val GOOGLE_NEWS_PACKAGE = "com.google.android.apps.magazines"

        // ==========================================
        // ⚙️ [주기 제어 설정 영역]
        // ==========================================
        private const val ROTATE_INTERVAL_MS = 1 * 60 * 1000L   // 가만히 둘 때 자동 넘김: 1분
        private const val RSS_FETCH_INTERVAL_MS = 15 * 60 * 1000L // 구글 최신 RSS 다운로드 간격: 15분
        // ==========================================

        private const val PREF_NAME = "NewsWidgetPrefs"
        private const val KEY_ARTICLES_JSON = "articles_json"
        private const val KEY_CURRENT_INDEX = "current_index"
        private const val KEY_LAST_FETCH_TIME = "last_fetch_time"

        private val blockedPublishers = listOf(
            "조선일보",
            "세계일보"
        )
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        checkAndRefreshNews(context, forceFetch = true)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            // [1] 손으로 터치했을 때: 즉시 다음 뉴스로 0초 만에 샥 넘김!
            ACTION_REFRESH -> {
                val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                val rawJson = prefs.getString(KEY_ARTICLES_JSON, null)

                if (rawJson.isNullOrEmpty()) {
                    // 저장된 뉴스가 아예 없으면 즉시 인터넷 다운로드
                    fetchAndStoreArticles(context, updateUiImmediately = true)
                } else {
                    // 저장된 뉴스가 있으면 즉시 다음 뉴스로 글자 전환!
                    showNextArticleFromStorage(context)
                    // 터치한 시점부터 다시 1분 타이머 재시작
                    scheduleNextRotation(context)

                    // 만약 구글에서 뉴스를 받아온 지 15분이 넘었다면, 백그라운드에서 조용히 최신 기사 보충
                    val lastFetchTime = prefs.getLong(KEY_LAST_FETCH_TIME, 0L)
                    if (System.currentTimeMillis() - lastFetchTime >= RSS_FETCH_INTERVAL_MS) {
                        fetchAndStoreArticles(context, updateUiImmediately = false)
                    }
                }
            }

            // [2] 화면이 켜질 때 (슬립 모드 해제)
            Intent.ACTION_USER_PRESENT -> {
                checkAndRefreshNews(context, forceFetch = false)
            }

            // [3] 터치하지 않아도 1분 경과 시: 자동으로 다음 뉴스로 넘김
            ACTION_ROTATE -> {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                // 화면이 켜져 있을 때만 다음 뉴스로 넘기고 타이머 연장 (화면 꺼지면 멈춤)
                if (powerManager.isInteractive) {
                    showNextArticleFromStorage(context)
                    scheduleNextRotation(context)

                    // 15분 경과 시 백그라운드 RSS 보충
                    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    val lastFetchTime = prefs.getLong(KEY_LAST_FETCH_TIME, 0L)
                    if (System.currentTimeMillis() - lastFetchTime >= RSS_FETCH_INTERVAL_MS) {
                        fetchAndStoreArticles(context, updateUiImmediately = false)
                    }
                }
            }
        }
    }

    private fun checkAndRefreshNews(context: Context, forceFetch: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val lastFetchTime = prefs.getLong(KEY_LAST_FETCH_TIME, 0L)
        val currentTime = System.currentTimeMillis()

        if (forceFetch || (currentTime - lastFetchTime >= RSS_FETCH_INTERVAL_MS)) {
            fetchAndStoreArticles(context, updateUiImmediately = true)
        } else {
            showNextArticleFromStorage(context)
            scheduleNextRotation(context)
        }
    }

    // 구글 RSS 다운로드
    private fun fetchAndStoreArticles(context: Context, updateUiImmediately: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
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
                        val jsonArray = JSONArray()
                        for (article in articleList) {
                            val obj = JSONObject().apply {
                                put("title", article.title)
                                put("link", article.link)
                            }
                            jsonArray.put(obj)
                        }

                        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        prefs.edit()
                            .putString(KEY_ARTICLES_JSON, jsonArray.toString())
                            .putLong(KEY_LAST_FETCH_TIME, System.currentTimeMillis())
                            .apply()

                        // 화면 즉시 갱신이 필요할 때만 (처음 켤 때 등)
                        if (updateUiImmediately) {
                            prefs.edit().putInt(KEY_CURRENT_INDEX, 0).apply()
                            withContext(Dispatchers.Main) {
                                displayArticle(context, articleList[0])
                                scheduleNextRotation(context)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (updateUiImmediately) {
                    withContext(Dispatchers.Main) {
                        displayArticle(
                            context,
                            NewsArticle("네트워크 연결을 확인하세요.", "https://news.google.com")
                        )
                    }
                }
            }
        }
    }

    // 저장소에서 다음 기사 꺼내서 화면에 표출
    private fun showNextArticleFromStorage(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val rawJson = prefs.getString(KEY_ARTICLES_JSON, null) ?: return

        try {
            val jsonArray = JSONArray(rawJson)
            if (jsonArray.length() == 0) return

            var index = prefs.getInt(KEY_CURRENT_INDEX, 0)
            index = (index + 1) % jsonArray.length() // 순차적으로 1씩 증가

            prefs.edit().putInt(KEY_CURRENT_INDEX, index).apply()

            val currentObj = jsonArray.getJSONObject(index)
            val article = NewsArticle(
                title = currentObj.getString("title"),
                link = currentObj.getString("link")
            )
            displayArticle(context, article)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 화면 그리기 (터치 이벤트 바인딩)
    private fun displayArticle(context: Context, article: NewsArticle) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val thisWidget = ComponentName(context, NewsWidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_news)

            // 터치 신호 인텐트
            val refreshIntent = Intent(context, NewsWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 💡 [핵심] 배경뿐만 아니라 긴 글자 영역을 터치해도 확실하게 갱신되도록 연결!
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)
            views.setOnClickPendingIntent(R.id.text_news_headline, pendingIntent)

            // 시간 및 본문 업데이트
            val currentTime = SimpleDateFormat("HH:mm:ss 갱신", Locale.KOREA).format(Date())
            views.setTextViewText(R.id.text_news_headline, article.title)
            views.setTextViewText(R.id.text_update_time, currentTime)

            // '뉴스 보기 >' 클릭 시 구글 뉴스 앱 검색 연동
            val searchKeyword = article.title.substringBeforeLast(" - ")
            val searchUrl = "https://news.google.com/search?q=${Uri.encode(searchKeyword)}&hl=ko&gl=KR&ceid=KR:ko"
            val isAppInstalled = context.packageManager.getLaunchIntentForPackage(GOOGLE_NEWS_PACKAGE) != null

            val articleIntent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (isAppInstalled) {
                    setPackage(GOOGLE_NEWS_PACKAGE)
                } else {
                    // [추가] 브라우저가 새 창(탭)을 만들지 않고 기존 창 1개만 재사용하도록 꼬리표 부착!
                    putExtra(android.provider.Browser.EXTRA_APPLICATION_ID, context.packageName)
                    putExtra("create_new_tab", false)
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

    // 비-웨이크업 1분 알람
    private fun scheduleNextRotation(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NewsWidgetProvider::class.java).apply {
            action = ACTION_ROTATE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            200,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAt = SystemClock.elapsedRealtime() + ROTATE_INTERVAL_MS
        alarmManager.set(AlarmManager.ELAPSED_REALTIME, triggerAt, pendingIntent)
    }
}