package com.seccheck

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class Petal(val label: String, val value: Double, val color: Color, val display: String = "")

val PALETTE = listOf(
    0xFF1E88E5, 0xFFE53935, 0xFF43A047, 0xFFFB8C00, 0xFF8E24AA,
    0xFF00ACC1, 0xFFFDD835, 0xFF6D4C41, 0xFF3949AB, 0xFFD81B60,
).map { Color(it) }

fun fmtBytes(b: Long): String {
    val u = listOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble(); var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return if (i == 0) "$b B" else "%.1f %s".format(v, u[i])
}

/** 玫瑰圖(南丁格爾圖):每片花瓣角度相同,半徑 ∝ √數值,所以「面積」才正比於數值。 */
@Composable
fun RoseChart(petals: List<Petal>, modifier: Modifier = Modifier, labelEvery: Int = 1) {
    val tm = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val txt = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxWidth().widthIn(max = 360.dp).aspectRatio(1f)) {
        val n = petals.size
        if (n == 0) return@Canvas
        val maxV = petals.maxOf { it.value }.coerceAtLeast(1e-9)
        val c = Offset(size.width / 2, size.height / 2)
        val rMax = min(c.x, c.y) * 0.70f
        for (f in listOf(0.25f, 0.5f, 0.75f, 1f))
            drawCircle(grid, rMax * sqrt(f), c, style = Stroke(1.dp.toPx()))
        val step = 360f / n
        val style = TextStyle(color = txt, fontSize = 10.sp)
        petals.forEachIndexed { i, p ->
            val r = rMax * sqrt((p.value / maxV).toFloat())
            val start = -90f + i * step - step / 2
            if (r > 0f) drawArc(p.color, start + 0.8f, step - 1.6f, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r))
            if (i % labelEvery == 0 && p.label.isNotEmpty()) {
                val a = Math.toRadians((start + step / 2).toDouble())
                val lr = rMax + 14.dp.toPx()
                val tl = tm.measure(p.label.take(8), style)
                drawText(
                    tl,
                    topLeft = Offset(
                        c.x + (cos(a) * lr).toFloat() - tl.size.width / 2f,
                        c.y + (sin(a) * lr).toFloat() - tl.size.height / 2f
                    )
                )
            }
        }
    }
}

@Composable
fun RoseLegend(petals: List<Petal>) {
    petals.forEach { p ->
        Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("●", color = p.color)
            Spacer(Modifier.width(6.dp))
            Text(p.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text(p.display, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun RoseBlock(title: String, petals: List<Petal>, labelEvery: Int = 1, legend: Boolean = true) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    if (petals.isEmpty() || petals.all { it.value <= 0.0 }) {
        Text(tr("沒有資料", "No data"), style = MaterialTheme.typography.bodySmall)
        return
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RoseChart(petals, labelEvery = labelEvery) }
    if (legend) RoseLegend(petals)
}
