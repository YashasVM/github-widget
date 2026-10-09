package com.example.githubwidget.ui.stats

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.githubwidget.data.SampleData
import com.example.githubwidget.design.WidgetDesign
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the "Share your year" picture to build/share-renders/ for a visual check. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xxhdpi")
class ShareImageRenderTest {

    @Test
    fun renderShareImage() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bmp = ShareImage.render(ctx, SampleData.userData, null, WidgetDesign())
        assertEquals(1080, bmp.width)
        assertEquals(1350, bmp.height)
        val out = File("build/share-renders").apply { mkdirs() }
        File(out, "share_year.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
