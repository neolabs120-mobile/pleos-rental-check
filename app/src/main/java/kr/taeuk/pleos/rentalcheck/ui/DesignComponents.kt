package kr.taeuk.pleos.rentalcheck.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import kr.taeuk.pleos.rentalcheck.InspectionStage

// Give the native app a small, code-drawn vehicle mark without adding an image dependency or presenting a simulated vehicle state.
@Composable
internal fun RentalBrand() {
    val ink = MaterialTheme.colorScheme.onPrimary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary) {
            Canvas(Modifier.size(46.dp).padding(9.dp)) {
                val w = size.width; val h = size.height; val stroke = 2.dp.toPx()
                drawRoundRect(ink, Offset(w * .08f, h * .38f), Size(w * .84f, h * .39f), CornerRadius(4.dp.toPx()), style = Stroke(stroke))
                drawLine(ink, Offset(w * .23f, h * .36f), Offset(w * .33f, h * .17f), stroke, StrokeCap.Round)
                drawLine(ink, Offset(w * .33f, h * .17f), Offset(w * .68f, h * .17f), stroke, StrokeCap.Round)
                drawLine(ink, Offset(w * .68f, h * .17f), Offset(w * .78f, h * .36f), stroke, StrokeCap.Round)
                drawCircle(ink, w * .055f, Offset(w * .27f, h * .83f)); drawCircle(ink, w * .055f, Offset(w * .73f, h * .83f))
            }
        }
        Text("렌트체크", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

// Keep stage names readable at large text sizes: compact displays show the exact current step instead of squeezing six labels into clipped cells.
@Composable
internal fun JourneyProgress(current: InspectionStage) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 860.dp || LocalDensity.current.fontScale > 1.2f) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StateBadge("${current.ordinal + 1} / 6 · ${current.title}")
                Text("계정 연결부터 반납 기록까지", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val colors = MaterialTheme.colorScheme
            InspectionStage.entries.forEachIndexed { index, stage ->
                val selected = stage == current
                val done = stage.ordinal < current.ordinal
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // A thin track above each label reads as one continuous journey: filled up to the current step, muted ahead.
                    Box(Modifier.fillMaxWidth().height(4.dp).background(
                        if (done || selected) colors.primary else colors.outlineVariant, RoundedCornerShape(50)))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = CircleShape, color = when { selected -> colors.primary; done -> colors.primaryContainer; else -> colors.surfaceContainerHighest }) {
                            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                                Text(if (done) "✓" else "${index + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                    color = when { selected -> colors.onPrimary; done -> colors.primary; else -> colors.onSurfaceVariant })
                            }
                        }
                        Text(stage.title, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = when { selected -> colors.onSurface; done -> colors.primary; else -> colors.onSurfaceVariant })
                    }
                }
            }
        }
    }
}

// Distinguish a short state label from explanatory copy; this badge is informational and never an extra navigation control.
@Composable
internal fun StateBadge(text: String, attention: Boolean = false) {
    Surface(shape = RoundedCornerShape(50), color = if (attention) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium,
            color = if (attention) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

// Show a phone illustration before a genuine QR exists; the simple line drawing cannot be mistaken for a scannable pairing code.
@Composable
internal fun PhoneConnectionMark() {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = colors.primaryContainer) {
        Canvas(Modifier.size(160.dp).padding(26.dp)) {
            val w = size.width; val h = size.height; val stroke = Stroke(3.dp.toPx())
            drawRoundRect(colors.primary, Offset(w * .24f, h * .02f), Size(w * .52f, h * .96f), CornerRadius(12.dp.toPx()), style = stroke)
            drawLine(colors.primary, Offset(w * .4f, h * .13f), Offset(w * .6f, h * .13f), 3.dp.toPx(), StrokeCap.Round)
            drawLine(colors.primary, Offset(w * .43f, h * .87f), Offset(w * .57f, h * .87f), 3.dp.toPx(), StrokeCap.Round)
            drawRoundRect(colors.primary.copy(alpha = .18f), Offset(w * .34f, h * .33f), Size(w * .32f, h * .32f), CornerRadius(6.dp.toPx()))
        }
    }
}
