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
import androidx.compose.foundation.layout.width
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
import com.standby.opticalscanner.decoder.OpticalProtocol
import com.standby.opticalscanner.scanner.ScanPhase
import com.standby.opticalscanner.scanner.ScannerUiState
import com.standby.opticalscanner.ui.theme.DarkBackground
import com.standby.opticalscanner.ui.theme.StandbyBlue
import com.standby.opticalscanner.ui.theme.StandbyLightBlue
import com.standby.opticalscanner.ui.theme.White
import java.util.Locale

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

        // Status / address result card above bottom bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 126.dp),
            contentAlignment = Alignment.Center
        ) {
            when (state.phase) {
                ScanPhase.Idle -> {
                    StatusPill(text = "Rikta rutan mot lampan och tryck på skanna")
                }

                ScanPhase.Calibrating -> {
                    ProgressCard(
                        label = "Kalibrerar optik...",
                        progress = state.progress
                    )
                }

                ScanPhase.Scanning -> {
                    ProgressCard(
                        label = "Läser av (${state.protocol.displayName})...",
                        progress = state.progress
                    )
                }

                ScanPhase.Matched -> {
                    state.matchedAddress?.let {
                        AddressResultCard(state = state)
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
private fun AddressResultCard(state: ScannerUiState) {
    val address = state.matchedAddress ?: return
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = DarkBackground.copy(alpha = 0.92f),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "AVKODAD ADRESS",
                color = SuccessGreen,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Text(
                text = "$address",
                color = White,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold
            )

            // Latency & Protocol info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                state.latencySeconds?.let { latency ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1E293B)
                    ) {
                        Text(
                            text = String.format(Locale.US, "⏱ %.2f s svarstid", latency),
                            color = StandbyLightBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = StandbyBlue.copy(alpha = 0.35f)
                ) {
                    Text(
                        text = state.protocol.displayName,
                        color = White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Quality metrics row
            if (state.glrOrRmse != null) {
                Spacer(modifier = Modifier.height(6.dp))
                val isStandby = state.protocol == OpticalProtocol.STANDBY_14
                val metric1 = if (isStandby) {
                    String.format(Locale.US, "GLR: %.2f", state.glrOrRmse)
                } else {
                    String.format(Locale.US, "RMSE: %.3f", state.glrOrRmse)
                }
                val metric2 = if (state.stabilityOrMargin != null) {
                    if (isStandby) {
                        String.format(Locale.US, "Stabilitet: %.0f%%", (state.stabilityOrMargin) * 100.0)
                    } else {
                        String.format(Locale.US, "Marginal: %.2f", state.stabilityOrMargin)
                    }
                } else null

                val metricText = if (metric2 != null) "$metric1  •  $metric2" else metric1
                Text(
                    text = metricText,
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }
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
                fontSize = 14.sp,
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
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
        )
    }
}

@Composable
fun ProtocolAndSpeedDialog(
    currentProtocol: OpticalProtocol,
    currentSymbolMs: Int,
    onSelectProtocol: (OpticalProtocol) -> Unit,
    onSelectSymbolMs: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        titleContentColor = White,
        textContentColor = White,
        title = {
            Text(
                text = "Protokoll & Inställningar",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "VÄLJ PROTOKOLL",
                    color = StandbyLightBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                OpticalProtocol.values().forEach { proto ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (proto == currentProtocol) StandbyBlue.copy(alpha = 0.25f) else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                onSelectProtocol(proto)
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = proto == currentProtocol,
                            onClick = { onSelectProtocol(proto) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = StandbyLightBlue,
                                unselectedColor = Color(0xFF94A3B8)
                            )
                        )
                        Column {
                            Text(
                                text = proto.displayName,
                                color = White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = proto.description,
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "SYMBOLTID / SLOTTID",
                    color = StandbyLightBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                currentProtocol.supportedSymbolMs.forEach { ms ->
                    val label = if (ms == currentProtocol.defaultSymbolMs) {
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
                                onSelectSymbolMs(ms)
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = ms == currentSymbolMs,
                            onClick = { onSelectSymbolMs(ms) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = StandbyLightBlue,
                                unselectedColor = Color(0xFF94A3B8)
                            )
                        )
                        Text(
                            text = label,
                            color = White,
                            fontSize = 14.sp
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

@Composable
fun SymbolSpeedDialog(
    currentSymbolMs: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ProtocolAndSpeedDialog(
        currentProtocol = OpticalProtocol.STANDBY_14,
        currentSymbolMs = currentSymbolMs,
        onSelectProtocol = {},
        onSelectSymbolMs = onSelect,
        onDismiss = onDismiss
    )
}
