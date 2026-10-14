package com.example.githubwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class ContributionWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.example.githubwidget.ACTION_REFRESH"
        const val ACTION_REPAINT = "com.example.githubwidget.ACTION_REPAINT"

        /** Long side cap for the rendered card background, in px. */
        private const val MAX_BG_PX = 900f
    }

    /** When a batch may talk to the server before repainting. */
    enum class Fetch {
        /** Repaint from cache only (resize, look changes with data). */
        NONE,

        /** Fetch only when nothing is cached for the username yet. */
        IF_MISSING,

        /** System/scheduled refresh: only when stale and the device allows it. */
        AUTO,

        /** Refresh icon / app buttons: always ask the server. */
        MANUAL
    }

    /** Cache state shared by every widget in one batch: parsed + stats computed once. */
    private class Snapshot(
        val username: String,
        val result: ContributionsResult?,
        val fetchedAt: Long,
        val stats: ContributionStats?
    )

    /** How much to show, based on the widget's current minimum width. */
    private data class SizeClass(
        val maxWeeks: Int,
        val monthLabels: Boolean,
        val showFooter: Boolean,
        val shortSubtitle: Boolean
    )

    private fun sizeClass(mgr: AppWidgetManager, widgetId: Int): SizeClass {
        return try {
            val opts = mgr.getAppWidgetOptions(widgetId)
            val minW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            when {
                minW < 180 -> SizeClass(maxWeeks = 12, monthLabels = false, showFooter = false, shortSubtitle = true)
                minW < 320 -> SizeClass(maxWeeks = 26, monthLabels = false, showFooter = true, shortSubtitle = false)
                else -> SizeClass(maxWeeks = 52, monthLabels = true, showFooter = true, shortSubtitle = false)
            }
        } catch (_: Exception) {
            SizeClass(maxWeeks = 26, monthLabels = false, showFooter = true, shortSubtitle = false)
        }
    }

    /** System update (widget added, boot, app updated): repaint, fetch only if stale. */
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Refresher.runAsync(pending) {
            // Also covers app updates, where onEnabled never fires again.
            RefreshScheduler.sync(context)
            runBatch(context, ids, Fetch.AUTO, Refresher.TIMEOUT_INTERACTIVE_MS)
        }
    }

    /** Re-render when the user resizes the widget on the homescreen — cache only. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        widgetId: Int,
        newOptions: Bundle
    ) {
        val pending = goAsync()
        Refresher.runAsync(pending) {
            runBatch(context, intArrayOf(widgetId), Fetch.NONE, Refresher.TIMEOUT_INTERACTIVE_MS)
        }
    }

    /** First widget placed: start the periodic background refresh. */
    override fun onEnabled(context: Context) {
        RefreshScheduler.sync(context)
    }

    /** Last widget removed: no more background work at all. */
    override fun onDisabled(context: Context) {
        RefreshScheduler.cancel(context)
        RenderCache.clear(context)
    }

    /** Per-widget files are removed with the widget — never touch another one. */
    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) {
            WidgetInstance.clear(context, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action
        if (action != ACTION_REFRESH && action != ACTION_REPAINT) return
        val mgr = AppWidgetManager.getInstance(context)
        val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
            ?: mgr.getAppWidgetIds(ComponentName(context, ContributionWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val fetch = if (action == ACTION_REFRESH) Fetch.MANUAL else Fetch.IF_MISSING
        val pending = goAsync()
        Refresher.runAsync(pending) {
            // Username may just have been set (widget setup): keep the job in step.
            if (fetch == Fetch.MANUAL) RefreshScheduler.sync(context)
            runBatch(context, ids, fetch, Refresher.TIMEOUT_INTERACTIVE_MS)
        }
    }

    /**
     * One shared fetch (when [fetch] allows it), then one repaint of every
     * widget in [ids] from a single cache load. Runs on [Refresher.executor].
     */
    internal fun runBatch(context: Context, ids: IntArray, fetch: Fetch, timeoutMs: Int) {
        if (ids.isEmpty()) return
        val mgr = AppWidgetManager.getInstance(context)
        val username = Prefs.getUsername(context)
        val doFetch = when (fetch) {
            Fetch.NONE -> false
            Fetch.IF_MISSING -> username.isNotBlank() && !Refresher.hasCacheFor(context, username)
            Fetch.AUTO -> Refresher.shouldAutoFetch(context)
            Fetch.MANUAL -> username.isNotBlank()
        }
        if (!doFetch) {
            paintAll(context, mgr, ids, Refresher.Outcome.SKIPPED)
            return
        }
        if (fetch == Fetch.MANUAL) {
            // Manual taps get feedback in the widget itself instead of a toast.
            paintAll(context, mgr, ids, Refresher.Outcome.SKIPPED, refreshing = true)
        } else if (!Refresher.hasCacheFor(context, username)) {
            // A brand-new widget would sit on the bare layout during the fetch.
            paintAll(context, mgr, ids, Refresher.Outcome.SKIPPED)
        }
        val outcome = Refresher.fetch(context, username, timeoutMs)
        paintAll(context, mgr, ids, outcome)
    }

    private fun snapshot(context: Context): Snapshot {
        val username = Prefs.getUsername(context)
        val cached = Cache.load(context)?.takeIf {
            username.isNotBlank() && it.first.username.equals(username, ignoreCase = true)
        }
        return Snapshot(
            username,
            cached?.first,
            cached?.second ?: 0L,
            cached?.first?.let { Stats.compute(it.days) }
        )
    }

    private fun paintAll(
        context: Context,
        mgr: AppWidgetManager,
        ids: IntArray,
        outcome: Refresher.Outcome,
        refreshing: Boolean = false
    ) {
        val snap = snapshot(context)
        for (id in ids) {
            try {
                paint(context, mgr, id, snap, outcome, refreshing)
            } catch (_: Exception) {
                // One broken widget must not block the others.
            }
        }
    }

    private fun activityPi(context: Context, widgetId: Int, code: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context, widgetId * 10 + code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * Tap map (predictable everywhere):
     * header -> GitHub profile (or app when no username yet)
     * graph  -> activity/analytics screen
     * total  -> this widget's own configuration
     * footer -> app home (customize defaults)
     * refresh icon -> manual refresh
     */
    private fun bindTaps(context: Context, views: RemoteViews, widgetId: Int, username: String) {
        val profileIntent = if (username.isBlank()) {
            Intent(context, MainActivity::class.java)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$username"))
        }
        views.setOnClickPendingIntent(
            R.id.widget_header, activityPi(context, widgetId, 1, profileIntent)
        )
        views.setOnClickPendingIntent(
            R.id.widget_graph,
            activityPi(context, widgetId, 2, Intent(context, ActivityActivity::class.java))
        )
        views.setOnClickPendingIntent(
            R.id.widget_total,
            activityPi(
                context, widgetId, 3,
                Intent(context, WidgetConfigActivity::class.java).putExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId
                )
            )
        )
        views.setOnClickPendingIntent(
            R.id.widget_updated,
            activityPi(context, widgetId, 4, Intent(context, MainActivity::class.java))
        )
        val refresh = Intent(context, ContributionWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
        }
        views.setOnClickPendingIntent(
            R.id.widget_refresh,
            PendingIntent.getBroadcast(
                context, widgetId * 10 + 5, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }

    private fun currentTheme(context: Context, widgetId: Int): GraphTheme =
        Themes.resolve(
            WidgetInstance.getThemeId(context, widgetId),
            WidgetInstance.getCustomColor(context, widgetId)
        )

    /**
     * The widget's on-screen size in px (current orientation), capped at
     * [MAX_BG_PX] on the long side, plus px per dp at that scale.
     */
    private fun bgSize(context: Context, mgr: AppWidgetManager, widgetId: Int): Triple<Int, Int, Float> {
        var wDp = 0
        var hDp = 0
        try {
            val opts = mgr.getAppWidgetOptions(widgetId)
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            wDp = opts.getInt(
                if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0
            )
            hDp = opts.getInt(
                if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0
            )
        } catch (_: Exception) {
            // Fall through to the default size.
        }
        if (wDp <= 0 || hDp <= 0) {
            wDp = 250 // widget_info minWidth x minHeight
            hDp = 110
        }
        val density = context.resources.displayMetrics.density
        val pxPerDp = density * minOf(1f, MAX_BG_PX / (max(wDp, hDp) * density))
        return Triple(
            (wDp * pxPerDp).roundToInt().coerceAtLeast(1),
            (hDp * pxPerDp).roundToInt().coerceAtLeast(1),
            pxPerDp
        )
    }

    private fun baseViews(context: Context, mgr: AppWidgetManager, widgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_contribution)
        val style = WidgetInstance.getStyle(context, widgetId)
        // Configured widgets keep their own opacity; otherwise the Studio
        // background opacity rules (it migrates the old card opacity).
        val opacity = if (WidgetInstance.hasCustom(context, widgetId)) {
            WidgetInstance.getOpacity(context, widgetId)
        } else {
            Studio.getBgOpacity(context)
        }
        // Glass style floats at reduced opacity with a lighter rim.
        val alpha = if (style == WidgetPrefs.STYLE_GLASS) minOf(opacity, 45) else opacity
        val border = if (style == WidgetPrefs.STYLE_GLASS) {
            Color.parseColor("#8B949E")
        } else {
            Color.parseColor("#30363D")
        }
        // Rendered at the widget's real aspect so fitXY never stretches the corners.
        val (w, h, pxPerDp) = bgSize(context, mgr, widgetId)
        val cornerPx = WidgetInstance.getCorners(context, widgetId) * pxPerDp
        val bg = CardRenderer.card(
            context, w, h, cornerPx, border, alpha,
            strokePx = pxPerDp.coerceAtLeast(1f), useCache = true
        )
        views.setImageViewBitmap(R.id.widget_bg, bg)
        views.setTextColor(R.id.widget_total, currentTheme(context, widgetId).accent)
        applyTextSize(context, widgetId, views)
        bindTaps(context, views, widgetId, Prefs.getUsername(context))
        return views
    }

    /** Widget text scales with the Studio text-size setting. */
    private fun applyTextSize(context: Context, widgetId: Int, views: RemoteViews) {
        // Studio text size is global; per-widget looks predate it.
        val s = Studio.textScale(Studio.getTextSize(context))
        views.setTextViewTextSize(R.id.widget_title, android.util.TypedValue.COMPLEX_UNIT_SP, 14f * s)
        views.setTextViewTextSize(R.id.widget_subtitle, android.util.TypedValue.COMPLEX_UNIT_SP, 11f * s)
        views.setTextViewTextSize(R.id.widget_total, android.util.TypedValue.COMPLEX_UNIT_SP, 20f * s)
        views.setTextViewTextSize(R.id.widget_updated, android.util.TypedValue.COMPLEX_UNIT_SP, 10f * s)
    }

    private fun formatTotal(total: Int): String =
        NumberFormat.getInstance(Locale.US).format(total)

    private fun paintResult(
        context: Context,
        widgetId: Int,
        views: RemoteViews,
        result: ContributionsResult,
        stats: ContributionStats,
        size: SizeClass,
        footer: String
    ) {
        val theme = currentTheme(context, widgetId)
        val style = WidgetInstance.getStyle(context, widgetId)
        val weeks = minOf(WidgetPrefs.rangeWeeks(WidgetInstance.getRange(context, widgetId)), size.maxWeeks)
        // Optional image-through-graph: sample the background image once at
        // graph resolution so cells inherit its hues without hiding intensity.
        val tint = if (Studio.imageThroughGraph(context) &&
            Studio.getBgType(context) == Studio.BG_IMAGE &&
            Studio.getImageUri(context).isNotEmpty()
        ) {
            CardRenderer.sampleImageCached(context, Studio.getImageUri(context), weeks, 7, Studio.SCALE_FILL)
        } else {
            null
        }
        val bitmap: Bitmap = GraphRenderer.render(
            result.days,
            colors = Studio.effectiveLevels(context, theme),
            maxWeeks = weeks,
            showMonthLabels = size.monthLabels,
            cornerRadius = WidgetPrefs.shapeRadiusFactor(WidgetInstance.getShape(context, widgetId)),
            gapScale = WidgetInstance.getSpacing(context, widgetId),
            cellScale = Studio.getCellSize(context),
            tint = tint,
            tintAmount = 0.35f
        )
        if (tint != null && !tint.isRecycled) tint.recycle()
        val today = result.days.lastOrNull()?.count ?: 0
        val total = formatTotal(result.totalLastYear)

        views.setTextViewText(R.id.widget_title, "@${result.username}")
        views.setImageViewBitmap(R.id.widget_graph, bitmap)
        views.setContentDescription(
            R.id.widget_graph,
            "@${result.username}: $total contributions in the last year, " +
                "${stats.currentStreak} day streak. Tap for activity details."
        )
        views.setViewVisibility(R.id.widget_graph, View.VISIBLE)

        when (style) {
            WidgetPrefs.STYLE_MINIMAL -> {
                // Graph only.
                views.setViewVisibility(R.id.widget_subtitle, View.GONE)
                views.setViewVisibility(R.id.widget_total, View.GONE)
                views.setViewVisibility(R.id.widget_updated, View.GONE)
            }
            WidgetPrefs.STYLE_TERMINAL -> {
                // Count-first, no big number.
                val parts = ArrayList<String>()
                parts.add("$total contributions")
                parts.add("today $today")
                if (WidgetInstance.showStreak(context, widgetId)) parts.add("${stats.currentStreak}d streak")
                if (WidgetInstance.showLongest(context, widgetId)) parts.add("${stats.longestStreak}d longest")
                views.setViewVisibility(R.id.widget_subtitle, View.VISIBLE)
                views.setTextViewText(R.id.widget_subtitle, parts.joinToString(" • "))
                views.setViewVisibility(R.id.widget_total, View.GONE)
                setFooter(views, size, context, widgetId, stats, footer)
            }
            else -> {
                val parts = ArrayList<String>()
                parts.add("Today: $today")
                if (WidgetInstance.showStreak(context, widgetId)) parts.add("${stats.currentStreak}d streak")
                if (WidgetInstance.showTotal(context, widgetId)) parts.add("$total/yr")
                if (Studio.showActiveDays(context) && !size.shortSubtitle) {
                    parts.add("${stats.activeDays} active")
                }
                views.setViewVisibility(R.id.widget_subtitle, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_subtitle,
                    if (size.shortSubtitle && parts.size > 2) {
                        "${parts[0]} • ${parts[1]}"
                    } else {
                        parts.joinToString(" • ")
                    }
                )
                if (WidgetInstance.showTotal(context, widgetId)) {
                    views.setViewVisibility(R.id.widget_total, View.VISIBLE)
                    views.setTextViewText(R.id.widget_total, total)
                } else {
                    views.setViewVisibility(R.id.widget_total, View.GONE)
                }
                setFooter(views, size, context, widgetId, stats, footer)
            }
        }
    }

    private fun setFooter(
        views: RemoteViews,
        size: SizeClass,
        context: Context,
        widgetId: Int,
        stats: ContributionStats,
        footer: String
    ) {
        val show = WidgetInstance.showUpdated(context, widgetId) && size.showFooter
        if (!show) {
            views.setViewVisibility(R.id.widget_updated, View.GONE)
            return
        }
        // A custom label takes over the footer line when set.
        val label = Studio.getCustomLabel(context)
        var text = footer
        if (label.isNotEmpty()) {
            text = label
        } else if (WidgetInstance.showLongest(context, widgetId)) {
            text = text.replace(" • tap refresh icon", " • ${stats.longestStreak}d longest • tap refresh icon")
                .replace("Last updated", "Updated")
        }
        views.setViewVisibility(R.id.widget_updated, View.VISIBLE)
        views.setTextViewText(R.id.widget_updated, text)
    }

    /**
     * Paint one widget from the batch snapshot. Never touches the network,
     * so the widget is never blank and works fully offline; [outcome] only
     * picks the status wording.
     */
    private fun paint(
        context: Context,
        mgr: AppWidgetManager,
        widgetId: Int,
        snap: Snapshot,
        outcome: Refresher.Outcome,
        refreshing: Boolean
    ) {
        val views = baseViews(context, mgr, widgetId)
        val username = snap.username

        if (username.isBlank()) {
            views.setTextViewText(R.id.widget_title, "Tap to open GH-widgets")
            views.setTextViewText(R.id.widget_subtitle, "Enter your GitHub username")
            views.setViewVisibility(R.id.widget_subtitle, View.VISIBLE)
            views.setViewVisibility(R.id.widget_graph, View.GONE)
            views.setViewVisibility(R.id.widget_total, View.GONE)
            views.setViewVisibility(R.id.widget_updated, View.GONE)
            mgr.updateAppWidget(widgetId, views)
            return
        }

        if (outcome == Refresher.Outcome.NOT_FOUND) {
            views.setTextViewText(R.id.widget_title, "@$username")
            views.setTextViewText(R.id.widget_subtitle, "User not found — tap to fix")
            mgr.updateAppWidget(widgetId, views)
            return
        }

        val result = snap.result
        val stats = snap.stats
        if (result != null && stats != null) {
            val ago = TimeAgo.format(snap.fetchedAt)
            val footer = when (outcome) {
                Refresher.Outcome.UPDATED, Refresher.Outcome.UNCHANGED ->
                    "Updated $ago • tap refresh icon to refresh"
                Refresher.Outcome.OFFLINE, Refresher.Outcome.FAILED ->
                    // Offline or server trouble: keep the cache and note its age.
                    "Last updated $ago • tap refresh icon to retry"
                else -> "Last updated $ago"
            }
            paintResult(context, widgetId, views, result, stats, sizeClass(mgr, widgetId), footer)
            if (refreshing) {
                views.setViewVisibility(R.id.widget_subtitle, View.VISIBLE)
                views.setTextViewText(R.id.widget_subtitle, "Refreshing…")
            }
        } else if (outcome == Refresher.Outcome.OFFLINE || outcome == Refresher.Outcome.FAILED) {
            views.setTextViewText(R.id.widget_title, "@$username")
            views.setTextViewText(R.id.widget_subtitle, "No connection — tap refresh icon to retry")
        } else {
            views.setTextViewText(R.id.widget_title, "@$username")
            views.setTextViewText(R.id.widget_subtitle, "Loading contributions…")
            views.setViewVisibility(R.id.widget_subtitle, View.VISIBLE)
            views.setViewVisibility(R.id.widget_graph, View.GONE)
            views.setViewVisibility(R.id.widget_total, View.GONE)
            views.setViewVisibility(R.id.widget_updated, View.GONE)
        }
        mgr.updateAppWidget(widgetId, views)
    }
}
