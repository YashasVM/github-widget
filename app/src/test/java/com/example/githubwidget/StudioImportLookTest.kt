package com.example.githubwidget

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class StudioImportLookTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferences() {
        listOf("gh_studio", "github_widget_prefs", "gh_widget_style").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun integerCellSizeIsStoredAsFloat() {
        assertNull(Studio.importLook(context, """{"look":{"studio_cell_size":1}}"""))
        assertEquals(1f, Studio.getCellSize(context), 0f)
        assertEquals(
            1f,
            context.getSharedPreferences("gh_studio", Context.MODE_PRIVATE)
                .getFloat("cell_size", -1f),
            0f
        )
    }

    @Test
    fun fractionalCellSizeIsPreserved() {
        assertNull(Studio.importLook(context, """{"look":{"studio_cell_size":1.2}}"""))
        assertEquals(1.2f, Studio.getCellSize(context), 0.0001f)
    }

    @Test
    fun malformedCellSizesLeaveExistingFloatUntouched() {
        for (value in listOf("\"1\"", "true", "false", "null", "{}", "[]")) {
            Studio.setCellSize(context, 1.1f)
            assertNull(Studio.importLook(context, """{"look":{"studio_cell_size":$value}}"""))
            assertEquals(1.1f, Studio.getCellSize(context), 0.0001f)
        }
    }

    @Test
    fun malformedCellSizeWithoutExistingValueKeepsDefault() {
        assertNull(Studio.importLook(context, """{"look":{"studio_cell_size":"bad"}}"""))
        assertEquals(1f, Studio.getCellSize(context), 0f)
    }

    @Test
    fun finiteOutOfRangeAndFloatOverflowValuesAreClamped() {
        for ((value, expected) in listOf("0" to 0.8f, "2" to 1.3f,
            "-1e100" to 0.8f, "1e100" to 1.3f)) {
            assertNull(Studio.importLook(context, """{"look":{"studio_cell_size":$value}}"""))
            assertEquals(expected, Studio.getCellSize(context), 0f)
            assertEquals(expected, context.getSharedPreferences("gh_studio", Context.MODE_PRIVATE)
                .getFloat("cell_size", -1f), 0f)
        }
    }

    @Test
    fun nonFiniteAndDoubleOverflowInputCannotPoisonCellSize() {
        for (value in listOf("1e400", "-1e400", "NaN", "Infinity", "-Infinity")) {
            Studio.setCellSize(context, 1.1f)
            // Android's parser may reject these or represent them as nonnumeric values.
            Studio.importLook(context, """{"look":{"studio_cell_size":$value}}""")
            assertEquals(1.1f, Studio.getCellSize(context), 0.0001f)
        }
    }

    @Test
    fun exportedDefaultCellSizeCanBeImportedAndReadAgain() {
        Studio.setCellSize(context, 1f)
        Studio.setTextSize(context, 2)
        Studio.setCustomLabel(context, "My graph")
        Studio.setShowActiveDays(context, true)
        val json = Studio.exportLook(context)
        // Exercise the actual JSONObject round trip, not a synthetic Float value.
        assertEquals(1, JSONObject(json).getJSONObject("look").getInt("studio_cell_size"))
        context.getSharedPreferences("gh_studio", Context.MODE_PRIVATE).edit().clear().commit()

        assertNull(Studio.importLook(context, json))
        assertEquals(1f, Studio.getCellSize(context), 0f)
        assertEquals(2, Studio.getTextSize(context))
        assertEquals("My graph", Studio.getCustomLabel(context))
        assertEquals(true, Studio.showActiveDays(context))
    }
}

