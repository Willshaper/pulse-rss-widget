package com.pulse.rsswidget.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_settings")

/** Single source of truth: feeds, the deduplicated history, and app settings. */
class SettingsStore(context: Context) {

    private val ds = context.applicationContext.dataStore

    // ----- Feeds -----
    // distinctUntilChanged on the raw JSON avoids re-decoding on unrelated DataStore writes.
    val feedsFlow: Flow<List<Feed>> = ds.data.map { it[KEY_FEEDS] }.distinctUntilChanged().map { decodeFeeds(it) }
    suspend fun getFeeds(): List<Feed> = decodeFeeds(ds.data.first()[KEY_FEEDS])

    suspend fun setFeeds(feeds: List<Feed>) {
        ds.edit { it[KEY_FEEDS] = encodeFeeds(feeds) }
    }

    // All feed mutations are atomic read-modify-writes so concurrent edits can't clobber each other.
    suspend fun addFeed(feed: Feed) {
        ds.edit { prefs ->
            val current = decodeFeeds(prefs[KEY_FEEDS]).toMutableList()
            if (current.none { it.url.equals(feed.url, ignoreCase = true) }) {
                current.add(feed)
                prefs[KEY_FEEDS] = encodeFeeds(current)
            }
        }
    }

    suspend fun removeFeed(url: String) {
        ds.edit { prefs ->
            prefs[KEY_FEEDS] = encodeFeeds(decodeFeeds(prefs[KEY_FEEDS]).filterNot { it.url.equals(url, ignoreCase = true) })
        }
    }

    suspend fun setEnabled(url: String, enabled: Boolean) =
        updateFeed(url) { it.copy(enabled = enabled) }

    suspend fun updateAutoTitle(url: String, autoTitle: String) =
        updateFeed(url) { if (it.autoTitle == autoTitle) it else it.copy(autoTitle = autoTitle) }

    suspend fun editFeed(url: String, customTitle: String, keywords: String) =
        updateFeed(url) { it.copy(customTitle = customTitle, keywords = keywords) }

    private suspend fun updateFeed(url: String, transform: (Feed) -> Feed) {
        ds.edit { prefs ->
            val updated = decodeFeeds(prefs[KEY_FEEDS]).map { if (it.url.equals(url, ignoreCase = true)) transform(it) else it }
            prefs[KEY_FEEDS] = encodeFeeds(updated)
        }
    }

    // ----- History (deduplicated, newest first, capped) -----
    val historyFlow: Flow<List<FeedItem>> = ds.data.map { it[KEY_HISTORY] }.distinctUntilChanged().map { decodeItems(it) }
    suspend fun getHistory(): List<FeedItem> = decodeItems(ds.data.first()[KEY_HISTORY])

    /** Drop history items from [feed] that no longer pass its keyword filter (after an edit). */
    suspend fun purgeHistoryForFeed(feed: Feed) {
        ds.edit { prefs ->
            val kept = decodeItems(prefs[KEY_HISTORY]).filterNot { it.feedUrl == feed.url && !feed.titlePasses(it.title) }
            prefs[KEY_HISTORY] = encodeItems(kept)
        }
    }

    /**
     * Merge new items into history, de-duplicating by link and keeping the newest [MAX_HISTORY].
     * The read-modify-write happens inside a single edit so concurrent refreshes can't clobber
     * each other's updates.
     */
    suspend fun mergeHistory(newItems: List<FeedItem>) {
        ds.edit { prefs ->
            val byLink = LinkedHashMap<String, FeedItem>()
            for (item in decodeItems(prefs[KEY_HISTORY])) byLink[item.link] = item
            for (item in newItems) byLink.putIfAbsent(item.link, item)
            val merged = byLink.values.sortedByDescending { it.timeMillis }.take(MAX_HISTORY)
            prefs[KEY_HISTORY] = encodeItems(merged)
        }
    }

    // ----- Scalars -----
    val intervalFlow: Flow<Int> = ds.data.map { it[KEY_INTERVAL] ?: DEFAULT_INTERVAL }
    suspend fun getInterval(): Int = ds.data.first()[KEY_INTERVAL] ?: DEFAULT_INTERVAL
    suspend fun setInterval(minutes: Int) = ds.edit { it[KEY_INTERVAL] = minutes }

    val showFaviconsFlow: Flow<Boolean> = ds.data.map { it[KEY_FAVICONS] ?: true }
    suspend fun getShowFavicons(): Boolean = ds.data.first()[KEY_FAVICONS] ?: true
    suspend fun setShowFavicons(show: Boolean) = ds.edit { it[KEY_FAVICONS] = show }

    // True only while a refresh is actually running. The timestamp lets the widget
    // auto-expire the overlay so it can never get stuck on.
    val refreshingFlow: Flow<Boolean> = ds.data.map { isFresh(it[KEY_REFRESHING] ?: false, it[KEY_REFRESH_AT] ?: 0L) }
    suspend fun isRefreshOverlayActive(): Boolean {
        val p = ds.data.first()
        return isFresh(p[KEY_REFRESHING] ?: false, p[KEY_REFRESH_AT] ?: 0L)
    }
    suspend fun setRefreshing(value: Boolean) = ds.edit {
        it[KEY_REFRESHING] = value
        if (value) it[KEY_REFRESH_AT] = System.currentTimeMillis()
    }

    private fun isFresh(on: Boolean, startedAt: Long): Boolean =
        on && (System.currentTimeMillis() - startedAt) < OVERLAY_TIMEOUT_MS

    val wifiOnlyFlow: Flow<Boolean> = ds.data.map { it[KEY_WIFI_ONLY] ?: false }
    suspend fun getWifiOnly(): Boolean = ds.data.first()[KEY_WIFI_ONLY] ?: false
    suspend fun setWifiOnly(value: Boolean) = ds.edit { it[KEY_WIFI_ONLY] = value }

    val lastRefreshFlow: Flow<Long> = ds.data.map { it[KEY_LAST_REFRESH] ?: 0L }
    suspend fun setLastRefresh(t: Long) = ds.edit { it[KEY_LAST_REFRESH] = t }

    // Global mute: hide entries whose title contains any of these, across all feeds.
    val muteKeywordsFlow: Flow<String> = ds.data.map { it[KEY_MUTE] ?: "" }.distinctUntilChanged()
    suspend fun getMuteKeywords(): String = ds.data.first()[KEY_MUTE] ?: ""
    suspend fun setMuteKeywords(value: String) = ds.edit { it[KEY_MUTE] = value }

    // ----- Backup / restore (feeds + settings; not the history cache) -----
    suspend fun exportBackup(): String {
        val obj = JSONObject()
        obj.put("app", "PulseRSSWidget")
        obj.put("version", 1)
        obj.put("exportedAt", System.currentTimeMillis())
        obj.put("interval", getInterval())
        obj.put("wifiOnly", getWifiOnly())
        obj.put("showFavicons", getShowFavicons())
        obj.put("mute", getMuteKeywords())
        obj.put("feeds", JSONArray(encodeFeeds(getFeeds())))
        return obj.toString(2)
    }

    /** Replaces feeds + settings from a backup. Returns false if the file isn't a valid backup. */
    suspend fun importBackup(json: String): Boolean = runCatching {
        val obj = JSONObject(json)
        obj.optJSONArray("feeds")?.let { setFeeds(decodeFeeds(it.toString())) }
        if (obj.has("interval")) setInterval(obj.getInt("interval"))
        if (obj.has("wifiOnly")) setWifiOnly(obj.getBoolean("wifiOnly"))
        if (obj.has("showFavicons")) setShowFavicons(obj.getBoolean("showFavicons"))
        if (obj.has("mute")) setMuteKeywords(obj.getString("mute"))
        true
    }.getOrDefault(false)

    // ----- Failed-feed tracking: only warn after several consecutive failures -----
    val failedFeedsFlow: Flow<Set<String>> = ds.data.map {
        decodeCounts(it[KEY_FAILCOUNTS]).filterValues { c -> c >= FAIL_THRESHOLD }.keys
    }
    suspend fun getFailCounts(): Map<String, Int> = decodeCounts(ds.data.first()[KEY_FAILCOUNTS])
    suspend fun setFailCounts(counts: Map<String, Int>) = ds.edit { it[KEY_FAILCOUNTS] = encodeCounts(counts) }

    // ----- Per-feed HTTP validators (ETag / Last-Modified) for conditional GETs -----
    suspend fun getFeedMeta(url: String): FeedMeta {
        val obj = decodeMeta(ds.data.first()[KEY_FEEDMETA])[url] ?: return FeedMeta()
        return obj
    }

    suspend fun setFeedMeta(url: String, meta: FeedMeta) {
        ds.edit { prefs ->
            val map = decodeMeta(prefs[KEY_FEEDMETA]).toMutableMap()
            map[url] = meta
            prefs[KEY_FEEDMETA] = encodeMeta(map)
        }
    }

    companion object {
        const val DEFAULT_INTERVAL = 30
        const val MAX_HISTORY = 1000
        const val WIDGET_LIMIT = 50

        private val KEY_FEEDS = stringPreferencesKey("feeds_json")
        private val KEY_HISTORY = stringPreferencesKey("history_json")
        private val KEY_INTERVAL = intPreferencesKey("interval_minutes")
        private val KEY_FAVICONS = booleanPreferencesKey("show_favicons")
        private val KEY_REFRESHING = booleanPreferencesKey("refreshing")
        private val KEY_REFRESH_AT = longPreferencesKey("refresh_started_at")
        private val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only")
        private val KEY_LAST_REFRESH = longPreferencesKey("last_refresh_at")
        private val KEY_MUTE = stringPreferencesKey("mute_keywords")

        /** The refresh overlay never shows longer than this, even if a worker dies mid-run. */
        const val OVERLAY_TIMEOUT_MS = 30_000L
        private val KEY_FAILCOUNTS = stringPreferencesKey("fail_counts_json")
        private val KEY_FEEDMETA = stringPreferencesKey("feed_meta_json")

        /** Show the "couldn't load" warning only after this many consecutive failures. */
        const val FAIL_THRESHOLD = 2

        private fun encodeCounts(counts: Map<String, Int>): String {
            val obj = JSONObject()
            counts.forEach { (url, c) -> if (c > 0) obj.put(url, c) }
            return obj.toString()
        }

        private fun decodeCounts(s: String?): Map<String, Int> {
            if (s.isNullOrBlank()) return emptyMap()
            return runCatching {
                val obj = JSONObject(s)
                obj.keys().asSequence().associateWith { obj.getInt(it) }
            }.getOrElse { emptyMap() }
        }

        private fun encodeMeta(map: Map<String, FeedMeta>): String {
            val obj = JSONObject()
            map.forEach { (url, meta) ->
                obj.put(
                    url,
                    JSONObject()
                        .put("etag", meta.etag ?: "")
                        .put("lastModified", meta.lastModified ?: "")
                        .put("retryAfter", meta.retryAfterUntil)
                )
            }
            return obj.toString()
        }

        private fun decodeMeta(s: String?): Map<String, FeedMeta> {
            if (s.isNullOrBlank()) return emptyMap()
            return runCatching {
                val obj = JSONObject(s)
                obj.keys().asSequence().associateWith { key ->
                    val o = obj.getJSONObject(key)
                    FeedMeta(
                        o.optString("etag").ifBlank { null },
                        o.optString("lastModified").ifBlank { null },
                        o.optLong("retryAfter", 0L)
                    )
                }
            }.getOrElse { emptyMap() }
        }

        private fun encodeFeeds(feeds: List<Feed>): String {
            val arr = JSONArray()
            feeds.forEach {
                arr.put(
                    JSONObject()
                        .put("url", it.url)
                        .put("autoTitle", it.autoTitle)
                        .put("customTitle", it.customTitle)
                        .put("enabled", it.enabled)
                        .put("keywords", it.keywords)
                )
            }
            return arr.toString()
        }

        private fun decodeFeeds(s: String?): List<Feed> {
            if (s.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(s)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Feed(
                        url = o.getString("url"),
                        autoTitle = o.optString("autoTitle", o.optString("title", "")),
                        customTitle = o.optString("customTitle", ""),
                        enabled = o.optBoolean("enabled", true),
                        keywords = o.optString("keywords", "")
                    )
                }
            }.getOrElse { emptyList() }
        }

        private fun encodeItems(items: List<FeedItem>): String {
            val arr = JSONArray()
            items.forEach {
                arr.put(
                    JSONObject()
                        .put("link", it.link)
                        .put("title", it.title)
                        .put("feedUrl", it.feedUrl)
                        .put("time", it.timeMillis)
                        .put("domain", it.domain)
                )
            }
            return arr.toString()
        }

        private fun decodeItems(s: String?): List<FeedItem> {
            if (s.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(s)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    FeedItem(
                        link = o.getString("link"),
                        title = o.getString("title"),
                        feedUrl = o.optString("feedUrl", ""),
                        timeMillis = o.optLong("time", 0L),
                        domain = o.optString("domain", "")
                    )
                }
            }.getOrElse { emptyList() }
        }
    }
}
