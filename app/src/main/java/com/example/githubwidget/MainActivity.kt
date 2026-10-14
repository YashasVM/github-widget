package com.example.githubwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import java.text.NumberFormat
import java.util.Locale

/**
 * Home + Studio: identity and actions on top (scroll away), the live preview
 * and section tabs pinned below them, one Studio section visible at a time.
 */
class MainActivity : AppCompatActivity() {

    private var lastResult: ContributionsResult? = null
    private var tintBmp: android.graphics.Bitmap? = null
    private var onStudioChanged: (() -> Unit)? = null
    private var currentTab = 0

    /** Bumped per load(); late answers for an older request are dropped. */
    private var loadSeq = 0

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun fmt(n: Int): String = NumberFormat.getInstance(Locale.US).format(n)

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // The URI still works for the session and usually beyond it.
            }
            Studio.setImageUri(this, uri.toString())
            Studio.setBgType(this, Studio.BG_IMAGE)
            onStudioChanged?.invoke()
        }

    private fun sampleTint(weeks: Int) {
        tintBmp?.let { if (!it.isRecycled) it.recycle() }
        tintBmp = null
        if (!Studio.imageThroughGraph(this)) return
        if (Studio.getBgType(this) != Studio.BG_IMAGE) return
        val uri = Studio.getImageUri(this)
        if (uri.isEmpty()) return
        tintBmp = CardRenderer.sampleImage(this, uri, weeks, 7, Studio.SCALE_FILL)
    }

    /** Short inline message (replaces Toasts), optionally with one action such as Undo. */
    private fun say(msg: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val bar = Snackbar.make(findViewById(R.id.home_root), msg, Snackbar.LENGTH_LONG)
        if (action != null && onAction != null) bar.setAction(action) { onAction() }
        bar.show()
    }

    /**
     * Sets a slider from a saved value. Slider throws if the value is outside
     * valueFrom..valueTo or off the stepSize grid, and saved values can come
     * from an imported look (any number), so clamp and snap first.
     */
    private fun Slider.setSafeValue(v: Float) {
        val c = v.coerceIn(valueFrom, valueTo)
        val snapped = if (stepSize > 0f) {
            valueFrom + Math.round((c - valueFrom) / stepSize) * stepSize
        } else {
            c
        }
        value = snapped.coerceIn(valueFrom, valueTo)
    }

    private fun hideKeyboard(v: View) {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<ViewGroup>(R.id.home_root)
        val appBar = findViewById<AppBarLayout>(R.id.home_appbar)
        val scroll = findViewById<NestedScrollView>(R.id.home_scroll)
        // Content goes under the transparent bars; the pinned area and the
        // page bottom are padded by the bar (or keyboard) sizes.
        Ui.edgeToEdge(this, root, top = appBar, bottom = root)

        val user = findViewById<TextView>(R.id.home_user)
        val updated = findViewById<TextView>(R.id.home_updated)
        val input = findViewById<EditText>(R.id.home_username)
        val inputLayout = findViewById<TextInputLayout>(R.id.home_username_layout)
        val accountCard = findViewById<View>(R.id.home_account_card)
        val accountTitle = findViewById<TextView>(R.id.home_account_title)
        val accountBody = findViewById<TextView>(R.id.home_account_body)
        val sampleGraph = findViewById<ImageView>(R.id.home_sample_graph)
        val accountCancel = findViewById<MaterialButton>(R.id.home_account_cancel)
        val saveBtn = findViewById<MaterialButton>(R.id.home_save)
        val actions = findViewById<View>(R.id.home_actions)
        val pinned = findViewById<View>(R.id.home_pinned)
        val refreshBtn = findViewById<View>(R.id.home_refresh)
        val switchBtn = findViewById<View>(R.id.home_switch)
        val card = findViewById<MaterialCardView>(R.id.st_preview_card)
        val bg = findViewById<ImageView>(R.id.st_preview_bg)
        val title = findViewById<TextView>(R.id.st_title)
        val subtitle = findViewById<TextView>(R.id.st_subtitle)
        val total = findViewById<TextView>(R.id.st_total)
        val graph = findViewById<ImageView>(R.id.st_graph)
        val footer = findViewById<TextView>(R.id.st_footer)
        val progress = findViewById<LinearProgressIndicator>(R.id.st_progress)
        val errorBox = findViewById<View>(R.id.st_error)
        val errorText = findViewById<TextView>(R.id.st_error_text)
        val tabs = findViewById<TabLayout>(R.id.st_tabs)
        val sectionsBox = findViewById<ViewGroup>(R.id.st_sections)
        val sections = listOf(
            findViewById<View>(R.id.sec_presets),
            findViewById<View>(R.id.sec_colors),
            findViewById<View>(R.id.sec_graph),
            findViewById<View>(R.id.sec_background),
            findViewById<View>(R.id.sec_text)
        )
        val rowThemes = findViewById<LinearLayout>(R.id.st_themes)
        val themeName = findViewById<TextView>(R.id.st_theme_name)
        val rowStyles = findViewById<ChipGroup>(R.id.st_styles)
        val rowRanges = findViewById<MaterialButtonToggleGroup>(R.id.st_ranges)
        val rowShapes = findViewById<MaterialButtonToggleGroup>(R.id.st_shapes)
        val cellText = findViewById<TextView>(R.id.st_cellsize_text)
        val cellSlider = findViewById<Slider>(R.id.st_cellsize)
        val spacingText = findViewById<TextView>(R.id.st_spacing_text)
        val spacingSlider = findViewById<Slider>(R.id.st_spacing)
        val rowLevels = findViewById<LinearLayout>(R.id.st_levels)
        val rowBgTypes = findViewById<MaterialButtonToggleGroup>(R.id.st_bgtypes)
        val solidRow = findViewById<LinearLayout>(R.id.st_solid_row)
        val bgColorBtn = findViewById<MaterialButton>(R.id.st_bgcolor)
        val bgColorHex = findViewById<TextView>(R.id.st_bgcolor_hex)
        val gradBox = findViewById<LinearLayout>(R.id.st_gradient_box)
        val grad1 = findViewById<MaterialButton>(R.id.st_grad_1)
        val grad2 = findViewById<MaterialButton>(R.id.st_grad_2)
        val grad3 = findViewById<MaterialButton>(R.id.st_grad_3)
        val gradThird = findViewById<MaterialCheckBox>(R.id.st_grad_third)
        val gradRadial = findViewById<MaterialCheckBox>(R.id.st_grad_radial)
        val rowAngles = findViewById<MaterialButtonToggleGroup>(R.id.st_angles)
        val imageBox = findViewById<LinearLayout>(R.id.st_image_box)
        val rowScales = findViewById<MaterialButtonToggleGroup>(R.id.st_scales)
        val chkImgGraph = findViewById<MaterialCheckBox>(R.id.st_image_graph)
        val bgOpText = findViewById<TextView>(R.id.st_bgopacity_text)
        val bgOpSlider = findViewById<Slider>(R.id.st_bgopacity)
        val blurText = findViewById<TextView>(R.id.st_blur_text)
        val blurSlider = findViewById<Slider>(R.id.st_blur)
        val rowOverlays = findViewById<ChipGroup>(R.id.st_overlays)
        val ovOpText = findViewById<TextView>(R.id.st_overlayop_text)
        val ovOpSlider = findViewById<Slider>(R.id.st_overlayop)
        val chkTotal = findViewById<MaterialCheckBox>(R.id.st_chk_total)
        val chkStreak = findViewById<MaterialCheckBox>(R.id.st_chk_streak)
        val chkLongest = findViewById<MaterialCheckBox>(R.id.st_chk_longest)
        val chkUpdated = findViewById<MaterialCheckBox>(R.id.st_chk_updated)
        val chkActive = findViewById<MaterialCheckBox>(R.id.st_chk_active)
        val labelInput = findViewById<EditText>(R.id.st_label)
        val rowTextSizes = findViewById<MaterialButtonToggleGroup>(R.id.st_textsizes)
        val rowFonts = findViewById<ChipGroup>(R.id.st_fonts)
        val cornersText = findViewById<TextView>(R.id.st_corners_text)
        val cornersSlider = findViewById<Slider>(R.id.st_corners)
        val rowPresets = findViewById<LinearLayout>(R.id.st_presets)

        fun theme(): GraphTheme =
            Themes.resolve(Prefs.getThemeId(this), Prefs.getCustomColor(this))

        fun repaintWidget() = Prefs.requestRepaint(this)

        // ---------- tabs ----------

        fun showTab(i: Int, animate: Boolean) {
            currentTab = i
            if (animate) {
                TransitionManager.beginDelayedTransition(sectionsBox, AutoTransition().setDuration(180))
            }
            sections.forEachIndexed { j, v -> v.visibility = if (j == i) View.VISIBLE else View.GONE }
            if (animate) scroll.scrollTo(0, 0)
        }

        val startTab = (savedInstanceState?.getInt(KEY_TAB, 0) ?: 0).coerceIn(0, sections.size - 1)
        tabs.getTabAt(startTab)?.select()
        showTab(startTab, animate = false)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                Ui.tick(tabs)
                showTab(tab.position, animate = true)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        // ---------- preview ----------

        fun paintCard() {
            card.radius = dp(WidgetPrefs.getCorners(this)).toFloat()
            Thread {
                try {
                    val bmp = CardRenderer.card(
                        this, 1024, 512,
                        dp(WidgetPrefs.getCorners(this)).toFloat() * 3f,
                        Color.parseColor("#30363D")
                    )
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        bg.setImageBitmap(bmp)
                        bg.visibility = View.VISIBLE
                        card.setCardBackgroundColor(Color.TRANSPARENT)
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        bg.visibility = View.GONE
                        card.setCardBackgroundColor(Themes.cardColor(Prefs.getOpacity(this)))
                    }
                }
            }.start()
        }

        fun showResult(result: ContributionsResult, ago: String) {
            lastResult = result
            val t = theme()
            val weeks = WidgetPrefs.rangeWeeks(WidgetPrefs.getRange(this))
            sampleTint(weeks)
            graph.alpha = 1f
            graph.setImageBitmap(
                GraphRenderer.render(
                    result.days,
                    scale = 2.5f,
                    colors = Studio.effectiveLevels(this, t),
                    maxWeeks = weeks,
                    showMonthLabels = true,
                    cornerRadius = WidgetPrefs.shapeRadiusFactor(WidgetPrefs.getShape(this)),
                    gapScale = WidgetPrefs.getSpacing(this),
                    cellScale = Studio.getCellSize(this),
                    tint = tintBmp,
                    tintAmount = 0.35f,
                    typeface = Fonts.graphLabels(this)
                )
            )
            graph.contentDescription =
                "@${result.username}: ${fmt(result.totalLastYear)} contributions in the last year."
            user.text = "@${result.username}"
            updated.text = "Updated $ago"
            title.text = "@${result.username}"
            // Same copy as the homescreen widget (WidgetCopy), drawn as a roomy 4x2:
            // big total, full subtitle, footer with "Updated 5m ago".
            val stats = Stats.compute(result.days)
            val content = WidgetCopy.content(
                WidgetPrefs.getStyle(this), result, stats,
                WidgetCopy.Options(
                    showTotal = WidgetPrefs.showTotal(this),
                    showStreak = WidgetPrefs.showStreak(this),
                    showLongest = WidgetPrefs.showLongest(this),
                    showUpdated = WidgetPrefs.showUpdated(this),
                    showActive = Studio.showActiveDays(this),
                    customLabel = Studio.getCustomLabel(this)
                ),
                WidgetCopy.Fit(maxWeeks = weeks, monthLabels = true, showFooter = true, compact = false),
                WidgetCopy.status(ago, offline = false)
            )
            fun show(v: TextView, text: String?) {
                v.visibility = if (text == null) View.GONE else View.VISIBLE
                if (text != null) v.text = text
            }
            show(subtitle, content.subtitle)
            show(total, content.total)
            show(footer, content.footer)
            total.setTextColor(t.accent)
            // Same font scale the widget applies (e.g. Press Start 2P runs wide).
            val s = Studio.textScale(Studio.getTextSize(this)) * Fonts.selected(this).scale
            title.textSize = 15f * s
            subtitle.textSize = 12f * s
            total.textSize = 24f * s
            footer.textSize = 11f * s
            title.typeface = Fonts.current(this, bold = true)
            subtitle.typeface = Fonts.current(this)
            total.typeface = Fonts.numbers(this)
            total.fontFeatureSettings = "tnum"
            footer.typeface = Fonts.current(this)
            paintCard()
        }

        fun repaintPreview() {
            val cached = lastResult ?: return
            val ts = Cache.load(this)?.second ?: 0L
            showResult(cached, TimeAgo.format(ts))
        }

        /** Empty level-0 grid in the current palette while the first load runs. */
        fun showSkeleton() {
            val weeks = WidgetPrefs.rangeWeeks(WidgetPrefs.getRange(this))
            graph.setImageBitmap(
                GraphRenderer.render(
                    Ui.sampleDays(weeks, empty = true),
                    scale = 2.5f,
                    colors = Studio.effectiveLevels(this, theme()),
                    maxWeeks = weeks,
                    cornerRadius = WidgetPrefs.shapeRadiusFactor(WidgetPrefs.getShape(this)),
                    gapScale = WidgetPrefs.getSpacing(this),
                    cellScale = Studio.getCellSize(this)
                )
            )
            graph.alpha = 0.6f
            graph.contentDescription = "Loading contribution graph"
        }

        // ---------- loading / error states ----------

        fun setLoading(on: Boolean) {
            if (on) progress.show() else progress.hide()
        }

        var onRetry: () -> Unit = {}

        fun showError(msg: String) {
            errorText.text = msg
            if (errorBox.visibility != View.VISIBLE) {
                TransitionManager.beginDelayedTransition(appBar, AutoTransition().setDuration(150))
                errorBox.visibility = View.VISIBLE
            }
        }

        fun hideError() {
            errorBox.visibility = View.GONE
        }

        findViewById<View>(R.id.st_error_retry).setOnClickListener { onRetry() }

        // ---------- account / onboarding ----------

        var editingAccount = false

        /** Onboarding (no username), account editing, or the full home + Studio. */
        fun applyMode() {
            val hasUser = Prefs.getUsername(this).isNotBlank()
            val showAccount = !hasUser || editingAccount
            accountCard.visibility = if (showAccount) View.VISIBLE else View.GONE
            sampleGraph.visibility = if (hasUser) View.GONE else View.VISIBLE
            accountCancel.visibility = if (hasUser) View.VISIBLE else View.GONE
            saveBtn.text = if (hasUser) "Save" else "Load my graph"
            if (hasUser) {
                accountTitle.text = "Switch account"
                accountBody.text = "Show a different GitHub user's public contributions."
            } else {
                accountTitle.text = "Your GitHub graph, on your homescreen"
                accountBody.text =
                    "Enter your GitHub username. Only public contributions are read: no sign-in, no token."
            }
            val main = if (hasUser) View.VISIBLE else View.GONE
            actions.visibility = main
            pinned.visibility = main
            scroll.visibility = main
            refreshBtn.visibility = main
            switchBtn.visibility = main
            if (!hasUser && sampleGraph.drawable == null) {
                sampleGraph.setImageBitmap(
                    GraphRenderer.render(
                        Ui.sampleDays(20),
                        scale = 2.5f,
                        colors = Themes.GREEN.levels,
                        maxWeeks = 20,
                        cornerRadius = WidgetPrefs.shapeRadiusFactor(WidgetPrefs.SHAPE_ROUNDED)
                    )
                )
            }
        }

        fun openAccount() {
            editingAccount = true
            TransitionManager.beginDelayedTransition(appBar, AutoTransition().setDuration(180))
            applyMode()
            appBar.setExpanded(true, true)
            input.setText(Prefs.getUsername(this))
            input.setSelection(input.text.length)
            input.requestFocus()
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }

        // ---------- theme tiles & palette ----------

        var onThemeTap: (String) -> Unit = {}
        var onCustomTap: () -> Unit = {}

        fun buildThemeRow() {
            rowThemes.removeAllViews()
            val currentId = Prefs.getThemeId(this)
            for (t in Themes.ALL) {
                rowThemes.addView(
                    Ui.themeTile(this, t.name, t.levels, currentId == t.id) { onThemeTap(t.id) }
                )
            }
            val customHex = Themes.toHex(Prefs.getCustomColor(this))
            val custom = Themes.resolve(Themes.CUSTOM_ID, Prefs.getCustomColor(this)).levels
            rowThemes.addView(
                Ui.themeTile(this, "Custom $customHex", custom, currentId == Themes.CUSTOM_ID) {
                    onCustomTap()
                }
            )
            themeName.text = if (currentId == Themes.CUSTOM_ID) "Custom $customHex" else theme().name
        }

        lateinit var styleButtons: List<Chip>
        lateinit var rangeButtons: List<MaterialButton>
        lateinit var shapeButtons: List<MaterialButton>
        lateinit var sizeButtons: List<MaterialButton>
        lateinit var fontButtons: List<Chip>
        lateinit var bgTypeButtons: List<MaterialButton>
        lateinit var scaleButtons: List<MaterialButton>
        lateinit var angleButtons: List<MaterialButton>
        lateinit var overlayButtons: List<Chip>

        fun spacingLabel(s: Float): String = when {
            s < 1f -> "Tight"
            s > 1f -> "Airy"
            else -> "Normal"
        }

        val overlayColors = listOf(
            "None" to null as Int?,
            "Black" to Color.BLACK,
            "White" to Color.WHITE,
            "Theme" to -1,
            "Custom" to -2
        )

        fun overlayIndex(): Int {
            if (Studio.getOverlayOpacity(this) == 0) return 0
            val c = Studio.getOverlay(this)
            return when {
                c == Color.BLACK -> 1
                c == Color.WHITE -> 2
                c == theme().accent -> 3
                else -> 4
            }
        }

        fun buildLevels() {
            rowLevels.removeAllViews()
            val levels = Studio.effectiveLevels(this, theme())
            val names = listOf("Empty", "Low", "Med", "High", "Max")
            levels.forEachIndexed { i, c ->
                rowLevels.addView(Ui.levelSwatch(this, names[i], c) {
                    ColorPickerDialog.show(this@MainActivity, c) { picked ->
                        val cur = Studio.getLevels(this@MainActivity)?.copyOf()
                            ?: theme().levels.copyOf()
                        cur[i] = picked
                        Studio.setLevels(this@MainActivity, cur)
                        buildLevels()
                        repaintPreview()
                        repaintWidget()
                    }
                })
            }
        }

        /** Font chips keep their own typeface (bold when selected). */
        fun paintFonts() {
            val selected = Fonts.ALL.indexOfFirst { it.id == Fonts.get(this) }
            Ui.select(fontButtons, selected)
            fontButtons.forEachIndexed { i, b ->
                b.typeface = Fonts.typeface(this, Fonts.ALL[i].id, bold = i == selected)
            }
        }

        fun syncSwatches() {
            val bgc = Studio.getBgColor(this)
            bgColorBtn.backgroundTintList = ColorStateList.valueOf(bgc)
            bgColorHex.text = Themes.toHex(bgc)
            bgColorBtn.contentDescription = "Background color, ${Themes.toHex(bgc)}"
            grad1.backgroundTintList = ColorStateList.valueOf(bgc)
            grad2.backgroundTintList = ColorStateList.valueOf(Studio.getGrad2(this))
            Studio.getGrad3(this)?.let {
                grad3.backgroundTintList = ColorStateList.valueOf(it)
            }
            buildLevels()
        }

        fun syncAll() {
            buildThemeRow()
            Ui.select(styleButtons, WidgetPrefs.STYLES.indexOf(WidgetPrefs.getStyle(this)))
            val ranges = listOf(WidgetPrefs.RANGE_3M, WidgetPrefs.RANGE_6M, WidgetPrefs.RANGE_12M)
            Ui.select(rangeButtons, ranges.indexOf(WidgetPrefs.getRange(this)))
            val shapes = listOf(
                WidgetPrefs.SHAPE_SQUARE, WidgetPrefs.SHAPE_ROUNDED,
                WidgetPrefs.SHAPE_SOFT, WidgetPrefs.SHAPE_CIRCLE
            )
            Ui.select(shapeButtons, shapes.indexOf(WidgetPrefs.getShape(this)))
            Ui.select(sizeButtons, Studio.getTextSize(this))
            paintFonts()
            val bgTypes = listOf(Studio.BG_SOLID, Studio.BG_GRADIENT, Studio.BG_IMAGE, Studio.BG_TRANSPARENT)
            Ui.select(bgTypeButtons, bgTypes.indexOf(Studio.getBgType(this)))
            Ui.select(scaleButtons, if (Studio.getImageScale(this) == Studio.SCALE_FIT) 1 else 0)
            val angles = listOf(0, 45, 90, 135)
            Ui.select(angleButtons, angles.indexOf(Studio.getGradAngle(this)).coerceAtLeast(0))
            Ui.select(overlayButtons, overlayIndex())
            cellSlider.setSafeValue(Studio.getCellSize(this))
            cellText.text = "${(Studio.getCellSize(this) * 100).toInt()}%"
            spacingSlider.setSafeValue(WidgetPrefs.getSpacing(this))
            spacingText.text = spacingLabel(WidgetPrefs.getSpacing(this))
            cornersSlider.setSafeValue(WidgetPrefs.getCorners(this).toFloat())
            cornersText.text = "${WidgetPrefs.getCorners(this)}dp"
            bgOpSlider.setSafeValue(Studio.getBgOpacity(this).toFloat())
            bgOpText.text = "${Studio.getBgOpacity(this)}%"
            blurSlider.setSafeValue(Studio.getBlur(this).toFloat())
            blurText.text = if (Studio.getBlur(this) == 0) "Off" else "${Studio.getBlur(this)}"
            ovOpSlider.setSafeValue(Studio.getOverlayOpacity(this).toFloat())
            ovOpText.text = "${Studio.getOverlayOpacity(this)}%"
            gradThird.isChecked = Studio.getGrad3(this) != null
            gradRadial.isChecked = Studio.isGradRadial(this)
            chkImgGraph.isChecked = Studio.imageThroughGraph(this)
            chkTotal.isChecked = WidgetPrefs.showTotal(this)
            chkStreak.isChecked = WidgetPrefs.showStreak(this)
            chkLongest.isChecked = WidgetPrefs.showLongest(this)
            chkUpdated.isChecked = WidgetPrefs.showUpdated(this)
            chkActive.isChecked = Studio.showActiveDays(this)
            if (labelInput.text.toString() != Studio.getCustomLabel(this)) {
                labelInput.setText(Studio.getCustomLabel(this))
            }
            val t = Studio.getBgType(this)
            solidRow.visibility = if (t == Studio.BG_SOLID || t == Studio.BG_GRADIENT) View.VISIBLE else View.GONE
            gradBox.visibility = if (t == Studio.BG_GRADIENT) View.VISIBLE else View.GONE
            imageBox.visibility = if (t == Studio.BG_IMAGE) View.VISIBLE else View.GONE
            syncSwatches()
            repaintPreview()
        }

        /** Applies a look change and offers Undo (restores the look as it was). */
        fun withUndo(msg: String, change: () -> Unit) {
            val before = Studio.exportLook(this)
            change()
            syncAll()
            repaintWidget()
            say(msg, "Undo") {
                Studio.importLook(this, before, replace = true)
                syncAll()
                repaintWidget()
            }
        }

        // ---------- actions ----------

        fun selectTheme(id: String) {
            Prefs.setThemeId(this, id)
            buildThemeRow()
            Ui.select(styleButtons, WidgetPrefs.STYLES.indexOf(WidgetPrefs.getStyle(this)))
            repaintPreview()
            repaintWidget()
        }

        fun openColorPicker() {
            ColorPickerDialog.show(this, Prefs.getCustomColor(this)) { c ->
                Prefs.setCustomColor(this, c)
                Prefs.setThemeId(this, Themes.CUSTOM_ID)
                buildThemeRow()
                repaintPreview()
                repaintWidget()
            }
        }

        onThemeTap = { id -> selectTheme(id) }
        onCustomTap = { openColorPicker() }

        fun pickSimpleColor(initial: Int, onPick: (Int) -> Unit) {
            ColorPickerDialog.show(this, initial) { onPick(it) }
        }

        fun applyOverlay(i: Int) {
            when (i) {
                0 -> Studio.setOverlayOpacity(this, 0)
                1 -> {
                    Studio.setOverlay(this, Color.BLACK)
                    if (Studio.getOverlayOpacity(this) == 0) Studio.setOverlayOpacity(this, 40)
                }
                2 -> {
                    Studio.setOverlay(this, Color.WHITE)
                    if (Studio.getOverlayOpacity(this) == 0) Studio.setOverlayOpacity(this, 40)
                }
                3 -> {
                    Studio.setOverlay(this, theme().accent)
                    if (Studio.getOverlayOpacity(this) == 0) Studio.setOverlayOpacity(this, 30)
                }
                else -> {
                    // Keep showing the real state until a color is actually picked.
                    Ui.select(overlayButtons, overlayIndex())
                    pickSimpleColor(Studio.getOverlay(this)) { c ->
                        Studio.setOverlay(this, c)
                        if (Studio.getOverlayOpacity(this) == 0) Studio.setOverlayOpacity(this, 40)
                        syncAll()
                        repaintWidget()
                    }
                    return
                }
            }
            ovOpSlider.setSafeValue(Studio.getOverlayOpacity(this).toFloat())
            ovOpText.text = "${Studio.getOverlayOpacity(this)}%"
            Ui.select(overlayButtons, overlayIndex())
            repaintPreview()
            repaintWidget()
        }

        // ---------- build rows ----------

        styleButtons = Ui.chips(rowStyles, WidgetPrefs.STYLES.map { WidgetPrefs.STYLE_NAMES[it] ?: it }) { i ->
            val id = WidgetPrefs.STYLES[i]
            WidgetPrefs.setStyle(this, id)
            val c = Presets.styleContent(id)
            WidgetPrefs.setContent(this, c[0], c[1], c[2], c[3])
            syncAll()
            repaintWidget()
        }

        val rangeIds = listOf(WidgetPrefs.RANGE_3M, WidgetPrefs.RANGE_6M, WidgetPrefs.RANGE_12M)
        rangeButtons = Ui.segments(rowRanges, listOf("3 months", "6 months", "1 year")) { i ->
            WidgetPrefs.setRange(this, rangeIds[i])
            repaintPreview()
            repaintWidget()
        }

        val shapeIds = listOf(
            WidgetPrefs.SHAPE_SQUARE, WidgetPrefs.SHAPE_ROUNDED,
            WidgetPrefs.SHAPE_SOFT, WidgetPrefs.SHAPE_CIRCLE
        )
        shapeButtons = Ui.segments(rowShapes, listOf("Square", "Rounded", "Soft", "Circle")) { i ->
            WidgetPrefs.setShape(this, shapeIds[i])
            repaintPreview()
            repaintWidget()
        }

        sizeButtons = Ui.segments(rowTextSizes, listOf("Compact", "Normal", "Large")) { i ->
            Studio.setTextSize(this, i)
            repaintPreview()
            repaintWidget()
        }

        fontButtons = Ui.chips(rowFonts, Fonts.ALL.map { "Aa  ${it.name}" }) { i ->
            Fonts.set(this, Fonts.ALL[i].id)
            paintFonts()
            repaintPreview()
            repaintWidget()
        }
        fontButtons.forEachIndexed { i, c -> c.contentDescription = "Font ${Fonts.ALL[i].name}" }

        val bgTypeIds = listOf(Studio.BG_SOLID, Studio.BG_GRADIENT, Studio.BG_IMAGE, Studio.BG_TRANSPARENT)
        bgTypeButtons = Ui.segments(rowBgTypes, listOf("Solid", "Gradient", "Image", "Clear")) { i ->
            val id = bgTypeIds[i]
            Studio.setBgType(this, id)
            if (id == Studio.BG_IMAGE && Studio.getImageUri(this).isEmpty()) {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            TransitionManager.beginDelayedTransition(sectionsBox, AutoTransition().setDuration(180))
            syncAll()
            repaintWidget()
        }

        scaleButtons = Ui.segments(rowScales, listOf("Fill", "Fit")) { i ->
            Studio.setImageScale(this, if (i == 0) Studio.SCALE_FILL else Studio.SCALE_FIT)
            repaintPreview()
            repaintWidget()
        }

        angleButtons = Ui.segments(rowAngles, listOf("0°", "45°", "90°", "135°")) { i ->
            Studio.setGradAngle(this, listOf(0, 45, 90, 135)[i])
            repaintPreview()
            repaintWidget()
        }

        overlayButtons = Ui.chips(rowOverlays, overlayColors.map { it.first }) { i -> applyOverlay(i) }

        // Presets as cards: a mini graph in each preset's palette, shape and spacing.
        val presetLooks = mapOf(
            Presets.DEFAULT to Triple(Themes.GREEN, WidgetPrefs.SHAPE_ROUNDED, 1f),
            Presets.MINIMAL to Triple(Themes.GREEN, WidgetPrefs.SHAPE_SOFT, 1.5f),
            Presets.DEVELOPER to Triple(Themes.GREEN, WidgetPrefs.SHAPE_SQUARE, 0.5f),
            Presets.NEON to Triple(Themes.ROSE, WidgetPrefs.SHAPE_SOFT, 1f)
        )
        val presetBlurbs = mapOf(
            Presets.DEFAULT to "Classic, 6 months",
            Presets.MINIMAL to "Graph only, airy",
            Presets.DEVELOPER to "Terminal, full year",
            Presets.NEON to "Glass, pink glow"
        )
        val sample = Ui.sampleDays(12)
        Ui.grid(rowPresets, Presets.ALL.map { id ->
            val (t, shape, spacing) = presetLooks[id] ?: Triple(Themes.GREEN, WidgetPrefs.SHAPE_ROUNDED, 1f)
            val thumb = GraphRenderer.render(
                sample, scale = 2f, colors = t.levels, maxWeeks = 12,
                cornerRadius = WidgetPrefs.shapeRadiusFactor(shape), gapScale = spacing
            )
            val name = Presets.NAMES[id] ?: id
            Ui.presetCard(this, name, presetBlurbs[id] ?: "", thumb) {
                withUndo("$name look applied") { Presets.apply(this, id) }
            }
        })

        // ---------- background controls ----------

        bgColorBtn.setOnClickListener {
            pickSimpleColor(Studio.getBgColor(this)) { c ->
                Studio.setBgColor(this, c)
                syncSwatches()
                repaintPreview()
                repaintWidget()
            }
        }
        grad1.setOnClickListener {
            pickSimpleColor(Studio.getBgColor(this)) { c ->
                Studio.setBgColor(this, c)
                syncSwatches()
                repaintPreview()
                repaintWidget()
            }
        }
        grad2.setOnClickListener {
            pickSimpleColor(Studio.getGrad2(this)) { c ->
                Studio.setGrad2(this, c)
                syncSwatches()
                repaintPreview()
                repaintWidget()
            }
        }
        grad3.setOnClickListener {
            pickSimpleColor(Studio.getGrad3(this) ?: Studio.getGrad2(this)) { c ->
                Studio.setGrad3(this, c)
                gradThird.isChecked = true
                syncSwatches()
                repaintPreview()
                repaintWidget()
            }
        }
        gradThird.setOnClickListener {
            if (gradThird.isChecked) {
                Studio.setGrad3(this, Studio.getGrad2(this))
            } else {
                Studio.setGrad3(this, null)
            }
            repaintPreview()
            repaintWidget()
        }
        gradRadial.setOnClickListener {
            Studio.setGradRadial(this, gradRadial.isChecked)
            repaintPreview()
            repaintWidget()
        }

        findViewById<MaterialButton>(R.id.st_image_pick).setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<MaterialButton>(R.id.st_image_remove).setOnClickListener {
            val oldUri = Studio.getImageUri(this)
            val oldType = Studio.getBgType(this)
            Studio.setImageUri(this, "")
            Studio.setBgType(this, Studio.BG_SOLID)
            syncAll()
            repaintWidget()
            say("Image removed", "Undo") {
                Studio.setImageUri(this, oldUri)
                Studio.setBgType(this, oldType)
                syncAll()
                repaintWidget()
            }
        }
        chkImgGraph.setOnClickListener {
            Studio.setImageThroughGraph(this, chkImgGraph.isChecked)
            repaintPreview()
            repaintWidget()
        }

        // ---------- sliders ----------

        fun liveSlider(
            slider: Slider,
            label: TextView,
            format: (Float) -> String,
            onRelease: (Float) -> Unit
        ) {
            slider.setLabelFormatter { format(it) }
            slider.addOnChangeListener { _, v, fromUser ->
                label.text = format(v)
                if (fromUser) Ui.tick(slider)
            }
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(s: Slider) = Unit
                override fun onStopTrackingTouch(s: Slider) {
                    onRelease(s.value)
                    repaintPreview()
                    repaintWidget()
                }
            })
        }

        liveSlider(cellSlider, cellText, { "${(it * 100).toInt()}%" }) {
            Studio.setCellSize(this, it)
        }
        liveSlider(spacingSlider, spacingText, { spacingLabel(it) }) {
            WidgetPrefs.setSpacing(this, it)
        }
        liveSlider(cornersSlider, cornersText, { "${it.toInt()}dp" }) {
            WidgetPrefs.setCorners(this, it.toInt())
        }
        liveSlider(bgOpSlider, bgOpText, { "${it.toInt()}%" }) {
            val v = it.toInt()
            Studio.setBgOpacity(this, v)
            Prefs.setOpacity(this, v.coerceIn(20, 100))
        }
        liveSlider(blurSlider, blurText, { if (it == 0f) "Off" else "${it.toInt()}" }) {
            Studio.setBlur(this, it.toInt())
        }
        liveSlider(ovOpSlider, ovOpText, { "${it.toInt()}%" }) {
            Studio.setOverlayOpacity(this, it.toInt())
            Ui.select(overlayButtons, overlayIndex())
        }

        // ---------- palette ----------

        findViewById<MaterialButton>(R.id.st_palette_generate).setOnClickListener {
            val ramp = Themes.fromBaseColor(theme().accent).levels
            withUndo("Palette built from theme color") { Studio.setLevels(this, ramp) }
        }
        findViewById<MaterialButton>(R.id.st_palette_reset).setOnClickListener {
            Studio.setLevels(this, null)
            buildLevels()
            repaintPreview()
            repaintWidget()
        }

        // ---------- content ----------

        val onContent = View.OnClickListener {
            Ui.tick(it)
            WidgetPrefs.setContent(
                this,
                chkTotal.isChecked, chkStreak.isChecked,
                chkLongest.isChecked, chkUpdated.isChecked
            )
            Studio.setShowActiveDays(this, chkActive.isChecked)
            repaintPreview()
            repaintWidget()
        }
        chkTotal.setOnClickListener(onContent)
        chkStreak.setOnClickListener(onContent)
        chkLongest.setOnClickListener(onContent)
        chkUpdated.setOnClickListener(onContent)
        chkActive.setOnClickListener(onContent)

        fun saveLabel() {
            if (labelInput.text.toString() == Studio.getCustomLabel(this)) return
            Studio.setCustomLabel(this, labelInput.text.toString())
            repaintPreview()
            repaintWidget()
        }
        labelInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveLabel()
        }
        labelInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveLabel()
                hideKeyboard(v)
                v.clearFocus()
                true
            } else {
                false
            }
        }

        // ---------- presets / transfer ----------

        findViewById<MaterialButton>(R.id.st_reset).setOnClickListener {
            withUndo("Look reset. Username and data kept.") {
                WidgetPrefs.resetToDefaults(this)
                Studio.resetBackground(this)
                Studio.resetGraph(this)
                Fonts.set(this, Fonts.SYSTEM)
                Studio.setCustomLabel(this, "")
                Studio.setShowActiveDays(this, false)
                labelInput.setText("")
            }
        }

        findViewById<MaterialButton>(R.id.st_draw).setOnClickListener {
            startActivity(Intent(this, DrawingActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.st_export).setOnClickListener {
            val json = Studio.exportLook(this@MainActivity)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, json)
            }
            startActivity(Intent.createChooser(intent, "Share look"))
        }

        findViewById<MaterialButton>(R.id.st_import).setOnClickListener {
            val field = EditText(this).apply {
                hint = "Paste a shared look"
                setPadding(dp(4))
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
                addView(field)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Import look")
                .setView(box)
                .setPositiveButton("Import") { _, _ ->
                    val before = Studio.exportLook(this)
                    val err = Studio.importLook(this, field.text.toString())
                    if (err == null) {
                        syncAll()
                        repaintWidget()
                        say("Look imported", "Undo") {
                            Studio.importLook(this, before, replace = true)
                            syncAll()
                            repaintWidget()
                        }
                    } else {
                        say(err)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // ---------- data ----------

        fun load(username: String) {
            val seq = ++loadSeq
            hideError()
            if (username.isBlank()) {
                setLoading(false)
                user.text = "GH-widgets"
                updated.text = "Let's set up your widget."
                title.text = "No data yet"
                subtitle.text = "Save a username to load your graph."
                footer.visibility = View.GONE
                graph.setImageDrawable(null)
                graph.contentDescription = null
                applyMode()
                return
            }
            val cached = Cache.load(this)
            val hasCache = cached != null && cached.first.username.equals(username, ignoreCase = true)
            if (hasCache) {
                showResult(cached!!.first, TimeAgo.format(cached.second))
            } else {
                lastResult = null
                user.text = "@$username"
                updated.text = "Loading contributions…"
                title.text = "@$username"
                subtitle.text = "Loading contributions…"
                footer.visibility = View.GONE
                total.text = "–"
                showSkeleton()
                paintCard()
            }
            setLoading(true)
            onRetry = {
                Prefs.requestRefresh(this)
                load(username)
            }

            Thread {
                try {
                    // Shared conditional fetch (ETag, deduped with widget refreshes).
                    val result = Refresher.fetchForApp(this, username)
                    runOnUiThread {
                        if (isFinishing || isDestroyed || seq != loadSeq) return@runOnUiThread
                        setLoading(false)
                        showResult(result, TimeAgo.format(System.currentTimeMillis()))
                        Prefs.requestRepaint(this)
                    }
                } catch (e: UserNotFoundException) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed || seq != loadSeq) return@runOnUiThread
                        setLoading(false)
                        if (!hasCache) {
                            subtitle.text = "User not found"
                            updated.text = "User not found"
                            showError("No GitHub user named @$username. Check the spelling.")
                            openAccount()
                            inputLayout.error = "No GitHub user named @$username"
                        } else {
                            showError("${e.message} Showing saved data.")
                        }
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed || seq != loadSeq) return@runOnUiThread
                        setLoading(false)
                        if (!hasCache) {
                            subtitle.text = "No connection"
                            updated.text = "Offline"
                            showError("Couldn't load your graph. Check your connection.")
                        } else {
                            val ts = Cache.load(this)?.second ?: 0L
                            showError("Couldn't refresh. Showing data from ${TimeAgo.format(ts)}.")
                        }
                    }
                }
            }.start()
        }

        // ---------- init ----------

        onStudioChanged = {
            syncAll()
            repaintWidget()
        }
        refreshLook = {
            syncAll()
        }

        fun saveUsername() {
            val u = input.text.toString().trim().trimStart('@').trim()
            if (u.isEmpty()) {
                inputLayout.error = "Enter a GitHub username"
                return
            }
            inputLayout.error = null
            input.setText(u)
            hideKeyboard(input)
            input.clearFocus()
            Prefs.setUsername(this, u)
            Prefs.requestRefresh(this)
            editingAccount = false
            TransitionManager.beginDelayedTransition(root, AutoTransition().setDuration(200))
            applyMode()
            load(u)
        }

        input.setText(Prefs.getUsername(this))
        saveBtn.setOnClickListener { saveUsername() }
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                saveUsername()
                true
            } else {
                false
            }
        }
        input.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) inputLayout.error = null }
        accountCancel.setOnClickListener {
            inputLayout.error = null
            input.setText(Prefs.getUsername(this))
            hideKeyboard(input)
            input.clearFocus()
            editingAccount = false
            TransitionManager.beginDelayedTransition(appBar, AutoTransition().setDuration(180))
            applyMode()
        }

        findViewById<View>(R.id.home_activity).setOnClickListener {
            startActivity(Intent(this, ActivityActivity::class.java))
        }
        findViewById<View>(R.id.home_share).setOnClickListener {
            shareFromHome()
        }
        findViewById<View>(R.id.home_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        refreshBtn.setOnClickListener {
            val u = Prefs.getUsername(this)
            if (u.isBlank()) {
                openAccount()
                return@setOnClickListener
            }
            load(u)
            Prefs.requestRefresh(this)
        }
        switchBtn.setOnClickListener {
            if (editingAccount) accountCancel.performClick() else openAccount()
        }

        val pin = findViewById<MaterialButton>(R.id.home_add_widget)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(AppWidgetManager::class.java)
            val provider = ComponentName(this, ContributionWidgetProvider::class.java)
            pin.isEnabled = mgr.isRequestPinAppWidgetSupported
            if (!mgr.isRequestPinAppWidgetSupported) {
                pin.text = "Add widget via homescreen (long-press)"
            }
            pin.setOnClickListener {
                if (mgr.isRequestPinAppWidgetSupported) {
                    mgr.requestPinAppWidget(provider, null, null)
                } else {
                    say("Launcher doesn't support pinning")
                }
            }
        } else {
            pin.isEnabled = false
            pin.text = "Add widget via homescreen (long-press)"
        }

        labelInput.setText(Studio.getCustomLabel(this))
        applyMode()
        syncAll()
        load(Prefs.getUsername(this))
        if (Prefs.getUsername(this).isBlank()) input.requestFocus()
        openAccountHook = { openAccount() }
        if (intent?.getBooleanExtra(EXTRA_OPEN_ACCOUNT, false) == true) openAccount()
    }

    /** Assigned in onCreate: re-syncs controls + repaints cached data, no network. */
    private var refreshLook: (() -> Unit)? = null

    /** Assigned in onCreate: shows the account card (from Settings / Activity). */
    private var openAccountHook: (() -> Unit)? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_ACCOUNT, false)) openAccountHook?.invoke()
    }

    override fun onResume() {
        super.onResume()
        // Look may have changed in Settings/Drawing — repaint, never refetch.
        refreshLook?.invoke()
    }

    private fun shareFromHome() {
        val username = Prefs.getUsername(this)
        val cached = Cache.load(this)
        if (username.isBlank() || cached == null ||
            !cached.first.username.equals(username, ignoreCase = true)
        ) {
            say("Load your graph first")
            return
        }
        say("Generating card…")
        Thread {
            try {
                val t = Themes.resolve(Prefs.getThemeId(this), Prefs.getCustomColor(this))
                val avatar = ShareCard.fetchAvatar(this, cached.first.username)
                val bmp = ShareCard.render(
                    cached.first, t,
                    ShareCard.Options(
                        dark = true,
                        showUsername = true,
                        showTotal = WidgetPrefs.showTotal(this),
                        showStreak = WidgetPrefs.showStreak(this)
                    ),
                    cornerRadius = WidgetPrefs.shapeRadiusFactor(WidgetPrefs.getShape(this)),
                    gapScale = WidgetPrefs.getSpacing(this),
                    customLevels = Studio.effectiveLevels(this, t),
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
                    say("Couldn't generate the card.")
                }
            }
        }.start()
    }

    companion object {
        private const val KEY_TAB = "studio_tab"

        /** Boolean extra: open the account card (username field) on arrival. */
        const val EXTRA_OPEN_ACCOUNT = "open_account"
    }
}
