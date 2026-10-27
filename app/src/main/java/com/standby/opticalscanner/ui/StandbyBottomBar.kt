package com.standby.opticalscanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.standby.opticalscanner.ui.theme.StandbyBlue
import com.standby.opticalscanner.ui.theme.StandbyLightBlue
import com.standby.opticalscanner.ui.theme.StandbyRing
import com.standby.opticalscanner.ui.theme.White

@Composable
fun StandbyBottomBar(
    onMenuClick: () -> Unit = {},
    onScanClick: () -> Unit = {},
    onRefreshClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(115.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        // 1. Curved Blue Background Bar
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
        ) {
            val w = size.width
            val h = size.height
            val cradleRadius = 52.dp.toPx()
            val centerX = w / 2f

            val path = Path().apply {
                moveTo(0f, 25f)
                // Left curve approaching center cradle
                lineTo(centerX - cradleRadius - 20f, 25f)
                cubicTo(
                    centerX - cradleRadius + 10f, 25f,
                    centerX - cradleRadius + 10f, 85f,
                    centerX, 85f
                )
                // Right curve leaving center cradle
                cubicTo(
                    centerX + cradleRadius - 10f, 85f,
                    centerX + cradleRadius - 10f, 25f,
                    centerX + cradleRadius + 20f, 25f
                )
                lineTo(w, 25f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }

            drawPath(path = path, color = StandbyBlue)
        }

        // 2. Buttons Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Button: Grid/Keypad
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Dialpad,
                    contentDescription = "Meny / Knappsats",
                    tint = White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Right Button: Refresh / Återställ
            IconButton(
                onClick = onRefreshClick,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Uppdatera / Återställ",
                    tint = White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        // 3. Center Elevated Scanner Action Button (FAB)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 2.dp)
                .size(78.dp)
                .shadow(elevation = 10.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(StandbyLightBlue)
                .border(width = 3.dp, color = StandbyRing, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = onScanClick,
                modifier = Modifier.fillMaxSize()
            ) {
                // Viewfinder / Reticle Icon with center horizontal dash: [ - ]
                Canvas(modifier = Modifier.size(36.dp)) {
                    val s = size.width
                    val strokeW = 3.5.dp.toPx()
                    val cornerL = s * 0.28f

                    // Top-Left corner
                    drawLine(
                        color = White,
                        start = Offset(0f, cornerL),
                        end = Offset(0f, 0f),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = White,
                        start = Offset(0f, 0f),
                        end = Offset(cornerL, 0f),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )

                    // Top-Right corner
                    drawLine(
                        color = White,
                        start = Offset(s - cornerL, 0f),
                        end = Offset(s, 0f),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = White,
                        start = Offset(s, 0f),
                        end = Offset(s, cornerL),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )

                    // Bottom-Left corner
                    drawLine(
                        color = White,
                        start = Offset(0f, s - cornerL),
                        end = Offset(0f, s),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = White,
                        start = Offset(0f, s),
                        end = Offset(cornerL, s),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )

                    // Bottom-Right corner
                    drawLine(
                        color = White,
                        start = Offset(s - cornerL, s),
                        end = Offset(s, s),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = White,
                        start = Offset(s, s),
                        end = Offset(s, s - cornerL),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )

                    // Center Horizontal Bar: -
                    val barWidth = s * 0.44f
                    val barStartX = (s - barWidth) / 2f
                    drawLine(
                        color = White,
                        start = Offset(barStartX, s / 2f),
                        end = Offset(barStartX + barWidth, s / 2f),
                        strokeWidth = strokeW * 1.1f,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}
