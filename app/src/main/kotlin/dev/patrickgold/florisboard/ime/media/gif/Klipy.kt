/*
 * Tiune fork. Licensed under the Apache License, Version 2.0, like the rest
 * of FlorisBoard.
 */

package dev.patrickgold.florisboard.ime.media.gif

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

/** One rendition of a GIF: where it is and how big. */
data class GifFile(val url: String, val width: Int, val height: Int)

/**
 * One GIF as the panel needs it: a small animated preview for the grid and a
 * full-size GIF for sending. `slug` is KLIPY's id for it, which the share
 * report names.
 */
data class GifItem(
    val slug: String,
    val title: String,
    val preview: GifFile,
    val send: GifFile,
)

data class GifPage(val items: List<GifItem>, val hasNext: Boolean)

/** No key in this build: GIFs are off, and the page says so. */
class GifsNotConfigured : IOException("GIFs are not set up in this build")

/**
 * Tiune fork. The GIF source: KLIPY's API, called from the phone itself.
 *
 * Directly, not through Tiune's server, because KLIPY's terms require it: the
 * requests and the media loads come from the user's app, results are shown in
 * the order given, and nothing is re-hosted. Its keys are not secrets for the
 * same reason. What KLIPY receives is the search, a random id for this
 * keyboard (never the account), the phone's country, and its IP address.
 *
 * Plain HttpURLConnection and org.json: both are on every phone, and a GIF
 * page does not justify a networking library in a keyboard.
 */
object Klipy {
    private const val TAG = "TiuneGifs"
    private const val BASE = "https://api.klipy.com/api/v1"
    private const val PER_PAGE = 24
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    /** The safety level KLIPY applies to results. Tiune is rated for
     *  everyone on Play, so not "off"; finer blocking is done in KLIPY's
     *  partner panel, which is where its terms put it. */
    private const val CONTENT_FILTER = "medium"
    private const val PREFS = "tiune_gifs"
    /** How long a sent GIF's file may outlive its send (see saveForSending). */
    private const val SENT_FILE_LIFETIME_MS = 5 * 60 * 1000L
    private const val PREF_CUSTOMER_ID = "customer_id"

    /** A random id that stays the same for this keyboard, which KLIPY asks
     *  for to keep results consistent. Made here, never tied to the user's
     *  Tiune account or anything else about them. */
    private fun customerId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(PREF_CUSTOMER_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(PREF_CUSTOMER_ID, id).apply()
        return id
    }

    private fun key(): String = dev.patrickgold.florisboard.FlorisImeService.gifApiKeyOrNull()
        ?: throw GifsNotConfigured()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun base(): String = dev.patrickgold.florisboard.FlorisImeService.gifApiBaseOrNull() ?: BASE

    /** Trending when `query` is blank, a search otherwise. Page 1 first. */
    suspend fun page(context: Context, query: String, page: Int): GifPage = withContext(Dispatchers.IO) {
        val q = query.trim()
        val country = Locale.getDefault().country.lowercase(Locale.ROOT).ifEmpty { "us" }
        val params = buildString {
            append("page=").append(page)
            append("&per_page=").append(PER_PAGE)
            append("&customer_id=").append(enc(customerId(context)))
            append("&locale=").append(enc(country))
            append("&content_filter=").append(CONTENT_FILTER)
            append("&format_filter=").append(enc("gif,webp"))
            if (q.isNotEmpty()) append("&q=").append(enc(q))
        }
        val endpoint = if (q.isEmpty()) "trending" else "search"
        val body = get("${base()}/${key()}/gifs/$endpoint?$params")
        parsePage(body)
    }

    /**
     * Tell KLIPY a GIF was sent, as its API asks — what it learns from is
     * which result was picked for which search. Best effort: a failure here
     * must never stand between the user and their GIF.
     */
    suspend fun reportShare(context: Context, slug: String, query: String) = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().put("customer_id", customerId(context))
            if (query.isNotBlank()) json.put("q", query.trim())
            val conn = open("${base()}/${key()}/gifs/share/${enc(slug)}")
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(json.toString().toByteArray()) }
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {
            Log.d(TAG, "share report failed: ${e.javaClass.simpleName}")
        }
    }

    /** The bytes at `url` — a preview for the grid, or the GIF being sent. */
    suspend fun fetch(url: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("too large")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Write the GIF being sent to the cache, where the FileProvider can
     *  hand it to the app — the one way Android lets a keyboard insert an
     *  image. KLIPY's terms forbid keeping copies of its media, so a file
     *  lives only long enough for the receiving app to read it: the previous
     *  send survives a few minutes (an app may still be copying it), nothing
     *  older does. */
    suspend fun saveForSending(context: Context, item: GifItem, bytes: ByteArray): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "gifs").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - SENT_FILE_LIFETIME_MS
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEachIndexed { i, f ->
            if (i >= 1 || f.lastModified() < cutoff) f.delete()
        }
        val safe = item.slug.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60).ifEmpty { "gif" }
        File(dir, "$safe.gif").apply { writeBytes(bytes) }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json, image/*")
        }

    private fun get(url: String): String {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    internal fun parsePage(body: String): GifPage {
        val root = JSONObject(body)
        if (!root.optBoolean("result", false)) throw IOException("no result")
        val data = root.optJSONObject("data") ?: return GifPage(emptyList(), false)
        val list = data.optJSONArray("data") ?: return GifPage(emptyList(), false)
        val items = ArrayList<GifItem>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            // Only GIFs: anything else (an advert, which needs a web view
            // this panel does not have) cannot be drawn here at all.
            if (o.optString("type", "gif") != "gif") continue
            val file = o.optJSONObject("file") ?: continue
            // A small, animated preview: webp is a fraction of the GIF's size.
            val preview = rendition(file, listOf("sm" to "webp", "sm" to "gif", "xs" to "webp", "md" to "webp"))
            // What is sent: a GIF, the one format every app that takes images
            // accepts; medium size, the trade Gboard makes too.
            val send = rendition(file, listOf("md" to "gif", "sm" to "gif", "hd" to "gif"))
            if (preview == null || send == null) continue
            items += GifItem(
                slug = o.optString("slug").ifEmpty { o.optString("id") },
                title = o.optString("title"),
                preview = preview,
                send = send,
            )
        }
        return GifPage(items, data.optBoolean("has_next", false))
    }

    private fun rendition(file: JSONObject, order: List<Pair<String, String>>): GifFile? {
        for ((size, format) in order) {
            val f = file.optJSONObject(size)?.optJSONObject(format) ?: continue
            val url = f.optString("url")
            if (url.isEmpty()) continue
            return GifFile(url, f.optInt("width", 1).coerceAtLeast(1), f.optInt("height", 1).coerceAtLeast(1))
        }
        return null
    }
}
