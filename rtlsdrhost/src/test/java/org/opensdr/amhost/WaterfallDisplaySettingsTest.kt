package org.opensdr.amhost

import org.junit.Assert.assertEquals
import org.junit.Test

class WaterfallDisplaySettingsTest {
    @Test
    fun `defaults match floor sensitivity 30 percent and contrast 80 percent`() {
        val settings = WaterfallDisplaySettings.normalized(
            floorDbfs = DEFAULT_WATERFALL_FLOOR_DBFS,
            contrastPercent = DEFAULT_WATERFALL_CONTRAST_PERCENT,
        )

        assertEquals(30f, settings.floorSensitivityPercent, 0.001f)
        assertEquals(80f, settings.contrastPercent, 0.001f)
        assertEquals(settings, settings.withFloorSensitivity(30f).withContrast(80f))
        assertEquals(16.8f, settings.rangeDb, 0.001f)
        assertEquals(-27.2f, settings.ceilingDbfs, 0.001f)
    }

    @Test
    fun `normalization keeps controls within their ranges`() {
        val settings = WaterfallDisplaySettings.normalized(
            floorDbfs = -20f,
            contrastPercent = 120f,
        )

        assertEquals(-20f, settings.floorDbfs)
        assertEquals(100f, settings.contrastPercent)
        assertEquals(10f, settings.rangeDb)
        assertEquals(-10f, settings.ceilingDbfs)
    }

    @Test
    fun `moving floor preserves contrast when it remains valid`() {
        val settings = WaterfallDisplaySettings.normalized(
            floorDbfs = -75f,
            contrastPercent = 75f,
        ).withFloor(-80f)

        assertEquals(-80f, settings.floorDbfs)
        assertEquals(75f, settings.contrastPercent)
        assertEquals(-52.5f, settings.ceilingDbfs)
    }

    @Test
    fun `floor sensitivity increases as floor moves toward weaker signals`() {
        val leastSensitive = WaterfallDisplaySettings.normalized(
            floorDbfs = -20f,
            contrastPercent = 75f,
        )
        val mostSensitive = leastSensitive.withFloorSensitivity(100f)

        assertEquals(0f, leastSensitive.floorSensitivityPercent)
        assertEquals(-100f, mostSensitive.floorDbfs)
        assertEquals(100f, mostSensitive.floorSensitivityPercent)
    }

    @Test
    fun `previous dB range maps to equivalent contrast`() {
        val settings = WaterfallDisplaySettings.fromRangeDb(
            floorDbfs = -75f,
            rangeDb = 60f,
        )

        assertEquals(60f, settings.rangeDb, 0.001f)
    }

    @Test
    fun `dBFS values map to fixed spectrum levels`() {
        assertEquals(0f, dbfsToSpectrumLevel(-100f))
        assertEquals(40f, dbfsToSpectrumLevel(-60f))
        assertEquals(100f, dbfsToSpectrumLevel(0f))
    }
}
