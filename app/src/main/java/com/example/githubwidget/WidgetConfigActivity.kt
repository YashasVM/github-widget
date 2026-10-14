package com.example.githubwidget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Setup screen for ONE widget instance (V1.9).
 *
 * Launched by the launcher when a widget is added (configure flow) and, on
 * Android 12+, from the widget's long-press "Reconfigure" (on older launchers:
 * the widget's total). Saves per-widget settings; the app's screen keeps
 * holding the defaults for new widgets. A live preview at the top shows the
 * in-progress choices with the same renderers the widget uses.
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var themeId: String = "green"
    private var styleId: String = WidgetPrefs.STYLE_CLASSIC
    private var rangeId: String = WidgetPrefs.RANGE_6M

    private lateinit var input: TextInputEditText
    private lateinit var slider: Slider
    private lateinit var chkTotal: MaterialCheckBox
    private lateinit var chkStreak: MaterialCheckBox
    private lateinit var chkLongest: MaterialCheckBox
    private lateinit var chkUpdated: MaterialCheckBox
    private lateinit var preview: LinearLayout
    private lateinit var rowThemes: LinearLayout

    /** Preview renders run off the UI thread; only the newest one is shown. */
    private val renderer: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile
    private var renderGen = 0

    /** Cached contributions (loaded once, off the UI thread) and when they were fetched. */
    @Volatile
    private var cached: Pair<ContributionsResult, Long>? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        // Launcher configure flow needs RESULT_CANCELED until we save.
        setResult(RESULT_CANCELED)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        setContentView(R.layout.activity_widget_config)
        applyInsets()

        input = findViewById(R.id.cfg_username)
        val inputLayout = findViewById<TextInputLayout>(R.id.cfg_username_layout)
        rowThemes = findViewById(R.id.cfg_themes)
        val rowStyles = findViewById<ChipGroup>(R.id.cfg_styles)
        val rowRanges = findViewById<MaterialButtonToggleGroup>(R.id.cfg_ranges)
        val opacityText = findViewById<TextView>(R.id.cfg_opacity_text)
        slider = findViewById(R.id.cfg_opacity)
        chkTotal = findViewById(R.id.cfg_total)
        chkStreak = findViewById(R.id.cfg_streak)
        chkLongest = findViewById(R.id.cfg_longest)
        chkUpdated = findViewById(R.id.cfg_updated)
        preview = findViewById(R.id.cfg_preview)
        val save = findViewById<MaterialButton>(R.id.cfg_save)

        // Start from this widget's effective settings (own or global fallback).
        input.setText(Prefs.getUsername(this))
        themeId = WidgetInstance.getThemeId(this, widgetId)
        styleId = WidgetInstance.getStyle(this, widgetId)
        rangeId = WidgetInstance.getRange(this, widgetId)
        // Same rule as the widget (ContributionWidgetProvider.baseViews): its own
        // opacity once configured, otherwise the Studio background opacity.
        val opacity = if (WidgetInstance.hasCustom(this, widgetId)) {
            WidgetInstance.getOpacity(this, widgetId)
        } else {
            Studio.getBgOpacity(this)
        }
        slider.value = (opacity.coerceIn(20, 100) / 5 * 5).toFloat()
        opacityText.text = "${slider.value.toInt()}%"
        chkTotal.isChecked = WidgetInstance.showTotal(this, widgetId)
        chkStreak.isChecked = WidgetInstance.showStreak(this, widgetId)
        chkLongest.isChecked = WidgetInstance.showLongest(this, widgetId)
        chkUpdated.isChecked = WidgetInstance.showUpdated(this, widgetId)
        // Reconfiguring an existing widget saves; a new one gets added.
        save.setText(if (WidgetInstance.hasCustom(this, widgetId)) R.string.cfg_save else R.string.cfg_add)

        buildThemeTiles()

        // Style: single-choice chips (TalkBack reads "checked").
        val styleIds = WidgetPrefs.STYLES
        styleIds.forEach { id ->
            val chip = (layoutInflater.inflate(R.layout.cfg_style_chip, rowStyles, false) as Chip).apply {
                this.id = View.generateViewId()
                tag = id
                text = WidgetPrefs.STYLE_NAMES[id] ?: id
                isChecked = id == styleId
            }
            rowStyles.addView(chip)
        }
        rowStyles.setOnCheckedStateChangeListener { group, ids ->
            val id = ids.firstOrNull() ?: return@setOnCheckedStateChangeListener
            styleId = group.findViewById<Chip>(id).tag as String
            renderPreview()
        }

        // Range: segmented toggle group.
        val rangeButtons = mapOf(
            R.id.cfg_range_3m to WidgetPrefs.RANGE_3M,
            R.id.cfg_range_6m to WidgetPrefs.RANGE_6M,
            R.id.cfg_range_12m to WidgetPrefs.RANGE_12M
        )
        rowRanges.check(rangeButtons.entries.firstOrNull { it.value == rangeId }?.key ?: R.id.cfg_range_6m)
        rowRanges.addOnButtonCheckedListener { _, buttonId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            rangeId = rangeButtons[buttonId] ?: WidgetPrefs.RANGE_6M
            renderPreview()
        }

        slider.setLabelFormatter { "${it.toInt()}%" }
        slider.addOnChangeListener { _, value, _ ->
            opacityText.text = "${value.toInt()}%"
            renderPreview()
        }
        listOf(chkTotal, chkStreak, chkLongest, chkUpdated).forEach { c ->
            c.setOnCheckedChangeListener { _, _ -> renderPreview() }
        }

        input.doAfterTextChanged {
            inputLayout.error = null
            renderPreview()
        }
        input.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) v.clearFocus()
            false
        }

        // The preview's card bitmap is drawn at the preview's real size.
        preview.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) renderPreview()
        }

        findViewById<View>(R.id.cfg_cancel).setOnClickListener { finish() }

        save.setOnClickListener {
            val u = input.text.toString().trim().trimStart('@')
            if (u.isEmpty()) {
                inputLayout.error = getString(R.string.cfg_username_error)
                input.requestFocus()
                findViewById<View>(R.id.cfg_scroll).scrollTo(0, 0)
                return@setOnClickListener
            }
            Prefs.setUsername(this, u)
            WidgetInstance.save(
                this, widgetId,
                themeId = themeId,
                style = styleId,
                opacity = slider.value.toInt(),
                range = rangeId,
                shape = WidgetInstance.getShape(this, widgetId),
                spacing = WidgetInstance.getSpacing(this, widgetId),
                corners = WidgetInstance.getCorners(this, widgetId),
                customColor = WidgetInstance.getCustomColor(this, widgetId),
                showTotal = chkTotal.isChecked,
                showStreak = chkStreak.isChecked,
                showLongest = chkLongest.isChecked,
                showUpdated = chkUpdated.isChecked
            )
            // Approve the widget, then paint + fetch it.
            val done = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            setResult(RESULT_OK, done)
            val refresh = Intent(this, ContributionWidgetProvider::class.java).apply {
                action = ContributionWidgetProvider.ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
            }
            sendBroadcast(refresh)
            Toast.makeText(this, R.string.cfg_saved, Toast.LENGTH_SHORT).show()
            finish()
        }

        // Load the cache once in the background, then draw the preview.
        renderer.execute {
            cached = try {
                Cache.load(this)
            } catch (_: Exception) {
                null
            }
            runOnUiThread { if (!isFinishing && !isDestroyed) renderPreview() }
        }
    }

    override fun onDestroy() {
        renderer.shutdownNow()
        super.onDestroy()
    }

    /** Edge-to-edge: status bar inset on top of the content, nav bar / keyboard under the bar. */
    private fun applyInsets() {
        val root = findViewById<View>(R.id.cfg_root)
        val content = findViewById<View>(R.id.cfg_content)
        val bar = findViewById<View>(R.id.cfg_bar)
        val contentTop = content.paddingTop
        val barBottom = bar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sys = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(left = sys.left, right = sys.right)
            content.updatePadding(top = contentTop + sys.top)
            bar.updatePadding(bottom = barBottom + max(sys.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }

    // ---------------------------------------------------------------- theme tiles

    private fun currentTheme(): GraphTheme =
        Themes.resolve(themeId, WidgetInstance.getCustomColor(this, widgetId))

    /** Swatch tiles like the app's theme row; checked state drives stroke and TalkBack. */
    private fun buildThemeTiles() {
        rowThemes.removeAllViews()
        val options = Themes.ALL.map { it.id to it } +
            (Themes.CUSTOM_ID to Themes.fromBaseColor(WidgetInstance.getCustomColor(this, widgetId)))
        for ((id, theme) in options) {
            rowThemes.addView(themeTile(id, theme, id == themeId))
        }
    }

    private fun themeTile(id: String, theme: GraphTheme, selected: Boolean): MaterialCardView {
        val tile = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            setCardBackgroundColor(getColor(R.color.widget_surface))
            cardElevation = 0f
            isCheckable = true
            checkedIcon = null
            isChecked = selected
            strokeWidth = if (selected) dp(2) else dp(1)
            strokeColor = if (selected) theme.accent else getColor(R.color.widget_outline)
            contentDescription = "${theme.name} theme"
            minimumHeight = dp(48)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(8)
            layoutParams = lp
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val squares = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        theme.levels.forEachIndexed { i, c ->
            squares.addView(View(this).apply {
                background = GradientDrawable().apply {
                    setColor(c)
                    cornerRadius = dp(3).toFloat()
                }
                val slp = LinearLayout.LayoutParams(dp(14), dp(14))
                if (i > 0) slp.marginStart = dp(3)
                layoutParams = slp
            })
        }
        inner.addView(squares)
        inner.addView(TextView(this).apply {
            text = theme.name
            textSize = 12f
            maxLines = 1
            setTextColor(getColor(if (selected) R.color.widget_text else R.color.widget_text_secondary))
            setPadding(0, dp(6), 0, 0)
        })
        tile.addView(inner)
        tile.setOnClickListener {
            if (themeId == id) return@setOnClickListener
            themeId = id
            tile.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            buildThemeTiles()
            renderPreview()
        }
        return tile
    }

    // ---------------------------------------------------------------- live preview

    /** A bitmap stretched to the view's bounds that never sizes the view itself. */
    private class FillDrawable(private val bmp: Bitmap) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        override fun draw(canvas: Canvas) {
            canvas.drawBitmap(bmp, null, bounds, paint)
        }
        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }
        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getPadding(padding: Rect): Boolean = false
    }

    /**
     * Repaints the preview from the in-progress choices: cached contributions when
     * they belong to the typed username, otherwise a sample year. Texts come from
     * [WidgetCopy] like the widget's, graph from [GraphRenderer], card from [CardRenderer].
     */
    private fun renderPreview() {
        if (!::preview.isInitialized) return
        val gen = ++renderGen
        val typed = input.text?.toString()?.trim()?.trimStart('@').orEmpty()
        val cache = cached
        val real = cache?.takeIf { typed.isNotEmpty() && it.first.username.equals(typed, ignoreCase = true) }
        val result = real?.first ?: WidgetCopy.sampleResult(typed.ifEmpty { "you" })
        val status = if (real != null) {
            WidgetCopy.status(TimeAgo.format(real.second), offline = false)
        } else {
            "Updated just now"
        }
        val theme = currentTheme()
        val style = styleId
        val weeksWanted = WidgetPrefs.rangeWeeks(rangeId)
        val opacity = slider.value.toInt()
        val opts = WidgetCopy.Options(
            showTotal = chkTotal.isChecked,
            showStreak = chkStreak.isChecked,
            showLongest = chkLongest.isChecked,
            showUpdated = chkUpdated.isChecked,
            showActive = Studio.showActiveDays(this),
            customLabel = Studio.getCustomLabel(this)
        )
        val scale = Studio.textScale(Studio.getTextSize(this)) * Fonts.selected(this).scale
        val density = resources.displayMetrics.density
        val wPx = preview.width
        val hPx = preview.height
        // Treat the preview as a 4x2 widget as wide as the card.
        val fit = WidgetCopy.fit(
            if (wPx > 0) (wPx / density).toInt() else 320, 180,
            scale * WidgetCopy.systemTextScale(resources.displayMetrics)
        )
        val corners = WidgetInstance.getCorners(this, widgetId)

        renderer.execute {
            if (gen != renderGen) return@execute
            val graph = try {
                GraphRenderer.render(
                    result.days,
                    scale = 2.5f,
                    colors = Studio.effectiveLevels(this, theme),
                    maxWeeks = minOf(weeksWanted, fit.maxWeeks),
                    showMonthLabels = fit.monthLabels,
                    cornerRadius = WidgetPrefs.shapeRadiusFactor(WidgetInstance.getShape(this, widgetId)),
                    gapScale = WidgetInstance.getSpacing(this, widgetId),
                    cellScale = Studio.getCellSize(this),
                    typeface = Fonts.graphLabels(this)
                )
            } catch (_: Exception) {
                null
            }
            val card = if (wPx > 0 && hPx > 0) {
                try {
                    // Same look rules as the widget's card (ContributionWidgetProvider.baseViews).
                    val glass = style == WidgetPrefs.STYLE_GLASS
                    val k = minOf(1f, 900f / max(wPx, hPx))
                    CardRenderer.card(
                        this,
                        (wPx * k).roundToInt().coerceAtLeast(1),
                        (hPx * k).roundToInt().coerceAtLeast(1),
                        corners * density * k,
                        Color.parseColor(if (glass) "#8B949E" else "#30363D"),
                        if (glass) minOf(opacity, 45) else opacity,
                        strokePx = (density * k).coerceAtLeast(1f),
                        useCache = true
                    )
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            val stats = Stats.compute(result.days)
            val content = WidgetCopy.content(style, result, stats, opts, fit, status)
            runOnUiThread {
                if (gen != renderGen || isFinishing || isDestroyed) return@runOnUiThread
                showPreview(result, content, theme, graph, card, scale, opacity)
            }
        }
    }

    private fun showPreview(
        result: ContributionsResult,
        content: WidgetCopy.Content,
        theme: GraphTheme,
        graph: Bitmap?,
        card: Bitmap?,
        scale: Float,
        opacity: Int
    ) {
        val title = findViewById<TextView>(R.id.cfg_pv_title)
        val subtitle = findViewById<TextView>(R.id.cfg_pv_subtitle)
        val total = findViewById<TextView>(R.id.cfg_pv_total)
        val footer = findViewById<TextView>(R.id.cfg_pv_footer)
        val graphView = findViewById<ImageView>(R.id.cfg_pv_graph)

        preview.background = if (card != null) {
            FillDrawable(card)
        } else {
            GradientDrawable().apply {
                setColor(Themes.cardColor(opacity))
                cornerRadius = dp(WidgetInstance.getCorners(this@WidgetConfigActivity, widgetId)).toFloat()
            }
        }
        title.text = "@${result.username}"
        fun show(v: TextView, text: String?) {
            v.visibility = if (text == null) View.GONE else View.VISIBLE
            if (text != null) v.text = text
        }
        show(subtitle, content.subtitle)
        show(total, content.total)
        show(footer, content.footer)
        total.setTextColor(theme.accent)
        if (graph != null) graphView.setImageBitmap(graph)

        // Same font and text scale as the widget.
        title.textSize = 15f * scale
        subtitle.textSize = 12f * scale
        total.textSize = 22f * scale
        footer.textSize = 11f * scale
        title.typeface = Fonts.current(this, bold = true)
        subtitle.typeface = Fonts.current(this)
        total.typeface = Fonts.numbers(this)
        total.fontFeatureSettings = "tnum"
        footer.typeface = Fonts.current(this)

        preview.contentDescription = buildString {
            append("Widget preview: @${result.username}")
            content.total?.let { append(", $it contributions") }
            content.subtitle?.let { append(", $it") }
        }
    }
}
