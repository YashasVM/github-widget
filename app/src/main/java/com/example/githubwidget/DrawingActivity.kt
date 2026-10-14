package com.example.githubwidget

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import java.io.File
import java.io.FileOutputStream

/** Drawing Studio: draw a background, export it, use it on the widget. */
class DrawingActivity : AppCompatActivity() {

    private fun drawingsDir(): File = File(filesDir, "drawings").apply { mkdirs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_drawing)

        val root = findViewById<View>(R.id.dr_root)
        val bar = findViewById<View>(R.id.dr_bar)
        Ui.edgeToEdge(this, root, top = findViewById(R.id.dr_appbar), bottom = bar)
        Ui.topBar(this, findViewById<MaterialToolbar>(R.id.dr_toolbar))
        fun say(msg: String) = Ui.snack(root, msg, above = bar)

        val canvas = findViewById<DrawingView>(R.id.drawing_canvas)
        val colorBtn = findViewById<MaterialButton>(R.id.drawing_color)
        val colorHex = findViewById<TextView>(R.id.drawing_color_hex)
        val sizeText = findViewById<TextView>(R.id.drawing_size_text)
        val sizeSlider = findViewById<Slider>(R.id.drawing_size)
        val undoBtn = findViewById<MaterialButton>(R.id.drawing_undo)
        val redoBtn = findViewById<MaterialButton>(R.id.drawing_redo)
        val clearBtn = findViewById<MaterialButton>(R.id.drawing_clear)

        // Square canvas as wide as the screen, but never taller than about half of it.
        canvas.doOnLayout { v ->
            val side = minOf(v.width, (resources.displayMetrics.heightPixels * 0.5f).toInt())
            if (v.height != side) v.updateLayoutParams { height = side }
        }

        val toolChips = tools(findViewById(R.id.drawing_tools)) { i ->
            canvas.tool = TOOLS[i].second
        }
        Ui.select(toolChips, TOOLS.indexOfFirst { it.second == canvas.tool }.coerceAtLeast(0))

        fun syncHistory() {
            // Widget.GH.Button.Icon dims its icon when disabled; no alpha hacks.
            undoBtn.isEnabled = canvas.canUndo
            redoBtn.isEnabled = canvas.canRedo
            clearBtn.isEnabled = !canvas.isEmpty
        }
        canvas.onChange = { syncHistory() }
        syncHistory()

        fun paintColor(c: Int) {
            Ui.paintSwatch(colorBtn, c)
            colorHex.text = Themes.toHex(c)
            colorBtn.contentDescription = "Brush color, ${Themes.toHex(c)}. Double-tap to change"
        }
        paintColor(canvas.color)
        colorBtn.setOnClickListener {
            ColorPickerDialog.show(this, canvas.color) { c ->
                canvas.color = c
                paintColor(c)
            }
        }

        sizeSlider.value = canvas.strokeWidth.coerceIn(sizeSlider.valueFrom, sizeSlider.valueTo)
        sizeText.text = "${sizeSlider.value.toInt()}px"
        sizeSlider.setLabelFormatter { "${it.toInt()}px" }
        sizeSlider.addOnChangeListener { _, v, _ ->
            sizeText.text = "${v.toInt()}px"
            canvas.strokeWidth = v
        }

        undoBtn.setOnClickListener { canvas.undo() }
        redoBtn.setOnClickListener { canvas.redo() }
        clearBtn.setOnClickListener {
            if (canvas.isEmpty) return@setOnClickListener
            // Clear wipes the undo history too, so ask first.
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear the canvas?")
                .setMessage("This removes the drawing and its undo history.")
                .setPositiveButton("Clear") { _, _ -> canvas.clear() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        fun savePng(): File? {
            return try {
                val file = File(drawingsDir(), "drawing_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    canvas.export().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                // Keep the folder tidy: newest 10 drawings only.
                drawingsDir().listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(10)
                    ?.forEach { it.delete() }
                file
            } catch (_: Exception) {
                null
            }
        }

        findViewById<MaterialButton>(R.id.drawing_save).setOnClickListener {
            if (canvas.isEmpty) {
                say("Draw something first")
                return@setOnClickListener
            }
            if (savePng() != null) {
                say("Drawing saved")
            } else {
                say("Couldn't save the drawing.")
            }
        }

        findViewById<MaterialButton>(R.id.drawing_use_bg).setOnClickListener {
            if (canvas.isEmpty) {
                say("Draw something first")
                return@setOnClickListener
            }
            val file = savePng()
            if (file == null) {
                say("Couldn't save the drawing.")
                return@setOnClickListener
            }
            Studio.setImageUri(this, "file://${file.absolutePath}")
            Studio.setBgType(this, Studio.BG_IMAGE)
            Prefs.requestRepaint(this)
            // The screen closes right away, so this one stays a Toast.
            Toast.makeText(this, "Drawing is now your widget background", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<MaterialButton>(R.id.drawing_share).setOnClickListener {
            if (canvas.isEmpty) {
                say("Draw something first")
                return@setOnClickListener
            }
            try {
                val bmp = canvas.export()
                ShareCard.share(this, bmp)
                bmp.recycle()
            } catch (_: Exception) {
                say("Couldn't share the drawing.")
            }
        }
    }

    companion object {
        /** Tool name, DrawingView tool id, icon. */
        val TOOLS = listOf(
            Triple("Brush", DrawingView.TOOL_BRUSH, R.drawable.ic_ui_brush_24),
            Triple("Eraser", DrawingView.TOOL_ERASER, R.drawable.ic_ui_eraser_24),
            Triple("Glow", DrawingView.TOOL_HIGHLIGHT, R.drawable.ic_ui_glow_24),
            Triple("Line", DrawingView.TOOL_LINE, R.drawable.ic_ui_line_24),
            Triple("Rect", DrawingView.TOOL_RECT, R.drawable.ic_ui_square_24),
            Triple("Circle", DrawingView.TOOL_CIRCLE, R.drawable.ic_ui_circle_24),
            Triple("Fill", DrawingView.TOOL_FILL, R.drawable.ic_ui_fill_24)
        )

        /**
         * Single-choice tool chips with icons. The selected chip shows the tonal
         * fill (no check mark, so the tool icon stays visible).
         */
        fun tools(group: ChipGroup, onPick: (Int) -> Unit): List<Chip> {
            val ctx = group.context
            val chips = Ui.chips(group, TOOLS.map { it.first }, onPick)
            chips.forEachIndexed { i, c ->
                c.setChipIconResource(TOOLS[i].third)
                c.isChipIconVisible = true
                c.chipIconTint = ColorStateList.valueOf(Ui.color(ctx, R.color.gh_text))
                c.isCheckedIconVisible = false
                c.contentDescription = "${TOOLS[i].first} tool"
            }
            return chips
        }
    }
}
