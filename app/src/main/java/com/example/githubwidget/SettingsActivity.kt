package com.example.githubwidget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File

/** Settings: account, refresh schedule, look transfer, data/reset, privacy, about. */
class SettingsActivity : AppCompatActivity() {

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private lateinit var root: View

    private fun say(msg: String) = Ui.snack(root, msg)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        root = findViewById(R.id.set_root)
        Ui.edgeToEdge(this, root, top = findViewById(R.id.set_appbar), bottom = root)
        Ui.topBar(this, findViewById<MaterialToolbar>(R.id.set_toolbar))

        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            findViewById<TextView>(R.id.set_version).text =
                "Version ${info.versionName} (${info.versionCode})"
        } catch (_: Exception) {
            // Version label keeps its static fallback.
        }
        // The version row is information only.
        findViewById<View>(R.id.set_about).apply {
            isClickable = false
            background = null
        }

        // ---------- account ----------

        findViewById<View>(R.id.set_account).setOnClickListener {
            // Home owns the username field (validation, first load); open it there.
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN_ACCOUNT, true)
            )
            finish()
        }

        // ---------- refresh ----------

        findViewById<View>(R.id.set_refresh).setOnClickListener {
            val u = Prefs.getUsername(this)
            if (u.isBlank()) {
                say("Set a GitHub username first")
                return@setOnClickListener
            }
            Prefs.requestRefresh(this)
            say("Refreshing widgets…")
        }

        val intervalValue = findViewById<TextView>(R.id.set_interval_value)
        fun paintInterval() {
            intervalValue.text = intervalLabel(Prefs.getRefreshHours(this))
        }
        paintInterval()
        findViewById<View>(R.id.set_interval).setOnClickListener {
            val choices = Prefs.REFRESH_HOURS
            MaterialAlertDialogBuilder(this)
                .setTitle("Background refresh")
                .setSingleChoiceItems(
                    choices.map { intervalLabel(it) }.toTypedArray(),
                    choices.indexOf(Prefs.getRefreshHours(this))
                ) { dialog, which ->
                    Prefs.setRefreshHours(this, choices[which])
                    paintInterval()
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val wifi = findViewById<CompoundButton>(R.id.set_wifi_only).apply {
            isChecked = Prefs.isWifiOnly(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setWifiOnly(this@SettingsActivity, checked)
            }
        }
        // The whole row toggles; TalkBack focuses the switch itself (it has the label).
        findViewById<View>(R.id.set_wifi_row).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { wifi.toggle() }
        }

        // ---------- look ----------

        findViewById<View>(R.id.set_export).setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, Studio.exportLook(this@SettingsActivity))
            }
            startActivity(Intent.createChooser(intent, "Share look"))
        }

        findViewById<View>(R.id.set_import).setOnClickListener { showImport() }

        findViewById<View>(R.id.set_reset_look).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Reset look?")
                .setMessage("Widget style, theme, background and graph settings return to defaults. Your username and saved data are kept.")
                .setPositiveButton("Reset look") { _, _ ->
                    // Same as Home's reset: keep the old look so it can be undone.
                    val before = Studio.exportLook(this)
                    WidgetPrefs.resetToDefaults(this)
                    Studio.resetBackground(this)
                    Studio.resetGraph(this)
                    Fonts.set(this, Fonts.SYSTEM)
                    Studio.setCustomLabel(this, "")
                    Studio.setShowActiveDays(this, false)
                    Prefs.requestRepaint(this)
                    Ui.snack(root, "Look reset", action = "Undo") {
                        if (Studio.importLook(this, before) == null) Prefs.requestRepaint(this)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // ---------- data ----------

        findViewById<View>(R.id.set_clear_cache).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear cached data?")
                .setMessage("The saved contribution graph is removed. Widgets show a loading state until the next refresh.")
                .setPositiveButton("Clear") { _, _ ->
                    getSharedPreferences("gh_widget_cache", MODE_PRIVATE).edit().clear().apply()
                    Prefs.requestRefresh(this)
                    paintLastRefreshed()
                    say("Cache cleared")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        findViewById<View>(R.id.set_reset_all).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Reset everything?")
                .setMessage("Clears your username, look, per-widget setups and cached data. Drawings are kept.")
                .setPositiveButton("Reset everything") { _, _ ->
                    resetEverything()
                    // This screen closes and Home restarts in its first-run state,
                    // so a Toast is the message that survives the switch.
                    Toast.makeText(this, "Everything reset", Toast.LENGTH_SHORT).show()
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // ---------- about ----------

        fun openUrl(url: String) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                say("Couldn't open the link.")
            }
        }
        findViewById<View>(R.id.set_repo).setOnClickListener {
            openUrl("https://github.com/mrduhlol/github-widget")
        }
        findViewById<View>(R.id.set_issue).setOnClickListener {
            openUrl("https://github.com/mrduhlol/github-widget/issues/new")
        }
        findViewById<View>(R.id.set_font_licenses).setOnClickListener {
            showFontLicenses()
        }
    }

    private fun showImport() {
        val layout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputStyle).apply {
            hint = "Shared look"
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val field = TextInputEditText(layout.context).apply {
            minLines = 2
            maxLines = 6
        }
        layout.addView(field)
        MaterialAlertDialogBuilder(this)
            .setTitle("Import look")
            .setMessage("Paste the text from \"Share look\".")
            .setView(layout)
            .setPositiveButton("Import") { _, _ ->
                val before = Studio.exportLook(this)
                val err = Studio.importLook(this, field.text?.toString().orEmpty())
                if (err == null) {
                    Prefs.requestRepaint(this)
                    Ui.snack(root, "Look imported", action = "Undo") {
                        if (Studio.importLook(this, before) == null) Prefs.requestRepaint(this)
                    }
                } else {
                    say(err)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Bundled fonts and their SIL OFL 1.1 texts (from assets/licenses). */
    private fun showFontLicenses() {
        val fonts = Fonts.ALL.filter { it.license != null }
        MaterialAlertDialogBuilder(this)
            .setTitle("Open-source fonts")
            .setItems(fonts.map { "${it.name} · SIL Open Font License 1.1" }.toTypedArray()) { _, i ->
                val text = Fonts.licenseText(this, fonts[i]) ?: "License text unavailable."
                val body = TextView(this).apply {
                    this.text = text
                    setTextAppearance(R.style.TextAppearance_GH_Caption)
                    setTextColor(Ui.color(context, R.color.gh_text_muted))
                    setTextIsSelectable(true)
                    setPadding(dp(24), dp(8), dp(24), dp(8))
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle(fonts[i].name)
                    .setView(NestedScrollView(this).apply { addView(body) })
                    .setPositiveButton("Close", null)
                    .show()
            }
            .setPositiveButton("Close", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        paintLastRefreshed()
        val u = Prefs.getUsername(this)
        findViewById<TextView>(R.id.set_account_value).text =
            if (u.isBlank()) "Not set · tap to add" else "@$u · tap to change"
    }

    private fun paintLastRefreshed() {
        val ts = Cache.fetchedAt(this)
        findViewById<TextView>(R.id.set_last_refreshed).text =
            "Last refreshed: ${TimeAgo.format(ts)}"
    }

    private fun intervalLabel(hours: Int): String = when (hours) {
        0 -> "Off (manual only)"
        1 -> "Every hour"
        else -> "Every $hours hours"
    }

    private fun resetEverything() {
        for (name in listOf("github_widget_prefs", "gh_widget_style", "gh_studio", "gh_widget_cache")) {
            getSharedPreferences(name, MODE_PRIVATE).edit().clear().apply()
        }
        try {
            val dir = File(applicationInfo.dataDir, "shared_prefs")
            dir.listFiles { f -> f.name.startsWith("gh_widget_") }
                ?.forEach { it.delete() }
        } catch (_: Exception) {
            // Per-widget files are best-effort; globals already reset.
        }
        RefreshScheduler.sync(this)
        Prefs.requestRefresh(this)
    }
}
