package com.pulse.rsswidget.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Fetches and caches site favicons on disk, keyed by domain.
 * Tries DuckDuckGo, then Google, then gives up (caller shows the app-icon fallback).
 * A zero-byte file is a negative-cache marker so we don't re-hit the network forever.
 */
class FaviconStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "favicons").apply { mkdirs() }
    private val memory = HashMap<String, Bitmap>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun fileFor(domain: String): File =
        File(dir, domain.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".png")

    /** Disk/memory only — never touches the network. Safe to call on the widget render path. */
    fun loadCached(domain: String): Bitmap? {
        if (domain.isBlank()) return null
        synchronized(memory) { memory[domain]?.let { return it } }
        val file = fileFor(domain)
        if (!file.exists() || file.length() == 0L) return null
        val bmp = BitmapFactory.decodeFile(file.path) ?: return null
        synchronized(memory) { memory[domain] = bmp }
        return bmp
    }

    /** Returns a cached icon or downloads one. Records a negative marker on failure. */
    suspend fun fetchAndCache(domain: String): Bitmap? = withContext(Dispatchers.IO) {
        if (domain.isBlank()) return@withContext null
        loadCached(domain)?.let { return@withContext it }
        val file = fileFor(domain)
        // A recent failure is cached as a marker; only re-attempt once it goes stale.
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < NEGATIVE_TTL_MS) {
            return@withContext null
        }

        val bytes = download(domain)
        val decoded = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        if (decoded == null) {
            runCatching { if (!file.createNewFile()) file.setLastModified(System.currentTimeMillis()) }
            return@withContext null
        }
        val scaled = runCatching { Bitmap.createScaledBitmap(decoded, ICON_PX, ICON_PX, true) }.getOrDefault(decoded)
        runCatching { FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        synchronized(memory) { memory[domain] = scaled }
        scaled
    }

    private fun download(domain: String): ByteArray? {
        val sources = listOf(
            "https://icons.duckduckgo.com/ip3/$domain.ico",
            "https://www.google.com/s2/favicons?domain=$domain&sz=64"
        )
        for (url in sources) {
            val bytes = runCatching {
                client.newCall(Request.Builder().url(url).header("User-Agent", "PulseRSSWidget/1.0").build())
                    .execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
            }.getOrNull()
            if (bytes != null && bytes.size > 64) return bytes
        }
        return null
    }

    companion object {
        private const val ICON_PX = 48
        private const val NEGATIVE_TTL_MS = 7L * 24 * 60 * 60 * 1000   // retry a failed favicon after a week
    }
}
