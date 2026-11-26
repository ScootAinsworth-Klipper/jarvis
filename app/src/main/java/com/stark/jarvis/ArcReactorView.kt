package com.stark.jarvis

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

private val CyanBright = Color(0xFF00F0FF)
private val CyanCore = Color(0xFF80F8FF)
private val CyanMid = Color(0xFF00B8FF)
private val CyanDeep = Color(0xFF004E7C)

@Composable
fun ArcReactorView(
    mode: ReactorMode,
    audioLevel: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "reactor_loop")

    val slowRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "slow_rot"
    )

    val fastRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "fast_rot"
    )

    val pulsePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val reactiveBoost by animateFloatAsState(
        targetValue = when (mode) {
            ReactorMode.IDLE -> 0.15f
            ReactorMode.LISTENING -> (0.4f + audioLevel * 0.6f).coerceIn(0.4f, 1f)
            ReactorMode.THINKING -> 0.7f
            ReactorMode.SPEAKING -> 0.55f + pulsePhase * 0.4f
        },
        animationSpec = tween(durationMillis = 120),
        label = "reactive_boost"
    )

    val speedFactor = when (mode) {
        ReactorMode.IDLE -> 1f
        ReactorMode.LISTENING -> 1.8f
        ReactorMode.THINKING -> 3.2f
        ReactorMode.SPEAKING -> 2.1f
    }

    Box(
        modifier = modifier
            .size(320.dp)
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxR = size.minDimension / 2f

            // Outer ambient glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        CyanBright.copy(alpha = 0.28f * reactiveBoost + 0.08f),
                        CyanMid.copy(alpha = 0.12f * reactiveBoost),
                        Color.Transparent
                    ),
                    center = center,
                    radius = maxR
                ),
                radius = maxR,
                center = center
            )

            // Expanding sonar ripple when listening or speaking
            if (mode == ReactorMode.LISTENING || mode == ReactorMode.SPEAKING) {
                val rippleRadius = maxR * (0.62f + pulsePhase * 0.34f)
                drawCircle(
                    color = CyanBright.copy(alpha = (1f - pulsePhase) * 0.45f),
                    radius = rippleRadius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            // Outer tick ring
            rotate(degrees = slowRotation * speedFactor * 0.5f, pivot = center) {
                val tickOuter = maxR * 0.90f
                val tickInnerMinor = maxR * 0.85f
                val tickInnerMajor = maxR * 0.81f
                for (i in 0 until 60) {
                    val angleRad = Math.toRadians(i * 6.0)
                    val isMajor = i % 5 == 0
                    val rIn = if (isMajor) tickInnerMajor else tickInnerMinor
                    val start = Offset(
                        center.x + (rIn * cos(angleRad)).toFloat(),
                        center.y + (rIn * sin(angleRad)).toFloat()
                    )
                    val end = Offset(
                        center.x + (tickOuter * cos(angleRad)).toFloat(),
                        center.y + (tickOuter * sin(angleRad)).toFloat()
                    )
                    drawLine(
                        color = if (isMajor) CyanBright.copy(alpha = 0.85f) else CyanMid.copy(alpha = 0.4f),
                        start = start,
                        end = end,
                        strokeWidth = if (isMajor) 2.2.dp.toPx() else 1.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            // Outer segmented HUD arcs
            val arcR1 = maxR * 0.76f
            rotate(degrees = fastRotation * speedFactor, pivot = center) {
                for (k in 0 until 3) {
                    drawArc(
                        color = CyanBright.copy(alpha = 0.85f),
                        startAngle = k * 120f + 10f,
                        sweepAngle = 82f,
                        useCenter = false,
                        topLeft = Offset(center.x - arcR1, center.y - arcR1),
                        size = Size(arcR1 * 2f, arcR1 * 2f),
                        style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // Base structural ring
            drawCircle(
                color = CyanDeep.copy(alpha = 0.8f),
                radius = maxR * 0.67f,
                center = center,
                style = Stroke(width = 6.dp.toPx())
            )

            // 10 radial energy coil segments
            rotate(degrees = slowRotation * speedFactor, pivot = center) {
                val coilR = maxR * 0.60f
                for (i in 0 until 10) {
                    drawArc(
                        color = CyanCore.copy(alpha = 0.65f + 0.35f * reactiveBoost),
                        startAngle = i * 36f + 6f,
                        sweepAngle = 22f,
                        useCenter = false,
                        topLeft = Offset(center.x - coilR, center.y - coilR),
                        size = Size(coilR * 2f, coilR * 2f),
                        style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Butt)
                    )
                }
            }

            // Inner fast scanning ring
            val arcR2 = maxR * 0.45f
            rotate(degrees = fastRotation * speedFactor * 1.4f, pivot = center) {
                val sweep = if (mode == ReactorMode.THINKING) 140f else 65f
                drawArc(
                    color = CyanBright,
                    startAngle = 0f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - arcR2, center.y - arcR2),
                    size = Size(arcR2 * 2f, arcR2 * 2f),
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = CyanBright.copy(alpha = 0.6f),
                    startAngle = 180f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - arcR2, center.y - arcR2),
                    size = Size(arcR2 * 2f, arcR2 * 2f),
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Triangular core frame
            rotate(degrees = slowRotation * 0.4f, pivot = center) {
                drawCoreTriangle(center, maxR * 0.34f, CyanCore.copy(alpha = 0.9f))
            }

            // Glowing plasma center
            val coreRadius = maxR * (0.20f + 0.04f * reactiveBoost)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White,
                        CyanCore,
                        CyanBright.copy(alpha = 0.85f),
                        CyanMid.copy(alpha = 0.1f)
                    ),
                    center = center,
                    radius = coreRadius * 1.6f
                ),
                radius = coreRadius * 1.6f,
                center = center
            )

            drawCircle(
                color = Color.White.copy(alpha = 0.95f),
                radius = coreRadius * 0.55f,
                center = center
            )
        }
    }
}

private fun DrawScope.drawCoreTriangle(center: Offset, radius: Float, color: Color) {
    val path = Path()
    for (i in 0 until 3) {
        val angle = Math.toRadians((i * 120 - 90).toDouble())
        val x = center.x + (radius * cos(angle)).toFloat()
        val y = center.y + (radius * sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
    )
}
