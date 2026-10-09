package com.example.githubwidget.design

import android.content.Context
import android.graphics.Typeface
import android.os.Build

/**
 * The typeface used for big numbers in Stats and the share image. Only sans
 * faces, so numbers stay readable at display size. The variable fonts are
 * bundled under assets/fonts and cover every weight from one file.
 */
enum class NumberFont(val label: String, val asset: String?, val sample: String) {
    SYSTEM("System", null, "Roboto"),
    INTER("Inter", "fonts/Inter-Variable.ttf", "Inter"),
    SPACE_GROTESK("Space Grotesk", "fonts/SpaceGrotesk-Variable.ttf", "Grotesk"),
    ;

    companion object {
        fun fromName(name: String?): NumberFont = entries.firstOrNull { it.name == name } ?: SYSTEM

        /** A typeface at [weight] (100–900) for canvas drawing. Falls back to the system sans if the asset can't load. */
        fun typeface(context: Context, font: NumberFont, weight: Int): Typeface {
            val asset = font.asset
            if (asset != null) {
                runCatching {
                    return Typeface.Builder(context.assets, asset)
                        .setFontVariationSettings("'wght' $weight")
                        .build()
                }
            }
            return systemTypeface(weight)
        }

        private fun systemTypeface(weight: Int): Typeface {
            val base = Typeface.create("sans-serif", Typeface.NORMAL)
            return if (Build.VERSION.SDK_INT >= 28) {
                Typeface.create(base, weight, false)
            } else if (weight >= 600) {
                Typeface.create(base, Typeface.BOLD)
            } else {
                base
            }
        }
    }
}
