package org.opensdr.amhost

import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import java.util.Locale

@Composable
internal fun rememberWaterfallClock(hasHistory: Boolean): LongState {
    val clock = remember { mutableLongStateOf(spectrumNowMs()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(hasHistory, lifecycle) {
        if (!hasHistory) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) withFrameNanos { clock.longValue = spectrumNowMs() }
        }
    }
    return clock
}

internal data class WaterfallBandLabel(val row: Int, val text: String)

internal fun waterfallBandLabels(rows: List<HostSpectrumFrame?>): List<WaterfallBandLabel> = buildList {
    var previousEpoch: Long? = null
    rows.forEachIndexed { index, frame ->
        val source = frame?.source
        if (source != null && source.epoch != previousEpoch) {
            add(WaterfallBandLabel(index, String.format(Locale.ROOT, "IQ %.3f MHz / %.0f kHz",
                source.centerHz / 1_000_000.0, source.sampleRateHz / 1000.0)))
        }
        previousEpoch = source?.epoch
    }
}

internal fun DrawScope.drawWaterfallBandLabels(
    labels: List<WaterfallBandLabel>, paint: Paint, top: Float, height: Float, scrollFraction: Float,
) {
    val cellHeight = height / WATERFALL_ROW_COUNT
    val labelHeight = 15.sp.toPx()
    paint.textSize = 10.sp.toPx()
    paint.color = android.graphics.Color.WHITE
    var previousBottom = top - 1f
    clipRect(left = 0f, top = top, right = size.width, bottom = top + height) {
        for (label in labels) {
            val y = (top + (label.row + scrollFraction - 1f) * cellHeight).coerceAtLeast(top)
            drawLine(Color(0xFFFACC15), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            if (y < previousBottom || y + labelHeight > top + height) continue
            drawRect(Color(0xCC020617), Offset(0f, y), Size(size.width, labelHeight))
            drawIntoCanvas { it.nativeCanvas.drawText(label.text, 4.dp.toPx(), y + labelHeight * 0.78f, paint) }
            previousBottom = y + labelHeight + 2.dp.toPx()
        }
    }
}
