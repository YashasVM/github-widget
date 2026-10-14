package com.example.githubwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color

object Prefs {
    private const val NAME = "github_widget_prefs"
    private const val KEY_USERNAME = "username"
    private const val KEY_THEME = "theme"
    private const val KEY_OPACITY = "opacity"
    private const val KEY_CUSTOM_COLOR = "custom_color"
    private const val KEY_REFRESH_HOURS = "refresh_interval_hours"
    private const val KEY_REFRESH_WIFI_ONLY = "refresh_wifi_only"

    /** Background refresh choices in hours; 0 = off (manual refresh only). */
    val REFRESH_HOURS = intArrayOf(0, 1, 3, 6, 12, 24)
    const val DEFAULT_REFRESH_HOURS = 6

    fun getUsername(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_USERNAME, "") ?: ""

    fun setUsername(context: Context, username: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_USERNAME, username.trim()).apply()
        RefreshScheduler.sync(context)
    }

    /** Background refresh interval in hours; 0 = off. */
    fun getRefreshHours(context: Context): Int {
        val h = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getInt(KEY_REFRESH_HOURS, DEFAULT_REFRESH_HOURS)
        return if (h in REFRESH_HOURS) h else DEFAULT_REFRESH_HOURS
    }

    fun setRefreshHours(context: Context, hours: Int) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_REFRESH_HOURS, hours).apply()
        RefreshScheduler.sync(context)
    }

    /** Background refreshes wait for an unmetered (Wi-Fi) network. */
    fun isWifiOnly(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_REFRESH_WIFI_ONLY, false)

    fun setWifiOnly(context: Context, wifiOnly: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_REFRESH_WIFI_ONLY, wifiOnly).apply()
        RefreshScheduler.sync(context)
    }

    fun getThemeId(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME, "green") ?: "green"

    fun setThemeId(context: Context, id: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, id).apply()
    }

    /** Card opacity 20..100 (percent). */
    fun getOpacity(context: Context): Int =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getInt(KEY_OPACITY, 100).coerceIn(20, 100)

    fun setOpacity(context: Context, opacity: Int) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_OPACITY, opacity.coerceIn(20, 100)).apply()
    }

    /** User-picked custom graph color (used when theme == "custom"). */
    fun getCustomColor(context: Context): Int =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CUSTOM_COLOR, Color.parseColor("#39D353"))

    fun setCustomColor(context: Context, color: Int) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_CUSTOM_COLOR, color).apply()
    }

    /** Manual refresh: always asks the server (unless it was asked seconds ago). */
    fun requestRefresh(context: Context) {
        broadcast(context, ContributionWidgetProvider.ACTION_REFRESH)
    }

    /**
     * Repaints widgets from cache only — no network. Use for look-only
     * changes (theme, style, transparency) so they apply instantly offline.
     */
    fun requestRepaint(context: Context) {
        broadcast(context, ContributionWidgetProvider.ACTION_REPAINT)
    }

    private fun broadcast(context: Context, action: String) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, ContributionWidgetProvider::class.java))
        val intent = Intent(context, ContributionWidgetProvider::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        context.sendBroadcast(intent)
    }
}
