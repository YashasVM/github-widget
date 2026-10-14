package com.example.githubwidget

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local cache for the last successful contribution fetch.
 *
 * Flow: API -> save() -> app + widget read instantly, even offline.
 * A background refresh then updates the cache + UI when network works.
 */
object Cache {

    private const val NAME = "gh_widget_cache"
    private const val KEY_DATA = "data_json"
    private const val KEY_TIME = "fetched_at"
    private const val KEY_ETAG = "etag"

    /** Last parsed dataset, reused while the stored JSON is unchanged. */
    private class Memo(val raw: String, val result: ContributionsResult)

    @Volatile
    private var memo: Memo? = null

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences(NAME, android.content.Context.MODE_PRIVATE)

    fun save(context: android.content.Context, result: ContributionsResult, etag: String? = null) {
        val days = JSONArray()
        for (d in result.days) {
            days.put(
                JSONObject()
                    .put("date", d.date)
                    .put("count", d.count)
                    .put("level", d.level)
            )
        }
        val root = JSONObject()
            .put("username", result.username)
            .put("total", result.totalLastYear)
            .put("days", days)
        val raw = root.toString()
        val edit = prefs(context)
            .edit()
            .putString(KEY_DATA, raw)
            .putLong(KEY_TIME, System.currentTimeMillis())
        if (etag.isNullOrEmpty()) edit.remove(KEY_ETAG) else edit.putString(KEY_ETAG, etag)
        edit.apply()
        memo = Memo(raw, result)
    }

    /** Server said "unchanged" (HTTP 304): only the check time moves. */
    fun touch(context: android.content.Context, etag: String?) {
        val edit = prefs(context).edit().putLong(KEY_TIME, System.currentTimeMillis())
        if (!etag.isNullOrEmpty()) edit.putString(KEY_ETAG, etag)
        edit.apply()
    }

    /** When the cache was last confirmed against the server (0 = never). Cheap: no JSON parse. */
    fun fetchedAt(context: android.content.Context): Long = prefs(context).getLong(KEY_TIME, 0L)

    /** ETag of the cached response, only when it belongs to [username]. */
    fun etagFor(context: android.content.Context, username: String): String? {
        val p = prefs(context)
        val etag = p.getString(KEY_ETAG, null) ?: return null
        val cached = load(context) ?: return null
        return if (cached.first.username.equals(username, ignoreCase = true)) etag else null
    }

    /** Returns the cached dataset + fetch timestamp, or null if nothing cached. */
    fun load(context: android.content.Context): Pair<ContributionsResult, Long>? {
        val prefs = prefs(context)
        val raw = prefs.getString(KEY_DATA, null) ?: return null
        val ts = prefs.getLong(KEY_TIME, 0L)
        memo?.let { m ->
            if (m.raw === raw || m.raw == raw) return m.result to ts
        }
        return try {
            val root = JSONObject(raw)
            val arr = root.getJSONArray("days")
            val days = ArrayList<Day>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                days.add(Day(o.getString("date"), o.optInt("count", 0), o.optInt("level", 0)))
            }
            val result = ContributionsResult(
                root.getString("username"),
                root.optInt("total", 0),
                days
            )
            memo = Memo(raw, result)
            result to ts
        } catch (_: Exception) {
            null
        }
    }
}

/** Human-readable relative timestamps: "just now", "8 min ago", "2h ago". */
object TimeAgo {

    fun format(ts: Long): String {
        if (ts <= 0L) return "never"
        val mins = ((System.currentTimeMillis() - ts) / 60000).coerceAtLeast(0)
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 60 * 24 -> "${mins / 60}h ago"
            mins < 60 * 24 * 30 -> "${mins / (60 * 24)}d ago"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ts))
        }
    }
}
