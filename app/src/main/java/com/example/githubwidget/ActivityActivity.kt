package com.example.githubwidget

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.NumberFormat
import java.util.Locale

/** Analytics for the cached year: stats, streaks, weekdays, records, insights, achievements. */
class ActivityActivity : AppCompatActivity() {

    private fun dp(v: Int): Int = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activity)

        val root = findViewById<View>(R.id.an_root)
        Ui.edgeToEdge(this, root, top = findViewById(R.id.an_appbar), bottom = root)
        Ui.topBar(this, findViewById<MaterialToolbar>(R.id.an_toolbar))

        val title = findViewById<TextView>(R.id.an_title)
        val updated = findViewById<TextView>(R.id.an_updated)
        val share = findViewById<MaterialButton>(R.id.btn_share)

        val username = Prefs.getUsername(this)
        if (username.isBlank()) {
            // The top bar already says "Activity"; no second title.
            title.visibility = View.GONE
            updated.visibility = View.GONE
            showEmpty(
                "No account yet",
                "Add your GitHub username to see streaks, records, insights and achievements here.",
                "Set up"
            )
            return
        }

        val cached = Cache.load(this)
        if (cached == null || !cached.first.username.equals(username, ignoreCase = true)) {
            title.text = "@$username"
            updated.text = "No data yet"
            showEmpty(
                "No data yet",
                "Connect to the internet and open GH-widgets once to fetch your contributions.",
                "Open GH-widgets"
            )
            return
        }

        val (result, ts) = cached
        title.text = "@${result.username}"
        updated.text = "Updated ${TimeAgo.format(ts)}"
        ActivityUi.bind(window.decorView, result)
        share.setOnClickListener { openShareOptions(result) }
    }

    /** Empty state: sample graph art, why it's empty and one way out (back to Home). */
    private fun showEmpty(headline: String, body: String, action: String) {
        findViewById<View>(R.id.an_content).visibility = View.GONE
        findViewById<View>(R.id.an_empty).visibility = View.VISIBLE
        findViewById<TextView>(R.id.an_empty_title).text = headline
        findViewById<TextView>(R.id.an_empty_body).text = body
        ActivityUi.sampleArt(findViewById(R.id.an_empty_graph))
        findViewById<MaterialButton>(R.id.an_empty_action).apply {
            text = action
            setOnClickListener {
                startActivity(
                    Intent(this@ActivityActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_OPEN_ACCOUNT, Prefs.getUsername(context).isBlank())
                )
                finish()
            }
        }
    }

    private fun openShareOptions(result: ContributionsResult) {
        fun check(label: String, initial: Boolean): MaterialCheckBox =
            MaterialCheckBox(this, null, com.google.android.material.R.attr.checkboxStyle).apply {
                setTextAppearance(R.style.TextAppearance_GH_BodyLarge)
                setTextColor(getColorStateList(R.color.gh_text_enabled))
                minHeight = dp(48)
                text = label
                isChecked = initial
            }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        box.addView(TextView(this).apply {
            text = "Template"
            setTextAppearance(R.style.TextAppearance_GH_Label)
            setTextColor(Ui.color(context, R.color.gh_text_muted))
            setPadding(0, 0, 0, dp(4))
        })
        val templates = ChipGroup(this)
        box.addView(templates)
        val chkDark = check("Dark card", true)
        val chkUser = check("Show username", true)
        val chkTotal = check("Show contribution count", true)
        val chkStreak = check("Show streak", true)
        fun applyTemplate(t: ShareCard.CardTemplate) {
            chkDark.isChecked = t.dark
            chkUser.isChecked = t.showUsername
            chkTotal.isChecked = t.showTotal
            chkStreak.isChecked = t.showStreak
        }
        val chips = Ui.chips(templates, ShareCard.TEMPLATES.map { it.name }) { i ->
            applyTemplate(ShareCard.TEMPLATES[i])
        }
        Ui.select(chips, 0)
        applyTemplate(ShareCard.TEMPLATES[0])
        box.addView(chkDark)
        box.addView(chkUser)
        box.addView(chkTotal)
        box.addView(chkStreak)

        val anchor = findViewById<View>(R.id.an_root)
        MaterialAlertDialogBuilder(this)
            .setTitle("Share contribution card")
            .setView(androidx.core.widget.NestedScrollView(this).apply { addView(box) })
            .setPositiveButton("Generate & share") { _, _ ->
                val opts = ShareCard.Options(
                    dark = chkDark.isChecked,
                    showUsername = chkUser.isChecked,
                    showTotal = chkTotal.isChecked,
                    showStreak = chkStreak.isChecked
                )
                Ui.snack(anchor, "Generating card…")
                Thread {
                    try {
                        val theme = Themes.resolve(
                            Prefs.getThemeId(this), Prefs.getCustomColor(this)
                        )
                        val avatar = ShareCard.fetchAvatar(this, result.username)
                        val bmp = ShareCard.render(
                            result, theme, opts,
                            cornerRadius = WidgetPrefs.shapeRadiusFactor(WidgetPrefs.getShape(this)),
                            gapScale = WidgetPrefs.getSpacing(this),
                            customLevels = Studio.effectiveLevels(this, theme).takeIf { opts.dark },
                            avatar = avatar,
                            context = this
                        )
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            ShareCard.share(this, bmp)
                            bmp.recycle()
                        }
                    } catch (_: Exception) {
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            Ui.snack(anchor, "Couldn't generate the card.")
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

/**
 * View building for the Activity screen. Takes plain views/Context (no Activity)
 * so screenshot tests render exactly what the screen shows. The numbers all come
 * from [Analytics] / [Achievements] / [Stats]; nothing is computed here.
 */
object ActivityUi {

    private fun fmt(n: Int): String = NumberFormat.getInstance(Locale.US).format(n)

    private fun fmt1(d: Double): String =
        if (d >= 10) "${d.toInt()}" else String.format(Locale.US, "%.1f", d)

    private fun days(n: Int): String = if (n == 1) "1 day" else "$n days"

    /** Fills every stats view under [root] from [result]. */
    fun bind(root: View, result: ContributionsResult) {
        val a = Analytics.compute(result.days, result.totalLastYear)
        root.findViewById<View>(R.id.an_empty).visibility = View.GONE
        root.findViewById<View>(R.id.an_content).visibility = View.VISIBLE

        fun stat(id: Int, value: String, label: String) {
            root.findViewById<TextView>(id).text = value
            // The tile card is focusable; read value + label as one item.
            root.findViewById<View>(tileOf(id))?.contentDescription = "$value $label"
        }
        stat(R.id.an_total, fmt(a.total), "contributions in the last 12 months")
        stat(R.id.an_week, fmt(a.thisWeek), "this week")
        stat(R.id.an_month, fmt(a.thisMonth), "this month")
        stat(R.id.an_avg, fmt1(a.avgPerActiveDay), "average per active day")
        stat(R.id.an_active, fmt(a.activeDays), "active days")
        stat(R.id.an_best, fmt(Stats.compute(result.days).bestCount), "on the best day")

        root.findViewById<TextView>(R.id.an_streak).text =
            if (a.currentStreak > 0) days(a.currentStreak) else "—"
        root.findViewById<TextView>(R.id.an_longest).text =
            if (a.longestStreak > 0) days(a.longestStreak) else "—"

        val reached = Achievements.milestoneReached(a.longestStreak)
        val next = Achievements.nextMilestone(a.longestStreak)
        root.findViewById<TextView>(R.id.an_milestone).text = when {
            next > 0 && a.currentStreak > 0 ->
                "${a.currentStreak} of $next days toward the $next-day milestone."
            reached > 0 -> "Milestone reached: $reached-day streak."
            next > 0 -> "Next milestone: $next-day streak."
            else -> "All streak milestones reached."
        }
        root.findViewById<LinearProgressIndicator>(R.id.an_milestone_bar).apply {
            if (next > 0 && a.currentStreak > 0) {
                visibility = View.VISIBLE
                max = next
                setProgressCompat(a.currentStreak.coerceAtMost(next), false)
            } else {
                visibility = View.GONE
            }
        }

        weekdays(root.findViewById(R.id.rows_weekday), a)
        records(root.findViewById(R.id.rows_records), result)
        insights(root.findViewById(R.id.rows_insights), Analytics.insights(a))
        achievements(root.findViewById(R.id.rows_achievements), result.totalLastYear, a)
    }

    private fun tileOf(valueId: Int): Int = when (valueId) {
        R.id.an_total -> R.id.an_total_tile
        R.id.an_week -> R.id.an_week_tile
        R.id.an_month -> R.id.an_month_tile
        R.id.an_avg -> R.id.an_avg_tile
        R.id.an_active -> R.id.an_active_tile
        else -> R.id.an_best_tile
    }

    /** Sample graph for the empty state (clearly decorative: dimmed, not announced). */
    fun sampleArt(image: ImageView) {
        image.setImageBitmap(
            GraphRenderer.render(
                Ui.sampleDays(20),
                scale = 2.5f,
                colors = Themes.GREEN.levels,
                maxWeeks = 20,
                cornerRadius = WidgetPrefs.shapeRadiusFactor(WidgetPrefs.SHAPE_ROUNDED)
            )
        )
    }

    private fun text(ctx: Context, style: Int, color: Int, value: CharSequence): TextView =
        TextView(ctx).apply {
            setTextAppearance(style)
            setTextColor(Ui.color(ctx, color))
            text = value
        }

    private fun meter(parent: LinearLayout): LinearProgressIndicator =
        LayoutInflater.from(parent.context).inflate(R.layout.gh_meter, parent, false)
            as LinearProgressIndicator

    /** One bar per weekday (Monday first); the busiest day in full accent. */
    fun weekdays(container: LinearLayout, a: AnalyticsData) {
        val ctx = container.context
        val d = { v: Int -> Ui.dp(ctx, v) }
        container.removeAllViews()
        val max = a.weekdayTotals.maxOrNull()?.coerceAtLeast(1) ?: 1
        val accent = Ui.color(ctx, R.color.gh_accent)
        val dim = ColorUtils.blendARGB(Ui.color(ctx, R.color.gh_surface), accent, 0.6f)
        // Columns sized to the widest day name / count at the current font scale,
        // so every bar starts and ends at the same x.
        val order = listOf(1, 2, 3, 4, 5, 6, 0)
        val labelW = order.maxOf {
            text(ctx, R.style.TextAppearance_GH_Label, R.color.gh_text_muted, "").paint
                .measureText(Analytics.weekdayName(it).take(3))
        }.toInt() + 1
        val countW = order.maxOf {
            text(ctx, R.style.TextAppearance_GH_Mono, R.color.gh_text, "").paint
                .measureText(fmt(a.weekdayTotals[it]))
        }.toInt() + 1
        for (dow in order) {
            val count = a.weekdayTotals[dow]
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = d(32)
                isFocusable = true
                contentDescription = "${Analytics.weekdayName(dow)}: ${fmt(count)} contributions"
            }
            row.addView(text(ctx, R.style.TextAppearance_GH_Label, R.color.gh_text_muted, Analytics.weekdayName(dow).take(3)).apply {
                width = labelW
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            row.addView(meter(container).apply {
                this.max = max
                setProgressCompat(count, false)
                setIndicatorColor(if (count == max && count > 0) accent else dim)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = d(8)
                    marginEnd = d(12)
                }
            })
            row.addView(text(ctx, R.style.TextAppearance_GH_Mono, R.color.gh_text, fmt(count)).apply {
                gravity = Gravity.END
                width = countW
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            container.addView(row)
        }
        if (a.mostActiveWeekday >= 0) {
            container.addView(
                text(ctx, R.style.TextAppearance_GH_Caption, R.color.gh_text_muted,
                    "Most active: ${Analytics.weekdayName(a.mostActiveWeekday)}").apply {
                    setPadding(0, d(8), 0, 0)
                }
            )
        }
    }

    /** Label / value rows; values in tabular mono. Labels wrap at large font scales. */
    fun records(container: LinearLayout, result: ContributionsResult) {
        val ctx = container.context
        val d = { v: Int -> Ui.dp(ctx, v) }
        container.removeAllViews()
        val r = Achievements.records(result.days)
        if (r.bestCount == 0) {
            container.addView(
                text(ctx, R.style.TextAppearance_GH_Body, R.color.gh_text_muted,
                    "No records yet. Contribute to set your first.").apply {
                    setPadding(0, d(4), 0, d(4))
                }
            )
            return
        }
        val rows = listOf(
            "Longest streak" to if (r.longestStreak > 0) days(r.longestStreak) else "—",
            "Most in a day" to "${fmt(r.bestCount)}${if (r.bestDate.isNotEmpty()) " · ${r.bestDate}" else ""}",
            "Most active month" to r.bestMonth.ifEmpty { "—" },
            "Most active weekday" to if (r.bestWeekday >= 0) Analytics.weekdayName(r.bestWeekday) else "—"
        )
        for ((label, value) in rows) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = d(40)
                isFocusable = true
            }
            row.addView(text(ctx, R.style.TextAppearance_GH_Body, R.color.gh_text_muted, label).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(text(ctx, R.style.TextAppearance_GH_Body, R.color.gh_text, value).apply {
                fontFeatureSettings = "tnum"
                gravity = Gravity.END
                setPadding(d(12), 0, 0, 0)
            })
            container.addView(row)
        }
    }

    fun insights(container: LinearLayout, lines: List<String>) {
        val ctx = container.context
        val d = { v: Int -> Ui.dp(ctx, v) }
        container.removeAllViews()
        for (line in lines) {
            container.addView(
                text(ctx, R.style.TextAppearance_GH_Body, R.color.gh_text, "•  $line").apply {
                    setPadding(0, d(4), 0, d(4))
                }
            )
        }
    }

    /** Icon, title + description, status, and a full-width progress bar while locked. */
    fun achievements(container: LinearLayout, total: Int, a: AnalyticsData) {
        val ctx = container.context
        val d = { v: Int -> Ui.dp(ctx, v) }
        container.removeAllViews()
        val list = Achievements.evaluate(total, a)
        list.forEachIndexed { index, ach ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, d(12), 0, d(12))
                isFocusable = true
                contentDescription = buildString {
                    append(ach.title).append(". ").append(ach.desc).append(". ")
                    append(if (ach.unlocked) "Unlocked" else "Locked")
                    if (!ach.unlocked && ach.progressText.isNotEmpty()) append(", ${ach.progressText}")
                }
            }
            row.addView(ImageView(ctx).apply {
                setImageResource(if (ach.unlocked) R.drawable.ic_ui_check_circle_24 else R.drawable.ic_ui_lock_24)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    Ui.color(ctx, if (ach.unlocked) R.color.gh_accent else R.color.gh_text_disabled)
                )
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(d(24), d(24)).apply { marginEnd = d(16) }
            })
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(text(ctx, R.style.TextAppearance_GH_Section, R.color.gh_text, ach.title).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            head.addView(
                text(
                    ctx, R.style.TextAppearance_GH_Label,
                    if (ach.unlocked) R.color.gh_accent else R.color.gh_text_muted,
                    if (ach.unlocked) "Unlocked" else (ach.progressText.ifEmpty { "Locked" })
                ).apply {
                    if (!ach.unlocked && ach.progressText.isNotEmpty()) {
                        setTextAppearance(R.style.TextAppearance_GH_Mono)
                        setTextColor(Ui.color(ctx, R.color.gh_text_muted))
                    }
                    setPadding(d(8), 0, 0, 0)
                }
            )
            texts.addView(head)
            texts.addView(text(ctx, R.style.TextAppearance_GH_Body, R.color.gh_text_muted, ach.desc).apply {
                setPadding(0, d(2), 0, 0)
            })
            if (!ach.unlocked && ach.progressText.isNotEmpty()) {
                texts.addView(meter(container).apply {
                    this.max = 1000
                    setProgressCompat((ach.progress * 1000).toInt().coerceIn(0, 1000), false)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = d(8) }
                })
            }
            row.addView(texts)
            container.addView(row)
            if (index < list.size - 1) {
                container.addView(View(ctx).apply {
                    setBackgroundColor(Ui.color(ctx, R.color.gh_outline_variant))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, d(1)).apply {
                        marginStart = d(40)
                    }
                })
            }
        }
    }
}
