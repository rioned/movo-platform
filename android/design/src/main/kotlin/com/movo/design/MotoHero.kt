package com.movo.design

import android.animation.ValueAnimator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Offline, vector-drawn motorcycle with layered bodywork and real perspective
 * transforms. Motion is finite and user-triggered, never a battery-draining loop.
 * Only use on welcome/idle surfaces, not while a rider is operating the motorcycle.
 */
@Composable
fun MotoHero(title: String, subtitle: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    var turned by rememberSaveable { mutableStateOf(false) }
    val motionEnabled = ValueAnimator.areAnimatorsEnabled()
    val perspective by animateFloatAsState(
        targetValue = if (turned && motionEnabled) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 110f),
        label = "Motorcycle perspective"
    )
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF102C24), Color(0xFF081914))))
            .padding(if (compact) 16.dp else 22.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("MOVO / MOTO", color = Color(0xFFD5F878), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text("RWANDA", color = Color(0xFFB5C9C0), style = MaterialTheme.typography.labelSmall)
        }
        if (!compact) {
            Box(Modifier.fillMaxWidth().height(164.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    // A quiet route halo and a grounding shadow, not a fake live map.
                    drawOval(Color(0xFF26463A), Offset(size.width * .1f, size.height * .28f), Size(size.width * .8f, size.height * .66f), style = Stroke(1.dp.toPx()))
                    drawOval(Color.Black.copy(alpha = .32f), Offset(size.width * .19f, size.height * .81f), Size(size.width * .64f, size.height * .13f))
                }
                MotorcycleIllustration(
                    Modifier.fillMaxWidth().height(158.dp)
                        .graphicsLayer {
                            rotationY = -12f + perspective * 24f
                            rotationX = 8f - perspective * 5f
                            rotationZ = -2f + perspective * 4f
                            cameraDistance = 12f * density
                            translationY = -perspective * 5f * density
                        }
                        .clickable(enabled = motionEnabled, role = Role.Button, onClickLabel = "Rotate motorcycle") { turned = !turned }
                        .semantics { contentDescription = "MOVO motorcycle illustration" }
                )
            }
        }
        Spacer(Modifier.height(if (compact) 10.dp else 4.dp))
        Text(title, color = Color.White, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = Color(0xFFC1D2CB), style = MaterialTheme.typography.bodyMedium)
        if (!compact) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (motionEnabled) "Tap the moto to explore ↔" else "Animations disabled · Motorcycle preview",
                color = Color(0xFFD5F878), style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun MotorcycleIllustration(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val factor = minOf(size.width / 320f, size.height / 180f)
        scale(factor, factor, pivot = Offset.Zero) {
            val shift = (size.width / factor - 320f) / 2f
            fun point(x: Float, y: Float) = Offset(x + shift, y)
            fun line(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, width: Float) =
                drawLine(color, point(x1, y1), point(x2, y2), width, StrokeCap.Round)
            val rubber = Color(0xFF0B100F)
            val alloy = Color(0xFFA2B9AD)
            val lime = Color(0xFFD5F878)
            // Wheel sidewalls, metal rims and hubs create depth without bitmap assets.
            for (x in listOf(78f, 249f)) {
                drawCircle(Color(0xFF26382F), 35f, point(x + 4f, 133f))
                drawCircle(rubber, 34f, point(x, 131f))
                drawCircle(alloy, 25f, point(x, 131f), style = Stroke(3f))
                drawCircle(Color(0xFF32493D), 20f, point(x, 131f))
                line(alloy, x - 20, 131f, x + 20, 131f, 2f)
                line(alloy, x, 111f, x, 151f, 2f)
                line(alloy, x - 14, 117f, x + 14, 145f, 2f)
                line(alloy, x - 14, 145f, x + 14, 117f, 2f)
                drawCircle(Color(0xFFDCE7E1), 6f, point(x, 131f))
            }
            line(Color(0xFF63796B), 78f, 131f, 147f, 125f, 10f)
            line(lime, 78f, 131f, 115f, 82f, 6f)
            line(lime, 115f, 82f, 167f, 125f, 6f)
            line(lime, 167f, 125f, 205f, 72f, 7f)
            line(alloy, 219f, 62f, 249f, 131f, 9f)
            line(Color(0xFF536B5C), 225f, 62f, 255f, 131f, 4f)
            drawRoundRect(Color(0xFF718779), point(132f, 94f), Size(42f, 31f), androidx.compose.ui.geometry.CornerRadius(7f))
            for (y in listOf(99f, 106f, 113f)) line(Color(0xFF263C30), 134f, y, 169f, y, 3f)
            // Faceted fuel tank with a highlight bevel.
            val tank = Path().apply {
                moveTo(142f + shift, 70f); lineTo(166f + shift, 49f)
                lineTo(196f + shift, 51f); lineTo(211f + shift, 73f)
                lineTo(186f + shift, 91f); lineTo(153f + shift, 88f); close()
            }
            drawPath(tank, Brush.linearGradient(listOf(Color(0xFFF0FFB8), lime, Color(0xFF789A32)), point(155f, 49f), point(193f, 96f)))
            line(Color(0xFFFAFFDE), 167f, 53f, 193f, 55f, 3f)
            drawRoundRect(Color(0xFF15241D), point(97f, 64f), Size(63f, 15f), androidx.compose.ui.geometry.CornerRadius(6f))
            line(Color(0xFF5B7063), 100f, 65f, 148f, 65f, 3f)
            line(lime, 99f, 81f, 142f, 89f, 9f)
            line(alloy, 222f, 67f, 212f, 37f, 5f)
            line(alloy, 212f, 37f, 192f, 35f, 5f)
            line(Color(0xFFCBDDD1), 218f, 39f, 224f, 20f, 3f)
            drawOval(Color(0xFFB6CDC0), point(221f, 15f), Size(19f, 9f))
            drawRoundRect(Color(0xFF566E5B), point(222f, 54f), Size(16f, 18f), androidx.compose.ui.geometry.CornerRadius(4f))
            line(Color(0xFFFFEDB8), 236f, 56f, 239f, 69f, 5f)
            line(Color(0xFFCAD7CF), 127f, 135f, 189f, 135f, 9f)
            line(Color(0xFF53695D), 132f, 137f, 188f, 137f, 3f)
            line(Color(0xFFFF8D74), 87f, 75f, 96f, 76f, 5f)
        }
    }
}
