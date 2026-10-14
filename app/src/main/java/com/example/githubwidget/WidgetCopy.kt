package com.example.githubwidget

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.Random

/**
 * What the homescreen widget shows and how it fits its size. Pure functions,
 * shared by [ContributionWidgetProvider] (RemoteViews) and the live preview on
 * [WidgetConfigActivity], so both always agree.
 */
object WidgetCopy {

    /**
     * How much the widget shows at its current size.
     *
     * @param maxWeeks graph columns that fill the graph area without large empty bands
     * @param compact narrow widget (about 2 cells): no big total, short subtitle
     */
    data class Fit(
        val maxWeeks: Int,
        val monthLabels: Boolean,
        val showFooter: Boolean,
        val compact: Boolean
    )

    /** Texts for one paint; null hides that view. */
    class Content(val subtitle: String?, val total: String?, val footer: String?)

    /** The user's per-widget content toggles. */
    data class Options(
        val showTotal: Boolean,
        val showStreak: Boolean,
        val showLongest: Boolean,
        val showUpdated: Boolean,
        val showActive: Boolean,
        val customLabel: String
    )

    /**
     * How much the system font size enlarges sp text, measured at the widget's
     * 12sp subtitle size (non-linear on Android 14+). The widget's texts are in sp,
     * so [fit] must budget for it or a large font squeezes the graph away.
     */
    fun systemTextScale(dm: android.util.DisplayMetrics): Float =
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 12f, dm) /
            (12f * dm.density)

    fun formatTotal(total: Int): String = NumberFormat.getInstance(Locale.US).format(total)

    /**
     * Size classes from the widget's size in dp. [textScale] is the Studio text
     * size times the font's scale times [systemTextScale]. The graph gets whatever height is left under
     * the header (and footer, when it fits), and the week count is picked so the
     * graph's aspect roughly matches that area: wide and short shows more weeks,
     * tall shows fewer, bigger cells. The chosen range still caps it.
     */
    fun fit(wDp: Int, hDp: Int, textScale: Float): Fit {
        val compact = wDp < 200
        val header = 36f * textScale // 15sp title + 12sp subtitle lines
        val footer = 18f * textScale // 4dp gap + 11sp line
        val chrome = 24f + 6f // vertical padding + graph gap
        val showFooter = hDp - chrome - header - footer >= 44f
        val graphH = (hDp - chrome - header - if (showFooter) footer else 0f).coerceAtLeast(24f)
        val graphW = (wDp - 28f).coerceAtLeast(40f)
        val labels = !compact && graphW >= 260f && graphH >= 64f
        // GraphRenderer units at scale 1: width 14 + 12n, height 98 (+13 with month labels).
        val unitsH = if (labels) 111f else 98f
        // Allow ~10% vertical slack before trading weeks for bigger cells.
        val weeks = (((graphW / graphH) * unitsH * 1.1f - 14f) / 12f).toInt()
        return Fit(weeks.coerceIn(13, 52), labels, showFooter, compact)
    }

    /** Footer status line, without instructions (the refresh icon speaks for itself). */
    fun status(ago: String, offline: Boolean): String =
        if (offline) "Offline · updated $ago" else "Updated $ago"

    fun content(
        style: String,
        result: ContributionsResult,
        stats: ContributionStats,
        opts: Options,
        fit: Fit,
        status: String
    ): Content {
        val today = result.days.lastOrNull()?.count ?: 0
        val total = formatTotal(result.totalLastYear)
        val footer = footer(opts, fit, stats, status)
        return when (style) {
            // Graph only.
            WidgetPrefs.STYLE_MINIMAL -> Content(null, null, null)
            WidgetPrefs.STYLE_TERMINAL -> {
                // Count-first, no big number.
                val parts = ArrayList<String>()
                parts.add(if (fit.compact) total else "$total contributions")
                parts.add("today $today")
                if (opts.showStreak) parts.add("${stats.currentStreak}d streak")
                if (opts.showLongest) parts.add("${stats.longestStreak}d longest")
                Content(parts.take(if (fit.compact) 2 else parts.size).joinToString(" • "), null, footer)
            }
            else -> if (fit.compact) {
                // Too narrow for the big number: fold it into the one-line subtitle.
                val parts = ArrayList<String>()
                if (opts.showTotal) parts.add(total)
                if (opts.showStreak) parts.add("${stats.currentStreak}d streak")
                if (parts.isEmpty()) parts.add("Today: $today")
                Content(parts.joinToString(" • "), null, footer)
            } else {
                val parts = ArrayList<String>()
                parts.add("Today: $today")
                if (opts.showStreak) parts.add("${stats.currentStreak}d streak")
                // The big number already shows the total; only repeat it when hidden.
                if (opts.showActive) parts.add("${stats.activeDays} active")
                Content(parts.joinToString(" • "), if (opts.showTotal) total else null, footer)
            }
        }
    }

    private fun footer(opts: Options, fit: Fit, stats: ContributionStats, status: String): String? {
        if (!opts.showUpdated || !fit.showFooter) return null
        // A custom label takes over the footer line when set.
        if (opts.customLabel.isNotEmpty()) return opts.customLabel
        return if (opts.showLongest) "$status • ${stats.longestStreak}d longest" else status
    }

    /** [weeks] of empty days ending today: the skeleton graph while loading / before setup. */
    fun skeletonDays(weeks: Int): List<Day> = days(weeks) { _ -> 0 }

    /**
     * A believable sample year (fixed seed) for previews when nothing is cached yet.
     * Never shown as real data: the setup preview labels it "@you".
     */
    fun sampleResult(username: String): ContributionsResult {
        val rnd = Random(7)
        val days = days(53) { weekend ->
            val roll = rnd.nextFloat()
            when {
                roll < (if (weekend) 0.55f else 0.2f) -> 0
                roll < 0.6f -> 1 + rnd.nextInt(3)
                roll < 0.85f -> 3 + rnd.nextInt(5)
                else -> 7 + rnd.nextInt(12)
            }
        }
        return ContributionsResult(username, days.sumOf { it.count }, days)
    }

    private fun days(weeks: Int, count: (weekend: Boolean) -> Int): List<Day> {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(weeks * 7 - 1))
        val out = ArrayList<Day>(weeks * 7)
        repeat(weeks * 7) {
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val c = count(dow == Calendar.SATURDAY || dow == Calendar.SUNDAY)
            val level = when {
                c == 0 -> 0
                c < 3 -> 1
                c < 6 -> 2
                c < 10 -> 3
                else -> 4
            }
            out.add(Day(sdf.format(cal.time), c, level))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }
}
