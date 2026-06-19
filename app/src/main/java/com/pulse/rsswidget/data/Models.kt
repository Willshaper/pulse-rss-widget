package com.pulse.rsswidget.data

import android.net.Uri

data class Feed(
    val url: String,
    val autoTitle: String = "",
    val customTitle: String = "",
    val enabled: Boolean = true,
    val keywords: String = ""        // comma-separated; empty = no filter
) {
    /** Name shown in the app: custom title wins, then the feed's own title, then host. */
    val displayTitle: String
        get() = customTitle.ifBlank { autoTitle.ifBlank { domainOf(url) } }

    fun keywordList(): List<String> = parseKeywords(keywords)

    /** True if [title] passes this feed's keyword filter (empty filter = everything passes). */
    fun titlePasses(title: String): Boolean {
        val kws = keywordList()
        return kws.isEmpty() || titleMatchesAny(title, kws)
    }
}

data class FeedItem(
    val link: String,                // unique key used for de-duplication
    val title: String,
    val feedUrl: String,
    val timeMillis: Long,
    val domain: String               // host of [link], used for the favicon
)

/** Per-feed HTTP state: caching validators and a rate-limit backoff deadline. */
data class FeedMeta(
    val etag: String? = null,
    val lastModified: String? = null,
    val retryAfterUntil: Long = 0L
)

/** Split a comma-separated keyword string into trimmed, lowercased, non-empty terms. */
fun parseKeywords(raw: String): List<String> =
    raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

/** True if [title] contains any of [keywords] (case-insensitive). Empty list = no match. */
fun titleMatchesAny(title: String, keywords: List<String>): Boolean {
    if (keywords.isEmpty()) return false
    val lower = title.lowercase()
    return keywords.any { lower.contains(it) }
}

/** Host of a URL, without a leading "www.". */
fun domainOf(url: String): String =
    runCatching { Uri.parse(url).host ?: url }.getOrDefault(url).removePrefix("www.")

/**
 * The items the widget shows: from enabled feeds, passing each feed's keyword filter and
 * not matching the global mute list, newest first, capped at [limit]. Single source of truth
 * for both the widget render and the favicon prefetch.
 */
fun visibleWidgetItems(
    feeds: List<Feed>,
    history: List<FeedItem>,
    muteWords: List<String>,
    limit: Int
): List<FeedItem> {
    val byUrl = feeds.associateBy { it.url }
    return history
        .filter { item ->
            val feed = byUrl[item.feedUrl]
            feed != null && feed.enabled && feed.titlePasses(item.title) && !titleMatchesAny(item.title, muteWords)
        }
        .take(limit)
}
