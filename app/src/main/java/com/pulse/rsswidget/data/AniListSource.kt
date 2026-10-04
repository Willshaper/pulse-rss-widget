package com.pulse.rsswidget.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * New episodes of the airing shows on an AniList user's lists (every list except Dropped), from
 * AniList's public GraphQL API — no login. Each refresh makes two requests: the list (media ids
 * and airing status only, Dropped excluded by AniList), then the episodes that aired in the last
 * [LOOKBACK_DAYS] for the shows kept. Adult shows are not filtered out. Rows open the show's
 * AniList page.
 */
class AniListSource(private val client: OkHttpClient) {

    /** Result of looking a user up before adding them as a source. */
    sealed class UserCheck {
        data class Found(val userName: String, val shows: Int) : UserCheck()
        object NotFound : UserCheck()        // unknown user, or a private list
        object Unreachable : UserCheck()
    }

    /** Thrown when AniList has no (public) list for the user. */
    private class NoPublicList : Exception()

    private class UserList(val userName: String, val showIds: List<Int>)

    fun check(user: String): UserCheck = try {
        val list = fetchList(user)
        UserCheck.Found(list.userName.ifBlank { user }, list.showIds.size)
    } catch (e: NoPublicList) {
        UserCheck.NotFound
    } catch (e: Exception) {
        UserCheck.Unreachable
    }

    /** Episodes aired in the look-back window, newest first, as items of the source at [feedUrl]. */
    fun recentEpisodes(user: String, feedUrl: String): List<FeedItem> {
        val ids = fetchList(user).showIds
        if (ids.isEmpty()) return emptyList()
        val now = System.currentTimeMillis() / 1000
        val items = ArrayList<FeedItem>()
        for (page in 1..MAX_PAGES) {
            val variables = JSONObject()
                .put("p", page)
                .put("ids", JSONArray(ids))
                .put("from", now - LOOKBACK_DAYS * 24 * 60 * 60)
                .put("to", now + 1)
            val pg = post(SCHEDULE_QUERY, variables).optJSONObject("Page") ?: break
            val schedules = pg.optJSONArray("airingSchedules") ?: JSONArray()
            for (i in 0 until schedules.length()) toItem(schedules.getJSONObject(i), feedUrl)?.let(items::add)
            if (pg.optJSONObject("pageInfo")?.optBoolean("hasNextPage") != true) break
        }
        return items
    }

    private fun fetchList(user: String): UserList {
        val collection = post(LIST_QUERY, JSONObject().put("u", user)).optJSONObject("MediaListCollection")
            ?: throw NoPublicList()
        val cutoff = LocalDate.now().minusDays(LOOKBACK_DAYS + 1)
        val ids = LinkedHashSet<Int>()   // custom lists repeat entries
        val lists = collection.optJSONArray("lists") ?: JSONArray()
        for (l in 0 until lists.length()) {
            val entries = lists.getJSONObject(l).optJSONArray("entries") ?: continue
            for (e in 0 until entries.length()) {
                val entry = entries.getJSONObject(e)
                val id = entry.optInt("mediaId", 0)
                val media = entry.optJSONObject("media") ?: continue
                if (id > 0 && isCurrent(media, cutoff)) ids += id
            }
        }
        val name = collection.optJSONObject("user")?.optNonBlank("name") ?: ""
        return UserList(name, ids.toList())
    }

    /**
     * Airing — or about to start / on a break, so a premiere or return shows up even before AniList
     * updates the status — or finished recently enough that its last episodes are still in the window.
     */
    private fun isCurrent(media: JSONObject, cutoff: LocalDate): Boolean = when (media.optString("status")) {
        "RELEASING", "NOT_YET_RELEASED", "HIATUS" -> true
        "FINISHED" -> endDateOf(media)?.let { !it.isBefore(cutoff) } ?: false
        else -> false
    }

    private fun endDateOf(media: JSONObject): LocalDate? {
        val d = media.optJSONObject("endDate") ?: return null
        val year = d.optInt("year", 0)
        val month = d.optInt("month", 0)
        val day = d.optInt("day", 0)
        if (year == 0 || month == 0 || day == 0) return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    private fun toItem(schedule: JSONObject, feedUrl: String): FeedItem? {
        val media = schedule.optJSONObject("media") ?: return null
        val id = media.optInt("id", 0)
        val episode = schedule.optInt("episode", 0)
        val airingAt = schedule.optLong("airingAt", 0L)
        if (id <= 0 || episode <= 0 || airingAt <= 0) return null
        val titles = media.optJSONObject("title")
        val title = titles?.optNonBlank("english") ?: titles?.optNonBlank("romaji") ?: return null
        return FeedItem(
            link = "https://anilist.co/anime/$id",
            title = "$title - Episode $episode",
            feedUrl = feedUrl,
            timeMillis = airingAt * 1000,
            domain = ICON_DOMAIN,
            key = "anilist:$id:$episode"   // the link is per show, so episodes need their own key
        )
    }

    private fun post(query: String, variables: JSONObject): JSONObject {
        val body = JSONObject().put("query", query).put("variables", variables).toString().toRequestBody(JSON_TYPE)
        val request = Request.Builder()
            .url(API_URL)
            .header("User-Agent", FeedRepository.USER_AGENT)   // AniList's CDN rejects some generic client agents
            .header("Accept", "application/json")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 429 || response.code == 503) {
                throw RateLimited(parseRetryAfterMs(response.header("Retry-After")))
            }
            if (response.code == 404) throw NoPublicList()
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) throw IllegalStateException("AniList error")
            return json.optJSONObject("data") ?: throw IllegalStateException("AniList returned no data")
        }
    }

    companion object {
        private const val API_URL = "https://graphql.anilist.co"
        private const val ICON_DOMAIN = "anilist.co"
        private const val LOOKBACK_DAYS = 7L
        private const val MAX_PAGES = 5
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        private const val LIST_QUERY =
            "query(\$u:String){MediaListCollection(userName:\$u,type:ANIME,status_not:DROPPED)" +
                "{user{name} lists{entries{mediaId media{status endDate{year month day}}}}}}"
        private const val SCHEDULE_QUERY =
            "query(\$p:Int,\$ids:[Int],\$from:Int,\$to:Int){Page(page:\$p,perPage:50){pageInfo{hasNextPage} " +
                "airingSchedules(mediaId_in:\$ids,airingAt_greater:\$from,airingAt_lesser:\$to,sort:TIME_DESC)" +
                "{episode airingAt media{id title{english romaji}}}}}"
    }
}

/** A string field, or null when it's missing, JSON null, or blank (org.json turns JSON null into "null"). */
private fun JSONObject.optNonBlank(name: String): String? =
    if (isNull(name)) null else optString(name).trim().ifBlank { null }
