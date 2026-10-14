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
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
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

    /**
     * How much to show at the widget's current on-screen size (width and height):
     * weeks that fill the graph area, month labels, footer, compact header.
     */
    private fun sizeClass(context: Context, mgr: AppWidgetManager, widgetId: Int): WidgetCopy.Fit {
        val (wDp, hDp) = sizeDp(context, mgr, widgetId)
        val sysScale = WidgetCopy.systemTextScale(context.resources.displayMetrics)
        return WidgetCopy.fit(wDp, hDp, textScale(context) * sysScale)
    }

    /** Studio text size times the font's own scale (e.g. Press Start 2P runs wide). */
    private fun textScale(context: Context): Float =
        Studio.textScale(Studio.getTextSize(context)) * Fonts.selected(context).scale

    /**
     * The widget's on-screen size in dp for the current orientation (portrait:
     * min width x max height; landscape: max width x min height), or the
     * widget_info default when the launcher hasn't reported one.
     */
    private fun sizeDp(context: Context, mgr: AppWidgetManager, widgetId: Int): Pair<Int, Int> {
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
        return wDp to hDp
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

    private fun configPi(context: Context, widgetId: Int): PendingIntent =
        activityPi(
            context, widgetId, 3,
            Intent(context, WidgetConfigActivity::class.java).putExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId
            )
        )

    /**
     * Tap map: three visible zones, no hidden ones.
     * - whole card (graph, footer, padding) -> activity/analytics screen
     * - header (name + subtitle)            -> GitHub profile
     * - refresh icon (top-end corner)       -> manual refresh
     * Configure: long-press -> "Reconfigure" on Android 12+ (widgetFeatures
     * reconfigurable). Older launchers have no such menu, so there the big total
     * number opens this widget's setup screen.
     * Before a username is set, everything except refresh opens the app.
     * A not-found username sends header taps to this widget's setup instead (see paint).
     */
    private fun bindTaps(context: Context, views: RemoteViews, widgetId: Int, username: String) {
        if (username.isBlank()) {
            val app = activityPi(context, widgetId, 1, Intent(context, MainActivity::class.java))
            views.setOnClickPendingIntent(android.R.id.background, app)
            views.setOnClickPendingIntent(R.id.widget_header, app)
            views.setOnClickPendingIntent(R.id.widget_graph, app)
        } else {
            val activity = activityPi(context, widgetId, 2, Intent(context, ActivityActivity::class.java))
            views.setOnClickPendingIntent(android.R.id.background, activity)
            views.setOnClickPendingIntent(R.id.widget_graph, activity)
            views.setOnClickPendingIntent(
                R.id.widget_header,
                activityPi(
                    context, widgetId, 1,
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$username"))
                )
            )
            views.setOnClickPendingIntent(
                R.id.widget_total,
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) configPi(context, widgetId) else activity
            )
        }
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
        val (wDp, hDp) = sizeDp(context, mgr, widgetId)
        val density = context.resources.displayMetrics.density
        val pxPerDp = density * minOf(1f, MAX_BG_PX / (max(wDp, hDp) * density))
        return Triple(
            (wDp * pxPerDp).roundToInt().coerceAtLeast(1),
            (hDp * pxPerDp).roundToInt().coerceAtLeast(1),
            pxPerDp
        )
    }

    private fun baseViews(context: Context, mgr: AppWidgetManager, widgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, Fonts.widgetLayout(context))
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Clip (and launcher open/close animations) follow the card's own corners.
            views.setViewOutlinePreferredRadius(
                android.R.id.background,
                WidgetInstance.getCorners(context, widgetId).toFloat(),
                TypedValue.COMPLEX_UNIT_DIP
            )
        }
        views.setTextColor(R.id.widget_total, currentTheme(context, widgetId).accent)
        applyTextSize(context, views)
        bindTaps(context, views, widgetId, Prefs.getUsername(context))
        return views
    }

    /** Widget text scales with the Studio text-size setting (base sizes match widget_text.xml). */
    private fun applyTextSize(context: Context, views: RemoteViews) {
        // Studio text size is global; per-widget looks predate it.
        val s = textScale(context)
        views.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, 15f * s)
        views.setTextViewTextSize(R.id.widget_subtitle, TypedValue.COMPLEX_UNIT_SP, 12f * s)
        views.setTextViewTextSize(R.id.widget_total, TypedValue.COMPLEX_UNIT_SP, 22f * s)
        views.setTextViewTextSize(R.id.widget_updated, TypedValue.COMPLEX_UNIT_SP, 11f * s)
    }

    private fun contentOptions(context: Context, widgetId: Int) = WidgetCopy.Options(
        showTotal = WidgetInstance.showTotal(context, widgetId),
        showStreak = WidgetInstance.showStreak(context, widgetId),
        showLongest = WidgetInstance.showLongest(context, widgetId),
        showUpdated = WidgetInstance.showUpdated(context, widgetId),
        showActive = Studio.showActiveDays(context),
        customLabel = Studio.getCustomLabel(context)
    )

    /** Shows [text] in [id], or hides the view when it is null. */
    private fun setOptionalText(views: RemoteViews, id: Int, text: String?) {
        if (text == null) {
            views.setViewVisibility(id, View.GONE)
        } else {
            views.setViewVisibility(id, View.VISIBLE)
            views.setTextViewText(id, text)
        }
    }

    /**
     * Header-only state (no data yet, setup, errors): [title] and [subtitle] over an
     * empty skeleton graph in the theme's level-0 color, so the card keeps its shape.
     */
    private fun paintPlaceholder(
        context: Context,
        widgetId: Int,
        views: RemoteViews,
        size: WidgetCopy.Fit,
        title: String,
        subtitle: String
    ) {
        views.setTextViewText(R.id.widget_title, title)
        setOptionalText(views, R.id.widget_subtitle, subtitle)
        views.setViewVisibility(R.id.widget_total, View.GONE)
        views.setViewVisibility(R.id.widget_updated, View.GONE)
        val weeks = minOf(WidgetPrefs.rangeWeeks(WidgetInstance.getRange(context, widgetId)), size.maxWeeks)
        try {
            views.setImageViewBitmap(
                R.id.widget_graph,
                GraphRenderer.render(
                    WidgetCopy.skeletonDays(weeks),
                    colors = Studio.effectiveLevels(context, currentTheme(context, widgetId)),
                    maxWeeks = weeks,
                    cornerRadius = WidgetPrefs.shapeRadiusFactor(WidgetInstance.getShape(context, widgetId)),
                    gapScale = WidgetInstance.getSpacing(context, widgetId),
                    cellScale = Studio.getCellSize(context)
                )
            )
            views.setContentDescription(R.id.widget_graph, "$title. $subtitle")
            views.setViewVisibility(R.id.widget_graph, View.VISIBLE)
        } catch (_: Exception) {
            views.setViewVisibility(R.id.widget_graph, View.GONE)
        }
    }

    private fun paintResult(
        context: Context,
        widgetId: Int,
        views: RemoteViews,
        result: ContributionsResult,
        stats: ContributionStats,
        size: WidgetCopy.Fit,
        status: String
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
            tintAmount = 0.35f,
            typeface = Fonts.graphLabels(context)
        )
        if (tint != null && !tint.isRecycled) tint.recycle()
        if (tint != null && !tint.isRecycled) tint.recycle()
        val content = WidgetCopy.content(
            style, result, stats, contentOptions(context, widgetId), size, status
        )

        views.setTextViewText(R.id.widget_title, "@${result.username}")
        views.setImageViewBitmap(R.id.widget_graph, bitmap)
        views.setContentDescription(
            R.id.widget_graph,
            "@${result.username}: ${WidgetCopy.formatTotal(result.totalLastYear)} contributions " +
                "in the last year, ${stats.currentStreak} day streak. Tap for activity details."
        )
        views.setViewVisibility(R.id.widget_graph, View.VISIBLE)
        setOptionalText(views, R.id.widget_subtitle, content.subtitle)
        setOptionalText(views, R.id.widget_total, content.total)
        setOptionalText(views, R.id.widget_updated, content.footer)
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
        val size = sizeClass(context, mgr, widgetId)
        // Dimmed icon while the manual refresh runs. Always set: launchers reapply
        // updates onto the existing view, so a dim left unset would stick.
        views.setInt(R.id.widget_refresh, "setImageAlpha", if (refreshing) 90 else 255)

        if (username.isBlank()) {
            paintPlaceholder(
                context, widgetId, views, size,
                "Set up GH-widgets", "Tap to add your GitHub username"
            )
            mgr.updateAppWidget(widgetId, views)
            return
        }

        if (outcome == Refresher.Outcome.NOT_FOUND) {
            paintPlaceholder(context, widgetId, views, size, "@$username", "User not found · tap to fix")
            // "Tap to fix" opens this widget's setup, where the username is edited.
            views.setOnClickPendingIntent(R.id.widget_header, configPi(context, widgetId))
            views.setOnClickPendingIntent(android.R.id.background, configPi(context, widgetId))
            mgr.updateAppWidget(widgetId, views)
            return
        }

        val result = snap.result
        val stats = snap.stats
        if (result != null && stats != null) {
            // Offline or server trouble: keep the cache and note its age.
            val offline = outcome == Refresher.Outcome.OFFLINE || outcome == Refresher.Outcome.FAILED
            val status = WidgetCopy.status(TimeAgo.format(snap.fetchedAt), offline)
            paintResult(context, widgetId, views, result, stats, size, status)
            if (refreshing) {
                views.setViewVisibility(R.id.widget_subtitle, View.VISIBLE)
                views.setTextViewText(R.id.widget_subtitle, "Refreshing…")
            }
        } else if (outcome == Refresher.Outcome.OFFLINE || outcome == Refresher.Outcome.FAILED) {
            paintPlaceholder(context, widgetId, views, size, "@$username", "No connection · tap refresh to retry")
        } else {
            paintPlaceholder(context, widgetId, views, size, "@$username", "Loading contributions…")
        }
        mgr.updateAppWidget(widgetId, views)
    }
}
