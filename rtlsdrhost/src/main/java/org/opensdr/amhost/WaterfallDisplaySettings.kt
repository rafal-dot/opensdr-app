package org.opensdr.amhost

internal const val SPECTRUM_MIN_DBFS = -100f
internal const val SPECTRUM_MAX_DBFS = 0f
internal const val MAX_WATERFALL_FLOOR_DBFS = -20f
internal const val MIN_WATERFALL_FLOOR_SENSITIVITY_PERCENT = 0f
internal const val MAX_WATERFALL_FLOOR_SENSITIVITY_PERCENT = 100f
internal const val MIN_WATERFALL_CONTRAST_PERCENT = 0f
internal const val MAX_WATERFALL_CONTRAST_PERCENT = 100f

private const val MIN_WATERFALL_RANGE_DB = 10f
private const val MAX_WATERFALL_RANGE_DB = 90f

internal data class WaterfallDisplaySettings(
    val floorDbfs: Float,
    val contrastPercent: Float,
) {
    private val maximumRangeDb: Float
        get() = (SPECTRUM_MAX_DBFS - floorDbfs)
            .coerceIn(MIN_WATERFALL_RANGE_DB, MAX_WATERFALL_RANGE_DB)

    val rangeDb: Float
        get() {
            val contrastFraction = contrastPercent / MAX_WATERFALL_CONTRAST_PERCENT
            return maximumRangeDb -
                ((maximumRangeDb - MIN_WATERFALL_RANGE_DB) * contrastFraction)
        }

    val ceilingDbfs: Float
        get() = (floorDbfs + rangeDb).coerceAtMost(SPECTRUM_MAX_DBFS)

    val floorSensitivityPercent: Float
        get() {
            val floorRangeDb = MAX_WATERFALL_FLOOR_DBFS - SPECTRUM_MIN_DBFS
            return ((MAX_WATERFALL_FLOOR_DBFS - floorDbfs) / floorRangeDb) *
                MAX_WATERFALL_FLOOR_SENSITIVITY_PERCENT
        }

    fun withFloor(floorDbfs: Float): WaterfallDisplaySettings {
        return normalized(floorDbfs = floorDbfs, contrastPercent = contrastPercent)
    }

    fun withFloorSensitivity(floorSensitivityPercent: Float): WaterfallDisplaySettings {
        val normalizedSensitivity = floorSensitivityPercent.coerceIn(
            MIN_WATERFALL_FLOOR_SENSITIVITY_PERCENT,
            MAX_WATERFALL_FLOOR_SENSITIVITY_PERCENT,
        )
        val floorRangeDb = MAX_WATERFALL_FLOOR_DBFS - SPECTRUM_MIN_DBFS
        val floorDbfs = MAX_WATERFALL_FLOOR_DBFS -
            (floorRangeDb * normalizedSensitivity / MAX_WATERFALL_FLOOR_SENSITIVITY_PERCENT)
        return normalized(floorDbfs = floorDbfs, contrastPercent = contrastPercent)
    }

    fun withContrast(contrastPercent: Float): WaterfallDisplaySettings {
        return normalized(floorDbfs = floorDbfs, contrastPercent = contrastPercent)
    }

    companion object {
        fun normalized(floorDbfs: Float, contrastPercent: Float): WaterfallDisplaySettings {
            val normalizedFloor = floorDbfs.coerceIn(
                SPECTRUM_MIN_DBFS,
                MAX_WATERFALL_FLOOR_DBFS,
            )
            return WaterfallDisplaySettings(
                floorDbfs = normalizedFloor,
                contrastPercent = contrastPercent.coerceIn(
                    MIN_WATERFALL_CONTRAST_PERCENT,
                    MAX_WATERFALL_CONTRAST_PERCENT,
                ),
            )
        }

        fun fromRangeDb(floorDbfs: Float, rangeDb: Float): WaterfallDisplaySettings {
            val normalizedFloor = floorDbfs.coerceIn(
                SPECTRUM_MIN_DBFS,
                MAX_WATERFALL_FLOOR_DBFS,
            )
            val maximumRange = (SPECTRUM_MAX_DBFS - normalizedFloor)
                .coerceIn(MIN_WATERFALL_RANGE_DB, MAX_WATERFALL_RANGE_DB)
            val normalizedRange = rangeDb.coerceIn(MIN_WATERFALL_RANGE_DB, maximumRange)
            val adjustableRange = maximumRange - MIN_WATERFALL_RANGE_DB
            val contrastPercent = if (adjustableRange <= 0f) {
                MAX_WATERFALL_CONTRAST_PERCENT
            } else {
                ((maximumRange - normalizedRange) / adjustableRange) *
                    MAX_WATERFALL_CONTRAST_PERCENT
            }
            return normalized(normalizedFloor, contrastPercent)
        }
    }
}

internal fun dbfsToSpectrumLevel(dbfs: Float): Float {
    return (dbfs - SPECTRUM_MIN_DBFS)
        .coerceIn(0f, SPECTRUM_MAX_DBFS - SPECTRUM_MIN_DBFS)
}
