package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class DocumentPdfLayoutTest {
    @Test fun normalPagesUseActualDensityPixelsAndKeepZoomAndAspectRatio() {
        for (density in listOf(1, 2, 3, 4)) for (zoom in listOf(50, 100, 150, 200)) {
            val viewport = 360 * density
            val size = documentPdfDisplaySize(viewport, zoom, 240, 320)
            assertEquals(viewport * zoom / 100, size.width)
            assertEquals(size.width * 4 / 3, size.height)
            assertEquals(size.width, Constraints.fixed(size.width, size.height).maxWidth)
        }
    }
    @Test fun extremePageRatiosAndViewportSizesAlwaysProduceRepresentableFiniteConstraints() {
        for (viewport in listOf(0, 1080, 4320, Int.MAX_VALUE)) for (zoom in listOf(50, 100, 200)) {
            for ((width, height) in listOf(1 to Int.MAX_VALUE, Int.MAX_VALUE to 1, 1000 to 100000, 240 to 320)) {
                val size = documentPdfDisplaySize(viewport, zoom, width, height)
                assertTrue(size.width > 0 && size.height > 0)
                val constraints = Constraints.fixed(size.width, size.height)
                assertTrue(constraints.hasBoundedWidth && constraints.hasBoundedHeight)
                assertEquals(size.width, constraints.maxWidth); assertEquals(size.height, constraints.maxHeight)
                for (density in listOf(0.75f, 1.5f, 2.75f, 3f, 4f)) with(Density(density)) {
                    // The Image.size modifier converts our pixels to dp and
                    // back; that round trip must also fit on real densities.
                    val widthPixels = size.width.toDp().roundToPx()
                    val heightPixels = size.height.toDp().roundToPx()
                    assertEquals(size.width, widthPixels); assertEquals(size.height, heightPixels)
                    Constraints.fixed(widthPixels, heightPixels)
                }
                if (size.width > 1 && size.height > 1) {
                    // Integer pixel rounding is the only allowed ratio loss.
                    assertTrue(abs(size.height.toDouble() - size.width.toDouble() * height / width) <= 1.0 + height.toDouble() / width)
                }
            }
        }
    }
    @Test fun oversizedHeightIsScaledWithoutInventingAnInfiniteDpLimit() {
        val size = documentPdfDisplaySize(1080, 200, 1, 300000)
        assertTrue(size.height < 300000)
        assertTrue(size.width < 2160)
        Constraints.fixed(size.width, size.height) // Regression: old 100000dp at density 3 threw here before the page was measured.
    }
}
