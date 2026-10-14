package com.example.githubwidget

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/**
 * Bundled typefaces (all SIL OFL 1.1, texts in assets/licenses) and the
 * Studio font choice for widget text, the live preview and share cards.
 *
 * The choice lives in the Studio prefs (key "font") so look import/export
 * carries it. "system" is the default and reproduces the original look.
 */
object Fonts {

    const val SYSTEM = "system"

    private const val NAME = "gh_studio"
    private const val KEY_FONT = "font"

    /**
     * One pickable font.
     *
     * @param res font-family resource, or null for the platform default
     * @param mono true when every glyph has the same advance (numbers line up)
     * @param bold false for single-weight display faces where synthetic bold smears
     * @param scale text-size multiplier for faces that run much wider than normal
     * @param license asset path of the license text, or null for the system font
     */
    data class FontOption(
        val id: String,
        val name: String,
        val res: Int?,
        val mono: Boolean,
        val widgetLayout: Int,
        val bold: Boolean = true,
        val scale: Float = 1f,
        val license: String? = null
    )

    val ALL = listOf(
        FontOption(SYSTEM, "System", null, false, R.layout.widget_contribution),
        FontOption(
            "inter", "Inter", R.font.inter, false, R.layout.widget_contribution_inter,
            license = "licenses/inter.txt"
        ),
        FontOption(
            "space_grotesk", "Space Grotesk", R.font.space_grotesk, false,
            R.layout.widget_contribution_space_grotesk,
            license = "licenses/spacegrotesk.txt"
        ),
        FontOption(
            "jetbrains_mono", "JetBrains Mono", R.font.jetbrains_mono, true,
            R.layout.widget_contribution_jetbrains_mono,
            license = "licenses/jetbrainsmono.txt"
        ),
        FontOption(
            "outfit", "Outfit", R.font.outfit, false, R.layout.widget_contribution_outfit,
            license = "licenses/outfit.txt"
        ),
        FontOption(
            "press_start_2p", "Press Start 2P", R.font.press_start_2p, true,
            R.layout.widget_contribution_press_start_2p,
            bold = false, scale = 0.75f, license = "licenses/pressstart2p.txt"
        ),
        FontOption(
            "dm_serif_display", "DM Serif Display", R.font.dm_serif_display, false,
            R.layout.widget_contribution_dm_serif_display,
            bold = false, license = "licenses/dmserifdisplay.txt"
        )
    )

    /** Loaded typefaces by "id/bold"; font files are parsed once per process. */
    private val cache = HashMap<String, Typeface>()

    fun option(id: String): FontOption = ALL.firstOrNull { it.id == id } ?: ALL[0]

    fun get(context: Context): String =
        option(prefs(context).getString(KEY_FONT, SYSTEM) ?: SYSTEM).id

    fun set(context: Context, id: String) {
        prefs(context).edit().putString(KEY_FONT, option(id).id).apply()
    }

    fun selected(context: Context): FontOption = option(get(context))

    /** True when the user kept the platform font (render exactly as before). */
    fun isSystem(context: Context): Boolean = get(context) == SYSTEM

    /**
     * Typeface for [id]; "system" gives Typeface.DEFAULT / DEFAULT_BOLD.
     * Falls back to the default if a font resource can't be loaded.
     */
    fun typeface(context: Context, id: String, bold: Boolean = false): Typeface {
        val opt = option(id)
        val useBold = bold && opt.bold
        val key = "${opt.id}/$useBold"
        synchronized(cache) { cache[key]?.let { return it } }
        val base = opt.res?.let {
            try {
                ResourcesCompat.getFont(context.applicationContext, it)
            } catch (_: Exception) {
                null
            }
        }
        val tf = when {
            base == null && useBold -> Typeface.DEFAULT_BOLD
            base == null -> Typeface.DEFAULT
            useBold -> Typeface.create(base, Typeface.BOLD)
            else -> base
        }
        synchronized(cache) { cache[key] = tf }
        return tf
    }

    /** Typeface of the chosen Studio font. */
    fun current(context: Context, bold: Boolean = false): Typeface =
        typeface(context, get(context), bold)

    /** Big numbers: system keeps its bold monospace, other fonts use their bold. */
    fun numbers(context: Context): Typeface =
        if (isSystem(context)) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        else current(context, bold = true)

    /** Month labels etc. on drawn bitmaps: null keeps the Paint default. */
    fun graphLabels(context: Context): Typeface? =
        if (isSystem(context)) null else current(context)

    /** Widget layout with the chosen font baked in (RemoteViews can't set typefaces). */
    fun widgetLayout(context: Context): Int = selected(context).widgetLayout

    /** Bundled Inter, for app UI that wants a clean sans regardless of the Studio pick. */
    fun inter(context: Context, bold: Boolean = false): Typeface =
        typeface(context, "inter", bold)

    /** Bundled Space Grotesk, for headings and display text in app UI. */
    fun spaceGrotesk(context: Context, bold: Boolean = false): Typeface =
        typeface(context, "space_grotesk", bold)

    /** Bundled JetBrains Mono, for numbers and code-like labels in app UI. */
    fun mono(context: Context, bold: Boolean = false): Typeface =
        typeface(context, "jetbrains_mono", bold)

    /** Full license text for [opt] from assets, or null for the system font. */
    fun licenseText(context: Context, opt: FontOption): String? {
        val path = opt.license ?: return null
        return try {
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
