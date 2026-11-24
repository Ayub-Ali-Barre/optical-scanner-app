package com.standby.opticalscanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.standby.opticalscanner.scanner.OpticalScanController
import com.standby.opticalscanner.ui.CameraPreview
import com.standby.opticalscanner.ui.ProtocolAndSpeedDialog
import com.standby.opticalscanner.ui.ScannerOverlay
import com.standby.opticalscanner.ui.StandbyBottomBar
import com.standby.opticalscanner.ui.theme.OpticalScannerTheme
import com.standby.opticalscanner.ui.theme.StandbyBlue
import com.standby.opticalscanner.ui.theme.StandbyLightBlue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpticalScannerTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scanController = remember(scope) { OpticalScanController(scope) }
    val scanState by scanController.uiState.collectAsState()
    var showSettingsDialog by remember { mutableStateOf(false) }

    DisposableEffect(scanController) {
        onDispose {
            scanController.release()
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            // 1. Live Camera Feed + ROI Frame Analysis
            CameraPreview(
                isScanning = scanState.isScanning,
                onRoiFrame = { timestampNs, rgb, width, height ->
                    scanController.onRoiFrame(timestampNs, rgb, width, height)
                },
                modifier = Modifier.fillMaxSize()
            )

            // 2. Centered ROI Reticle + Minimal Address/Status Overlay
            ScannerOverlay(
                state = scanState,
                modifier = Modifier.fillMaxSize()
            )

            // 3. Top Header: Protocol Chip (Left) + Standby Logo (Right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Interactive Protocol & Speed Pill
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF0F172A).copy(alpha = 0.82f),
                    modifier = Modifier.clickable { showSettingsDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(StandbyLightBlue, RoundedCornerShape(4.dp))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${scanState.protocol.displayName} (${scanState.symbolMs} ms)",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Brand Header
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "STANDBY",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "When attention matters",
                        color = Color(0xFFB0C4DE),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }

            // 4. Bottom Navigation Bar
            StandbyBottomBar(
                isScanning = scanState.isScanning,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
                onMenuClick = { showSettingsDialog = true },
                onScanClick = { scanController.toggleScan() },
                onRefreshClick = { scanController.reset() }
            )

            if (showSettingsDialog) {
                ProtocolAndSpeedDialog(
                    currentProtocol = scanState.protocol,
                    currentSymbolMs = scanState.symbolMs,
                    onSelectProtocol = { proto -> scanController.setProtocol(proto) },
                    onSelectSymbolMs = { ms -> scanController.setSymbolMs(ms) },
                    onDismiss = { showSettingsDialog = false }
                )
            }
        } else {
            // Permission Request Screen
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Kameratillstånd krävs",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Appen behöver använda kameran för att skanna och avkoda optiska ljussignaler från fordonet.",
                    color = Color(0xFF94A3B8),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { launcher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = StandbyBlue)
                ) {
                    Text("Ge kameratillstånd")
                }
            }
        }
    }
}
