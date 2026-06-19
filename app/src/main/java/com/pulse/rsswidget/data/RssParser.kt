package com.pulse.rsswidget.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Minimal RSS 2.0 + Atom parser built on the platform XmlPullParser (no extra deps). */
object RssParser {

    data class ParsedItem(val title: String, val link: String, val timeMillis: Long)
    data class Parsed(val feedTitle: String?, val items: List<ParsedItem>)

    fun parse(xml: String): Parsed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml.trim().removePrefix("﻿")))

        var feedTitle: String? = null
        val items = ArrayList<ParsedItem>()

        var inItem = false
        var curTitle: String? = null
        var curLink: String? = null
        var curTime = 0L

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase(Locale.ROOT)) {
                        "item", "entry" -> {
                            inItem = true; curTitle = null; curLink = null; curTime = 0L
                        }
                        "title" -> {
                            val t = readText(parser)
                            if (inItem) curTitle = t else if (feedTitle == null) feedTitle = t
                        }
                        "link" -> {
                            if (inItem) {
                                val href = parser.getAttributeValue(null, "href")
                                if (href != null) {
                                    val rel = parser.getAttributeValue(null, "rel")
                                    if (curLink == null || rel == null || rel == "alternate") curLink = href
                                } else {
                                    val text = readText(parser)
                                    if (!text.isNullOrBlank()) curLink = text
                                }
                            }
                        }
                        "pubdate", "published", "updated", "date" -> {
                            if (inItem && curTime == 0L) curTime = parseDate(readText(parser))
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase(Locale.ROOT)
                    if (name == "item" || name == "entry") {
                        val title = curTitle?.trim()
                        val link = curLink?.trim()
                        if (!title.isNullOrBlank() && !link.isNullOrBlank()) {
                            items.add(ParsedItem(unescape(title), link, curTime))
                        }
                        inItem = false
                    }
                }
            }
            event = parser.next()
        }
        return Parsed(feedTitle?.let { unescape(it.trim()) }, items)
    }

    private fun readText(parser: XmlPullParser): String? = runCatching {
        if (parser.next() == XmlPullParser.TEXT) {
            val text = parser.text
            parser.nextTag()
            text
        } else null
    }.getOrNull()

    private val DATE_FORMATS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    )

    private fun parseDate(raw: String?): Long {
        val s = raw?.trim() ?: return 0L
        if (s.isEmpty()) return 0L
        for (pattern in DATE_FORMATS) {
            runCatching {
                val fmt = SimpleDateFormat(pattern, Locale.ENGLISH)
                val d: Date? = fmt.parse(s)
                if (d != null) return d.time
            }
        }
        return 0L
    }

    private val DECIMAL_ENTITY = Regex("&#(\\d+);")
    private val HEX_ENTITY = Regex("&#[xX]([0-9a-fA-F]+);")

    private fun unescape(s: String): String {
        var r = s.replace(Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL), "$1")
        r = DECIMAL_ENTITY.replace(r) { m ->
            m.groupValues[1].toIntOrNull()
                ?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value
        }
        r = HEX_ENTITY.replace(r) { m ->
            m.groupValues[1].toIntOrNull(16)
                ?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value
        }
        r = r
            .replace("&rsquo;", "’").replace("&lsquo;", "‘")
            .replace("&rdquo;", "”").replace("&ldquo;", "“")
            .replace("&hellip;", "…").replace("&mdash;", "—").replace("&ndash;", "–")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&amp;", "&")
        return r.trim()
    }
}
