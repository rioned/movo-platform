package com.movo.customer.parcel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Static, dependency-free illustration. Decorative only; it conveys no delivery data. */
@Composable
fun ParcelArtwork(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = minOf(size.width, size.height * 1.45f)
        val h = size.height
        val leftInset = (size.width - w) / 2f
        fun point(x: Float, y: Float) = Offset(leftInset + w * x, h * y)
        fun face(vararg points: Pair<Float, Float>): Path = Path().apply {
            points.forEachIndexed { i, p -> if (i == 0) moveTo(leftInset + w * p.first, h * p.second) else lineTo(leftInset + w * p.first, h * p.second) }
            close()
        }
        drawOval(Brush.radialGradient(listOf(Color(0xFF39D99B).copy(alpha = .24f), Color.Transparent), point(.53f, .52f), w * .52f), topLeft = point(.04f, .04f), size = Size(w * .92f, h * .9f))
        drawOval(Color(0xFF001F16).copy(alpha = .65f), point(.22f, .80f), Size(w * .62f, h * .12f))
        drawOval(Color(0xFF72E9B2).copy(alpha = .22f), point(.04f, .66f), Size(w * .92f, h * .30f), style = Stroke(1.dp.toPx()))
        drawOval(Color(0xFF72E9B2).copy(alpha = .10f), point(.12f, .70f), Size(w * .76f, h * .21f), style = Stroke(1.dp.toPx()))
        val top = face(.20f to .33f, .52f to .12f, .84f to .32f, .51f to .54f)
        val left = face(.20f to .33f, .51f to .54f, .51f to .86f, .20f to .64f)
        val right = face(.51f to .54f, .84f to .32f, .84f to .66f, .51f to .86f)
        drawPath(left, Brush.linearGradient(listOf(Color(0xFF16875E), Color(0xFF084333)), point(.20f, .33f), point(.51f, .86f)))
        drawPath(right, Brush.linearGradient(listOf(Color(0xFF0D654C), Color(0xFF032C24)), point(.84f, .32f), point(.51f, .86f)))
        drawPath(top, Brush.linearGradient(listOf(Color(0xFF9AF0BC), Color(0xFF2CAB7C)), point(.30f, .12f), point(.73f, .54f)))
        drawPath(face(.34f to .24f, .40f to .20f, .72f to .40f, .66f to .44f), Color(0xFFDAF8C5))
        drawPath(face(.66f to .44f, .72f to .40f, .72f to .53f, .66f to .57f), Color(0xFF82CAA4))
        drawPath(top, Color(0xFFC5FFDE).copy(alpha = .55f), style = Stroke(1.dp.toPx()))
        drawLine(Color(0xFF79DEAC).copy(alpha = .4f), point(.51f, .54f), point(.51f, .86f), 1.dp.toPx())
        // Shipping label, perspective aligned to the front face.
        drawPath(face(.27f to .45f, .43f to .56f, .43f to .67f, .27f to .56f), Color(0xFFE3F5E9))
        for (i in 0..6) {
            val x = .29f + i * .018f
            val y = .48f + i * .012f
            drawLine(Color(0xFF164C3B), point(x, y), point(x, y + .046f), if (i % 2 == 0) 2.dp.toPx() else 1.dp.toPx())
        }
        drawCircle(Color(0xFFBCECD0), 3.dp.toPx(), point(.12f, .38f))
        drawCircle(Color(0xFF67D99C), 2.dp.toPx(), point(.87f, .18f))
        drawLine(Color(0xFF67D99C).copy(alpha = .45f), point(.84f, .78f), point(.94f, .72f), 1.dp.toPx())
    }
}

/** A carbon-toned stage stays legible in both system appearances. */
@Composable
fun ParcelHero(title: String, subtitle: String, eyebrow: String = "MOVO • PARCEL DELIVERY", compact: Boolean = false) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF09251F), Color(0xFF114D3B), Color(0xFF0B201B))))
            .border(1.dp, Color(0xFF6BCBA2).copy(alpha = .25f), shape)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(eyebrow, color = Color(0xFFB2E8CC), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.6.sp)
        ParcelArtwork(Modifier.fillMaxWidth().height(if (compact) 116.dp else 170.dp))
        Text(title, color = Color(0xFFF5FFF9), style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge)
        Text(subtitle, color = Color(0xFFC0D9CD), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun JourneyStep(number: String, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(number, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)).padding(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}
