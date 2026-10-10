package org.opensdr.amhost

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
internal fun WaterfallCard(
    waterfallFrames: List<HostSpectrumFrame>,
    centerFrequencyHz: Long?,
    sampleRateHz: Int?,
    channelBandwidthHz: Int,
    displaySettings: WaterfallDisplaySettings,
    onDisplaySettingsChange: (WaterfallDisplaySettings) -> Unit,
    showDisplayControls: Boolean = true,
) {
    val clock = rememberWaterfallClock(waterfallFrames.isNotEmpty())
    val timelineTick by remember(clock) { derivedStateOf { clock.longValue / HOST_WATERFALL_ROW_MS } }
    val rows = remember(waterfallFrames, timelineTick) {
        waterfallRowsAt(waterfallFrames, timelineTick * HOST_WATERFALL_ROW_MS)
    }
    val latestFrame = rows.firstOrNull()
    val visibleCenterHz = latestFrame?.source?.centerHz ?: centerFrequencyHz
    val spanHz = (latestFrame?.source?.sampleRateHz ?: sampleRateHz)
        ?.takeIf { it > 0 }?.toLong() ?: DEFAULT_SPECTRUM_SPAN_HZ
    val showCurrentBandwidth = latestFrame?.source?.let {
        it.centerHz == centerFrequencyHz && it.sampleRateHz == sampleRateHz
    } == true
    val startHz = visibleCenterHz?.minus(spanHz / 2)
    val endHz = visibleCenterHz?.plus(spanHz / 2)
    val scale = remember(displaySettings) {
        waterfallScale(displaySettings)
    }
    val peakFrame = remember(waterfallFrames, latestFrame) {
        peakHoldFrame(waterfallFrames.filter {
            latestFrame != null && it.source.epoch == latestFrame.source.epoch &&
                it.source.sequence <= latestFrame.source.sequence
        }.map { it.levels })
    }
    val sourceColumns = WATERFALL_COLUMN_COUNT
    val waterfallRaster = remember(sourceColumns) {
        WaterfallRaster(sourceColumns.coerceAtLeast(2))
    }
    DisposableEffect(waterfallRaster) {
        onDispose { waterfallRaster.close() }
    }
    val waterfallRasterRevision = remember(rows, scale, waterfallRaster) {
        waterfallRaster.update(rows = rows, scale = scale)
    }
    val animatedSpectrumFrame = key(latestFrame?.source?.epoch) {
        rememberAnimatedSpectrumFrame(latestFrame?.levels)
    }
    val bandLabels = remember(rows) { waterfallBandLabels(rows) }
    val bandLabelPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showDisplayControls) {
                Text("Spectrum + waterfall", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Floor ${displaySettings.floorDbfs.roundToInt()} dBFS | " +
                        "contrast ${displaySettings.contrastPercent.roundToInt()}% | " +
                        "IQ span ${formatFrequencySpan(spanHz)} | " +
                        "RX bandwidth ${formatFrequencySpan(channelBandwidthHz.toLong())}",
                    style = MaterialTheme.typography.bodySmall,
                )
                WaterfallContrastControl(
                    settings = displaySettings,
                    onSettingsChange = onDisplaySettingsChange,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatWaterfallEdge(startHz), style = MaterialTheme.typography.bodySmall)
                Text(formatWaterfallEdge(visibleCenterHz), style = MaterialTheme.typography.bodySmall)
                Text(formatWaterfallEdge(endHz), style = MaterialTheme.typography.bodySmall)
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (showDisplayControls) 176.dp else 220.dp),
            ) {
                val spectrumColumns = (sourceColumns * SPECTRUM_UPSAMPLE_FACTOR)
                    .coerceAtMost(size.width.roundToInt().coerceAtLeast(sourceColumns))
                    .coerceAtLeast(sourceColumns)
                val spectrumHeight = (size.height * 0.30f)
                    .coerceAtLeast(42.dp.toPx())
                    .coerceAtMost(size.height * 0.42f)
                val gap = 8.dp.toPx()
                val waterfallTop = spectrumHeight + gap
                val waterfallHeight = (size.height - waterfallTop).coerceAtLeast(1f)
                // Read the clock in drawing, not composition: the bitmap advances
                // at fixed time ticks while this translation follows every vsync.
                val waterfallScrollFraction = ((clock.longValue - timelineTick * HOST_WATERFALL_ROW_MS)
                    .toFloat() / HOST_WATERFALL_ROW_MS).coerceIn(0f, 1f)

                drawReceiverBackground()
                drawFrequencyGrid(spectrumHeight = spectrumHeight, waterfallTop = waterfallTop)
                drawWaterfallHistory(
                    raster = waterfallRaster,
                    rasterRevision = waterfallRasterRevision,
                    top = waterfallTop,
                    height = waterfallHeight,
                    scrollFraction = waterfallScrollFraction,
                )
                if (showCurrentBandwidth) {
                    drawDemodBandwidthOverlay(
                        spanHz = spanHz,
                        channelBandwidthHz = channelBandwidthHz,
                        top = 0f,
                        height = spectrumHeight,
                    )
                }
                drawSpectrumTrace(
                    currentFrame = animatedSpectrumFrame,
                    peakFrame = peakFrame,
                    scale = scale,
                    height = spectrumHeight,
                    visualColumns = spectrumColumns,
                )
                drawCenterCursor(spectrumHeight)
                drawWaterfallBandLabels(bandLabels, bandLabelPaint, waterfallTop, waterfallHeight, waterfallScrollFraction)
            }
        }
    }
}

@Composable
private fun rememberAnimatedSpectrumFrame(frame: IntArray?): FloatArray? {
    if (frame == null || frame.isEmpty()) return null

    val animatedFrame = FloatArray(frame.size)
    for (index in frame.indices) {
        val animatedLevel by animateFloatAsState(
            targetValue = frame[index].coerceIn(0, 100).toFloat(),
            animationSpec = tween(durationMillis = SPECTRUM_TRACE_ANIMATION_MS),
            label = "hostSpectrumBin$index",
        )
        animatedFrame[index] = animatedLevel
    }
    return animatedFrame
}

private data class WaterfallScale(
    val floor: Float,
    val ceiling: Float,
)

private data class WaterfallColorStop(
    val level: Float,
    val color: Color,
)

private class WaterfallRaster(
    val columns: Int,
) {
    private val bitmap = Bitmap.createBitmap(
        columns,
        WATERFALL_RASTER_ROW_COUNT,
        Bitmap.Config.ARGB_8888,
    )
    private val pixels = IntArray(columns * WATERFALL_RASTER_ROW_COUNT)

    val image: ImageBitmap = bitmap.asImageBitmap()
    val paint = Paint().apply {
        filterQuality = FilterQuality.Low
    }

    private var revision = 0

    fun update(rows: List<HostSpectrumFrame?>, scale: WaterfallScale): Int {
        repeat(WATERFALL_RASTER_ROW_COUNT) { rowIndex ->
            val row = rows.getOrNull(rowIndex)?.levels
            repeat(columns) { columnIndex ->
                val level = if (row == null) {
                    emptySignalLevel(rowIndex = rowIndex, columnIndex = columnIndex)
                } else {
                    waterfallLevel(
                        value = interpolatedFrameValue(
                            frame = row,
                            visualIndex = columnIndex,
                            visualColumns = columns,
                        ),
                        scale = scale,
                    )
                }
                pixels[(rowIndex * columns) + columnIndex] = waterfallColor(level).toArgb()
            }
        }
        bitmap.setPixels(
            pixels,
            0,
            columns,
            0,
            0,
            columns,
            WATERFALL_RASTER_ROW_COUNT,
        )
        revision += 1
        return revision
    }

    fun close() {
        bitmap.recycle()
    }
}

private const val DEFAULT_SPECTRUM_SPAN_HZ = 240_000L
private const val SPECTRUM_UPSAMPLE_FACTOR = 1
private const val PEAK_HOLD_ROWS = 18
private const val EMPTY_SIGNAL_BASE_LEVEL = 0.035f
private const val EMPTY_SIGNAL_VARIATION = 0.045f
private const val SPECTRUM_TRACE_ANIMATION_MS = 220
private const val WATERFALL_RASTER_ROW_COUNT = WATERFALL_ROW_COUNT + 1

private val receiverBackgroundColors = listOf(
    Color(0xFF020617),
    Color(0xFF07111F),
    Color(0xFF0F172A),
)

private val frequencyGuideFractions = floatArrayOf(0.25f, 0.50f, 0.75f)

private val waterfallColorStops = arrayOf(
    WaterfallColorStop(0.00f, Color(0xFF020617)),
    WaterfallColorStop(0.18f, Color(0xFF082F49)),
    WaterfallColorStop(0.42f, Color(0xFF0E7490)),
    WaterfallColorStop(0.66f, Color(0xFF2DD4BF)),
    WaterfallColorStop(0.84f, Color(0xFFFACC15)),
    WaterfallColorStop(1.00f, Color(0xFFFFF7ED)),
)

private fun DrawScope.drawReceiverBackground() {
    drawRect(
        brush = Brush.verticalGradient(
            colors = receiverBackgroundColors,
        ),
    )
}

private fun DrawScope.drawFrequencyGrid(
    spectrumHeight: Float,
    waterfallTop: Float,
) {
    val gridColor = Color(0x33E2E8F0)
    val fineGridColor = Color(0x1AE2E8F0)
    frequencyGuideFractions.forEach { fraction ->
        val x = size.width * fraction
        drawLine(
            color = if (fraction == 0.50f) gridColor else fineGridColor,
            start = Offset(x, 0f),
            end = Offset(x, spectrumHeight),
            strokeWidth = 1.dp.toPx(),
        )
    }
    drawLine(
        color = gridColor,
        start = Offset(0f, waterfallTop - 4.dp.toPx()),
        end = Offset(size.width, waterfallTop - 4.dp.toPx()),
        strokeWidth = 1.dp.toPx(),
    )
    drawLine(
        color = fineGridColor,
        start = Offset(0f, spectrumHeight * 0.50f),
        end = Offset(size.width, spectrumHeight * 0.50f),
        strokeWidth = 1.dp.toPx(),
    )
}

private fun DrawScope.drawWaterfallHistory(
    raster: WaterfallRaster,
    rasterRevision: Int,
    top: Float,
    height: Float,
    scrollFraction: Float,
) {
    if (rasterRevision <= 0) return

    val cellHeight = height / WATERFALL_ROW_COUNT.toFloat()
    val clampedScroll = scrollFraction.coerceIn(0f, 1f)
    val rasterTop = top + ((clampedScroll - 1f) * cellHeight)
    val scaleX = size.width / raster.columns.toFloat()
    val scaleY = cellHeight

    clipRect(left = 0f, top = top, right = size.width, bottom = top + height) {
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.translate(0f, rasterTop)
            canvas.scale(scaleX, scaleY)
            canvas.drawImage(raster.image, Offset.Zero, raster.paint)
            canvas.restore()
        }
    }
}

private fun DrawScope.drawDemodBandwidthOverlay(
    spanHz: Long,
    channelBandwidthHz: Int,
    top: Float,
    height: Float,
) {
    if (spanHz <= 0L || channelBandwidthHz <= 0) return
    val widthFraction = (channelBandwidthHz.toFloat() / spanHz.toFloat()).coerceIn(0f, 1f)
    if (widthFraction <= 0f) return

    val halfWidth = (size.width * widthFraction) / 2f
    val centerX = size.width / 2f
    val left = (centerX - halfWidth).coerceIn(0f, size.width)
    val right = (centerX + halfWidth).coerceIn(left, size.width)
    val lineColor = Color(0x992DD4BF)

    drawRect(
        color = Color(0x182DD4BF),
        topLeft = Offset(left, top),
        size = Size(right - left, height),
    )
    drawLine(
        color = lineColor,
        start = Offset(left, top),
        end = Offset(left, top + height),
        strokeWidth = 1.dp.toPx(),
    )
    drawLine(
        color = lineColor,
        start = Offset(right, top),
        end = Offset(right, top + height),
        strokeWidth = 1.dp.toPx(),
    )
}

private fun DrawScope.drawSpectrumTrace(
    currentFrame: FloatArray?,
    peakFrame: IntArray?,
    scale: WaterfallScale,
    height: Float,
    visualColumns: Int,
) {
    val baseline = height - 4.dp.toPx()
    val usableHeight = (height - 10.dp.toPx()).coerceAtLeast(1f)
    val currentPath = spectrumPath(
        frame = currentFrame,
        scale = scale,
        baseline = baseline,
        usableHeight = usableHeight,
        visualColumns = visualColumns,
    )
    val peakPath = spectrumPath(
        frame = peakFrame?.toSpectrumFloatFrame(),
        scale = scale,
        baseline = baseline,
        usableHeight = usableHeight,
        visualColumns = visualColumns,
    )

    drawPath(
        path = peakPath,
        color = Color(0x99FACC15),
        style = Stroke(width = 1.dp.toPx()),
    )
    drawPath(
        path = currentPath,
        color = Color(0xFFE0F2FE),
        style = Stroke(width = 2.dp.toPx()),
    )
}

private fun DrawScope.drawCenterCursor(height: Float) {
    val x = size.width / 2f
    drawLine(
        color = Color(0xCCF8FAFC),
        start = Offset(x, 0f),
        end = Offset(x, height),
        strokeWidth = 1.5.dp.toPx(),
    )
}

private fun DrawScope.spectrumPath(
    frame: FloatArray?,
    scale: WaterfallScale,
    baseline: Float,
    usableHeight: Float,
    visualColumns: Int,
): Path {
    val path = Path()
    repeat(visualColumns) { index ->
        val x = if (visualColumns <= 1) 0f else size.width * index / (visualColumns - 1).toFloat()
        val level = spectrumLevel(
            frame = frame,
            visualIndex = index,
            visualColumns = visualColumns,
            scale = scale,
        )
        val y = baseline - (level * usableHeight)
        if (index == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }
    return path
}

private fun spectrumLevel(
    frame: FloatArray?,
    visualIndex: Int,
    visualColumns: Int,
    scale: WaterfallScale,
): Float {
    if (frame == null) {
        return emptySignalLevel(rowIndex = 0, columnIndex = visualIndex)
    }
    return waterfallLevel(
        value = interpolatedFrameValue(
            frame = frame,
            visualIndex = visualIndex,
            visualColumns = visualColumns,
        ),
        scale = scale,
    )
}

private fun IntArray.toSpectrumFloatFrame(): FloatArray {
    return FloatArray(size) { index ->
        this[index].coerceIn(0, 100).toFloat()
    }
}

private fun interpolatedFrameValue(
    frame: FloatArray,
    visualIndex: Int,
    visualColumns: Int,
): Float {
    if (frame.isEmpty()) return 0f
    if (frame.size == 1 || visualColumns <= 1) return frame.first()

    val sourcePosition = visualIndex.toFloat() * frame.lastIndex.toFloat() / (visualColumns - 1).toFloat()
    val leftIndex = floor(sourcePosition).toInt().coerceIn(0, frame.lastIndex)
    val rightIndex = (leftIndex + 1).coerceAtMost(frame.lastIndex)
    val fraction = sourcePosition - leftIndex
    return (frame[leftIndex] * (1f - fraction)) + (frame[rightIndex] * fraction)
}

private fun waterfallScale(settings: WaterfallDisplaySettings): WaterfallScale {
    return WaterfallScale(
        floor = dbfsToSpectrumLevel(settings.floorDbfs),
        ceiling = dbfsToSpectrumLevel(settings.ceilingDbfs),
    )
}

private fun peakHoldFrame(frames: List<IntArray>): IntArray? {
    val recentFrames = frames.takeLast(PEAK_HOLD_ROWS).filter { it.isNotEmpty() }
    if (recentFrames.isEmpty()) return null

    val columns = recentFrames.maxOf { it.size }
    return IntArray(columns) { columnIndex ->
        recentFrames.maxOf { frame -> frame.getOrNull(columnIndex) ?: 0 }
    }
}

private fun interpolatedFrameValue(
    frame: IntArray,
    visualIndex: Int,
    visualColumns: Int,
): Float {
    if (frame.isEmpty()) return 0f
    if (frame.size == 1 || visualColumns <= 1) return frame.first().toFloat()

    val sourcePosition = visualIndex.toFloat() * frame.lastIndex.toFloat() / (visualColumns - 1).toFloat()
    val leftIndex = floor(sourcePosition).toInt().coerceIn(0, frame.lastIndex)
    val rightIndex = (leftIndex + 1).coerceAtMost(frame.lastIndex)
    val fraction = sourcePosition - leftIndex
    return (frame[leftIndex] * (1f - fraction)) + (frame[rightIndex] * fraction)
}

private fun waterfallLevel(value: Float, scale: WaterfallScale): Float {
    val span = (scale.ceiling - scale.floor).coerceAtLeast(1f)
    return ((value - scale.floor) / span).coerceIn(0f, 1f)
}

private fun emptySignalLevel(rowIndex: Int, columnIndex: Int): Float {
    val hash = ((rowIndex + 11) * 37 + (columnIndex + 5) * 17) % 29
    return EMPTY_SIGNAL_BASE_LEVEL + (hash / 28f) * EMPTY_SIGNAL_VARIATION
}

private fun waterfallColor(level: Float): Color {
    val clamped = level.coerceIn(0f, 1f)
    val upperIndex = waterfallColorStops.indexOfFirst { clamped <= it.level }
        .takeIf { it >= 0 }
        ?: waterfallColorStops.lastIndex
    if (upperIndex == 0) return waterfallColorStops.first().color
    val lower = waterfallColorStops[upperIndex - 1]
    val upper = waterfallColorStops[upperIndex]
    val localFraction = ((clamped - lower.level) / (upper.level - lower.level).coerceAtLeast(0.001f))
        .coerceIn(0f, 1f)
    return lerp(lower.color, upper.color, localFraction)
}

private fun formatFrequencySpan(spanHz: Long): String {
    return when {
        spanHz >= 1_000_000L -> String.format(Locale.US, "%.2f MHz", spanHz / 1_000_000.0)
        spanHz >= 1_000L -> String.format(Locale.US, "%.0f kHz", spanHz / 1_000.0)
        else -> "$spanHz Hz"
    }
}

private fun formatWaterfallEdge(frequencyHz: Long?): String {
    if (frequencyHz == null || frequencyHz <= 0L) return "-"
    return String.format(Locale.US, "%.3f MHz", frequencyHz / 1_000_000.0)
}
