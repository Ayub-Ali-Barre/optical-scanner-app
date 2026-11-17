package com.standby.opticalscanner.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.standby.opticalscanner.scanner.ScanPhase
import com.standby.opticalscanner.scanner.ScannerUiState
import com.standby.opticalscanner.ui.theme.DarkBackground
import com.standby.opticalscanner.ui.theme.StandbyBlue
import com.standby.opticalscanner.ui.theme.StandbyLightBlue
import com.standby.opticalscanner.ui.theme.White

val RETICLE_SIZE: Dp = 96.dp
private val SuccessGreen = Color(0xFF22C55E)
private val WarningAmber = Color(0xFFF59E0B)

@Composable
fun ScannerOverlay(
    state: ScannerUiState,
    modifier: Modifier = Modifier
) {
    val pulseTransition = rememberInfiniteTransition(label = "reticlePulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "reticleAlpha"
    )

    val reticleColor = when (state.phase) {
        ScanPhase.Idle -> White.copy(alpha = 0.9f)
        ScanPhase.Calibrating, ScanPhase.Scanning -> StandbyLightBlue.copy(alpha = pulseAlpha)
        ScanPhase.Matched -> SuccessGreen
        ScanPhase.Failed -> WarningAmber
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Fixed centered ROI reticle
        CenterReticle(
            color = reticleColor,
            modifier = Modifier
                .align(Alignment.Center)
                .size(RETICLE_SIZE)
        )

        // Minimal status / address result card above bottom bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 132.dp),
            contentAlignment = Alignment.Center
        ) {
            when (state.phase) {
                ScanPhase.Idle -> {
                    StatusPill(text = "Rikta rutan mot lampan och tryck på skanna")
                }

                ScanPhase.Calibrating -> {
                    ProgressCard(
                        label = "Kalibrerar...",
                        progress = state.progress
                    )
                }

                ScanPhase.Scanning -> {
                    ProgressCard(
                        label = "Läser av...",
                        progress = state.progress
                    )
                }

                ScanPhase.Matched -> {
                    state.matchedAddress?.let { address ->
                        AddressResultCard(address = address)
                    }
                }

                ScanPhase.Failed -> {
                    StatusPill(
                        text = state.statusHint ?: "Ingen adress hittades",
                        accentColor = WarningAmber
                    )
                }
            }
        }
    }
}

@Composable
private fun CenterReticle(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val s = size.width
        val strokeW = 3.5.dp.toPx()
        val corner = s * 0.24f

        // Subtle inner box border so user sees exact ROI bounds
        drawRect(
            color = color.copy(alpha = 0.16f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
        )

        // Top-Left
        drawLine(color, Offset(0f, corner), Offset(0f, 0f), strokeW, StrokeCap.Round)
        drawLine(color, Offset(0f, 0f), Offset(corner, 0f), strokeW, StrokeCap.Round)

        // Top-Right
        drawLine(color, Offset(s - corner, 0f), Offset(s, 0f), strokeW, StrokeCap.Round)
        drawLine(color, Offset(s, 0f), Offset(s, corner), strokeW, StrokeCap.Round)

        // Bottom-Left
        drawLine(color, Offset(0f, s - corner), Offset(0f, s), strokeW, StrokeCap.Round)
        drawLine(color, Offset(0f, s), Offset(corner, s), strokeW, StrokeCap.Round)

        // Bottom-Right
        drawLine(color, Offset(s - corner, s), Offset(s, s), strokeW, StrokeCap.Round)
        drawLine(color, Offset(s, s), Offset(s, s - corner), strokeW, StrokeCap.Round)
    }
}

@Composable
private fun AddressResultCard(address: Int) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = DarkBackground.copy(alpha = 0.88f),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 36.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "ADRESS",
                color = SuccessGreen,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$address",
                color = White,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ProgressCard(
    label: String,
    progress: Float
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = DarkBackground.copy(alpha = 0.84f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                color = White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp),
                color = StandbyLightBlue,
                trackColor = White.copy(alpha = 0.2f)
            )
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    accentColor: Color = White
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = DarkBackground.copy(alpha = 0.82f)
    ) {
        Text(
            text = text,
            color = accentColor,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
        )
    }
}

@Composable
fun SymbolSpeedDialog(
    currentSymbolMs: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        titleContentColor = White,
        textContentColor = White,
        title = {
            Text(
                text = "Symboltid",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Välj symbolhastighet som matchar lampans sändning.",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                ScannerUiState.SUPPORTED_SYMBOL_MS.forEach { ms ->
                    val label = if (ms == ScannerUiState.DEFAULT_SYMBOL_MS) {
                        "$ms ms (standard)"
                    } else {
                        "$ms ms"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (ms == currentSymbolMs) StandbyBlue.copy(alpha = 0.22f) else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                onSelect(ms)
                                onDismiss()
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = ms == currentSymbolMs,
                            onClick = {
                                onSelect(ms)
                                onDismiss()
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = StandbyLightBlue,
                                unselectedColor = Color(0xFF94A3B8)
                            )
                        )
                        Text(
                            text = label,
                            color = White,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Stäng", color = StandbyLightBlue)
            }
        }
    )
}
