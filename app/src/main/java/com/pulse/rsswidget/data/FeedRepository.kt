package com.pulse.rsswidget.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Fetches enabled feeds and merges their full contents into the deduped history (filters apply only at the widget layer, not here). */
class FeedRepository(context: Context) {

    private val store = SettingsStore(context)
    private val favicons = FaviconStore(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private data class FetchResult(
        val notModified: Boolean,
        val body: String,
        val etag: String?,
        val lastModified: String?
    )

    /** Thrown on HTTP 429/503; carries how long to back off. */
    private class RateLimited(val retryAfterMs: Long) : Exception()

    /** Refresh every enabled feed and merge all new items into history (unfiltered — filters apply only to the widget view). */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val feeds = store.getFeeds().filter { it.enabled }
        val collected = ArrayList<FeedItem>()
        val counts = store.getFailCounts().toMutableMap()
        var anyReached = false

        for (feed in feeds) {
            val meta = store.getFeedMeta(feed.url)
            if (System.currentTimeMillis() < meta.retryAfterUntil) {
                Log.i(TAG, "Skipping ${feed.url} (rate-limit backoff active)")
                continue
            }
            try {
                val result = fetch(feed.url, meta.etag, meta.lastModified)
                counts[feed.url] = 0
                anyReached = true
                if (result.notModified) {
                    Log.i(TAG, "304 Not Modified: ${feed.url}")
                    continue
                }
                val parsed = RssParser.parse(result.body)
                parsed.feedTitle?.takeIf { it.isNotBlank() }?.let { store.updateAutoTitle(feed.url, it) }

                // History stores the full, unfiltered feed. Keyword and mute filters are applied
                // only at the widget/view layer (see visibleWidgetItems), so History always
                // reflects the true contents of the feeds.
                val items = parsed.items
                    .filter { it.title.isNotBlank() && it.link.isNotBlank() }
                    .map { FeedItem(it.link, it.title, feed.url, it.timeMillis, domainOf(it.link)) }
                collected += items
                store.setFeedMeta(feed.url, FeedMeta(result.etag, result.lastModified, 0L))
                Log.i(TAG, "Fetched ${feed.url} -> ${parsed.items.size} parsed, ${items.size} stored")
            } catch (e: RateLimited) {
                store.setFeedMeta(feed.url, meta.copy(retryAfterUntil = System.currentTimeMillis() + e.retryAfterMs))
                Log.i(TAG, "Rate limited ${feed.url}; backing off ${e.retryAfterMs / 1000}s")
            } catch (e: Exception) {
                counts[feed.url] = (counts[feed.url] ?: 0) + 1
                Log.w(TAG, "FAILED ${feed.url} (#${counts[feed.url]}): ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        val liveUrls = feeds.map { it.url }.toSet()
        store.setFailCounts(counts.filterKeys { it in liveUrls })
        if (collected.isNotEmpty()) store.mergeHistory(collected)
        if (anyReached) store.setLastRefresh(System.currentTimeMillis())

        if (store.getShowFavicons()) {
            val widgetDomains = widgetItems().map { it.domain }.distinct()
            for (domain in widgetDomains) runCatching { favicons.fetchAndCache(domain) }
        }
        Log.i(TAG, "Refresh done: collected=${collected.size}, history=${store.getHistory().size}")
    }

    /** Newest items for the widget — same filter the widget renders, so favicon prefetch matches. */
    suspend fun widgetItems(): List<FeedItem> = visibleWidgetItems(
        store.getFeeds(),
        store.getHistory(),
        parseKeywords(store.getMuteKeywords()),
        SettingsStore.WIDGET_LIMIT
    )

    /** Best-effort feed title for nicer display names when adding a feed. */
    suspend fun fetchFeedTitle(url: String): String? = withContext(Dispatchers.IO) {
        runCatching { RssParser.parse(fetch(url, null, null).body).feedTitle }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun fetch(url: String, etag: String?, lastModified: String?): FetchResult {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) PulseRSSWidget/1.0 (RSS reader)")
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
        if (etag != null) builder.header("If-None-Match", etag)
        if (lastModified != null) builder.header("If-Modified-Since", lastModified)

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 304) return FetchResult(true, "", etag, lastModified)
            if (response.code == 429 || response.code == 503) {
                throw RateLimited(parseRetryAfterMs(response.header("Retry-After")))
            }
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            return FetchResult(
                notModified = false,
                body = response.body?.string() ?: "",
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified")
            )
        }
    }

    private fun parseRetryAfterMs(header: String?): Long {
        if (header.isNullOrBlank()) return DEFAULT_BACKOFF_MS
        val trimmed = header.trim()
        trimmed.toLongOrNull()?.let { return (it.coerceIn(0, MAX_BACKOFF_S) * 1000) }
        return runCatching {
            val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(trimmed)
            if (date != null) (date.time - System.currentTimeMillis()).coerceIn(0, MAX_BACKOFF_S * 1000) else DEFAULT_BACKOFF_MS
        }.getOrDefault(DEFAULT_BACKOFF_MS)
    }

    companion object {
        private const val TAG = "PulseRepo"
        private const val DEFAULT_BACKOFF_MS = 5 * 60 * 1000L   // when no Retry-After header
        private const val MAX_BACKOFF_S = 6 * 60 * 60L          // cap a feed's backoff at 6h
    }
}
