package com.example.githubwidget

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.Random

/**
 * Shared view builders for app screens, on top of the GH design system
 * (gh_* colors, TextAppearance.GH.*, Widget.GH.* styles).
 *
 * Selection rows are real checkable controls (toggle groups / chips), so
 * TalkBack announces "selected", spacing is handled by the group, and every
 * pick gives a haptic tick. Builders take a Context so screenshot tests can
 * call them without an Activity.
 */
object Ui {

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun color(context: Context, res: Int): Int = ContextCompat.getColor(context, res)

    /** Light haptic tick for a selection change. */
    fun tick(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    // ---------- single-choice rows ----------

    /**
     * Fills [group] with equal-width segments, one per label. [onPick] runs for
     * user taps only (after the group has checked the tapped segment).
     */
    fun segments(
        group: MaterialButtonToggleGroup,
        labels: List<String>,
        onPick: (Int) -> Unit
    ): List<MaterialButton> {
        group.removeAllViews()
        group.isSingleSelection = true
        group.isSelectionRequired = true
        val inflater = LayoutInflater.from(group.context)
        return labels.mapIndexed { i, label ->
            (inflater.inflate(R.layout.gh_segment, group, false) as MaterialButton).also { b ->
                b.id = View.generateViewId()
                b.text = label
                // Large font scales: shrink to fit the segment instead of cutting
                // words ("Rou…"), never above the normal 13sp or below 12dp.
                b.maxLines = 1
                b.ellipsize = null
                TextViewCompat.setAutoSizeTextTypeUniformWithPresetSizes(
                    b, autoSizes(group.context), TypedValue.COMPLEX_UNIT_PX
                )
                b.setOnClickListener {
                    tick(it)
                    onPick(i)
                }
                group.addView(b)
            }
        }
    }

    /** Candidate text sizes (px) from 12dp up to the scaled 13sp label size. */
    private fun autoSizes(context: Context): IntArray {
        val dm = context.resources.displayMetrics
        val min = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, dm).toInt()
        val max = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, dm).toInt()
        return (min..maxOf(min, max)).toList().toIntArray()
    }

    /** Wrapping single-choice chips; for rows with long or many labels. */
    fun chips(group: ChipGroup, labels: List<String>, onPick: (Int) -> Unit): List<Chip> {
        group.removeAllViews()
        group.isSingleSelection = true
        group.isSelectionRequired = true
        val inflater = LayoutInflater.from(group.context)
        return labels.mapIndexed { i, label ->
            (inflater.inflate(R.layout.gh_chip_choice, group, false) as Chip).also { c ->
                c.id = View.generateViewId()
                c.text = label
                c.setOnClickListener {
                    tick(it)
                    onPick(i)
                }
                group.addView(c)
            }
        }
    }

    /** Shows [selected] as checked without firing pick callbacks; -1 clears. */
    fun select(options: List<View>, selected: Int) {
        options.getOrNull(selected)?.let { (it as Checkable).isChecked = true }
        options.forEachIndexed { i, v ->
            if (i != selected && (v as Checkable).isChecked) v.isChecked = false
        }
    }

    // ---------- tiles ----------

    /**
     * Theme tile: palette squares + name. Checkable card, so TalkBack reads
     * "Matrix theme, checked".
     */
    fun themeTile(
        context: Context,
        name: String,
        colors: IntArray,
        selected: Boolean,
        onTap: () -> Unit
    ): MaterialCardView {
        val d = { v: Int -> dp(context, v) }
        val tile = MaterialCardView(context, null, 0).apply {
            radius = d(12).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(color(context, R.color.gh_surface))
            isCheckable = true
            checkedIcon = null
            isChecked = selected
            strokeWidth = if (selected) d(2) else d(1)
            strokeColor = color(context, if (selected) R.color.gh_text else R.color.gh_outline)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = d(8)
            layoutParams = lp
            isClickable = true
            isFocusable = true
            contentDescription = "$name theme"
            setOnClickListener {
                tick(it)
                onTap()
            }
        }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(12), d(12), d(12), d(10))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val squares = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        colors.forEachIndexed { i, c ->
            squares.addView(View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(c)
                    cornerRadius = d(4).toFloat()
                }
                val slp = LinearLayout.LayoutParams(d(18), d(18))
                if (i < colors.size - 1) slp.marginEnd = d(3)
                layoutParams = slp
            })
        }
        inner.addView(squares)
        inner.addView(TextView(context).apply {
            text = name
            setTextAppearance(R.style.TextAppearance_GH_Label)
            setTextColor(color(context, if (selected) R.color.gh_text else R.color.gh_text_muted))
            if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            setPadding(0, d(8), 0, 0)
        })
        tile.addView(inner)
        return tile
    }

    /**
     * 48dp color swatch with a 3:1 outline (visible even for near-black colors)
     * and a hex label under it.
     */
    fun levelSwatch(
        context: Context,
        name: String,
        c: Int,
        onTap: (View) -> Unit
    ): LinearLayout {
        val d = { v: Int -> dp(context, v) }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(MaterialButton(context).apply {
            text = ""
            paintSwatch(this, c)
            layoutParams = LinearLayout.LayoutParams(d(48), d(48))
            contentDescription = "$name level color, ${Themes.toHex(c)}. Double-tap to change"
            setOnClickListener { onTap(it) }
        })
        col.addView(TextView(context).apply {
            text = name
            setTextAppearance(R.style.TextAppearance_GH_Caption)
            setTextColor(color(context, R.color.gh_text_muted))
            gravity = Gravity.CENTER_HORIZONTAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(0, d(4), 0, 0)
        })
        return col
    }

    /** Round color swatch button: fill + outline, no inset. */
    fun paintSwatch(b: MaterialButton, c: Int) {
        val ctx = b.context
        b.backgroundTintList = android.content.res.ColorStateList.valueOf(c)
        b.strokeColor = android.content.res.ColorStateList.valueOf(color(ctx, R.color.gh_outline))
        b.strokeWidth = dp(ctx, 1)
        b.cornerRadius = dp(ctx, 24)
        b.insetTop = 0
        b.insetBottom = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(0, 0, 0, 0)
    }

    /** Preset card: mini graph in the preset's colors, name and one-line description. */
    fun presetCard(
        context: Context,
        name: String,
        description: String,
        thumb: Bitmap,
        onTap: () -> Unit
    ): MaterialCardView {
        val d = { v: Int -> dp(context, v) }
        val card = MaterialCardView(context, null, 0).apply {
            radius = d(12).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(color(context, R.color.gh_surface))
            strokeColor = color(context, R.color.gh_outline)
            strokeWidth = d(1)
            isClickable = true
            isFocusable = true
            contentDescription = "$name preset. $description"
            setOnClickListener {
                tick(it)
                onTap()
            }
        }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(12), d(12), d(12), d(12))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        inner.addView(ImageView(context).apply {
            setImageBitmap(thumb)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            maxHeight = d(72)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        inner.addView(TextView(context).apply {
            text = name
            setTextAppearance(R.style.TextAppearance_GH_Section)
            setTextColor(color(context, R.color.gh_text))
            setPadding(0, d(10), 0, 0)
        })
        inner.addView(TextView(context).apply {
            text = description
            setTextAppearance(R.style.TextAppearance_GH_Caption)
            setTextColor(color(context, R.color.gh_text_muted))
        })
        card.addView(inner)
        return card
    }

    /** Lays out [views] in rows of [perRow] equal columns inside [container]. */
    fun grid(container: LinearLayout, views: List<View>, perRow: Int = 2) {
        val context = container.context
        container.removeAllViews()
        views.chunked(perRow).forEachIndexed { r, chunk ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                if (r > 0) lp.topMargin = dp(context, 8)
                layoutParams = lp
            }
            for (i in 0 until perRow) {
                val v = chunk.getOrNull(i) ?: View(context)
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                if (i > 0) lp.marginStart = dp(context, 8)
                v.layoutParams = lp
                row.addView(v)
            }
            container.addView(row)
        }
    }

    // ---------- sample data ----------

    /**
     * Deterministic fake contribution days ending today, for onboarding art,
     * preset thumbnails and loading skeletons ([empty] = all level 0).
     */
    fun sampleDays(weeks: Int, empty: Boolean = false, seed: Long = 7L): List<Day> {
        val rnd = Random(seed)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        val n = weeks * 7
        cal.add(Calendar.DAY_OF_YEAR, -(n - 1))
        return (0 until n).map {
            val count = if (empty) 0 else {
                val roll = rnd.nextFloat()
                when {
                    roll < 0.25f -> 0
                    roll < 0.6f -> 1 + rnd.nextInt(2)
                    roll < 0.85f -> 3 + rnd.nextInt(3)
                    else -> 6 + rnd.nextInt(10)
                }
            }
            val level = when {
                count == 0 -> 0
                count < 3 -> 1
                count < 6 -> 2
                count < 10 -> 3
                else -> 4
            }
            val d = Day(sdf.format(cal.time), count, level)
            cal.add(Calendar.DAY_OF_YEAR, 1)
            d
        }
    }

    // ---------- window ----------

    /** Sub-screen top bar: the back arrow behaves like system back. */
    fun topBar(activity: ComponentActivity, toolbar: MaterialToolbar) {
        toolbar.setNavigationOnClickListener { activity.onBackPressedDispatcher.onBackPressed() }
    }

    /** Short inline message (instead of a Toast), optionally with one action. */
    fun snack(
        anchor: View,
        msg: CharSequence,
        action: String? = null,
        long: Boolean = false,
        above: View? = null,
        onAction: (() -> Unit)? = null
    ) {
        val bar = Snackbar.make(
            anchor, msg,
            if (long || action != null) Snackbar.LENGTH_LONG
            else Snackbar.LENGTH_SHORT
        )
        if (action != null && onAction != null) bar.setAction(action) { onAction() }
        // Sit above a sticky bottom bar instead of covering it.
        if (above != null) bar.anchorView = above
        bar.show()
    }

    /**
     * Draws the activity edge-to-edge and pads [top] by the status bar, [bottom]
     * by the navigation bar (or keyboard) and [root] by side insets. Initial
     * paddings are kept and added to. Call after setContentView.
     */
    fun edgeToEdge(activity: Activity, root: View, top: View = root, bottom: View = root) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val left0 = root.paddingLeft
        val right0 = root.paddingRight
        val top0 = top.paddingTop
        val bottom0 = bottom.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            root.updatePadding(left = left0 + bars.left, right = right0 + bars.right)
            top.updatePadding(top = top0 + bars.top)
            bottom.updatePadding(bottom = bottom0 + maxOf(bars.bottom, ime.bottom))
            // Consumed: children (e.g. Snackbar) must not add the same insets again.
            WindowInsetsCompat.CONSUMED
        }
    }
}
