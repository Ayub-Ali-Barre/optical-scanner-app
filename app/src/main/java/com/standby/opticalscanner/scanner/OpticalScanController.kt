package com.standby.opticalscanner.scanner

import com.standby.opticalscanner.decoder.DecodeResult
import com.standby.opticalscanner.decoder.PatternDecoder
import com.standby.opticalscanner.decoder.PhotometryClippedException
import com.standby.opticalscanner.decoder.RoiPhotometer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ScanPhase {
    Idle,
    Calibrating,
    Scanning,
    Matched,
    Failed
}

data class ScannerUiState(
    val phase: ScanPhase = ScanPhase.Idle,
    val matchedAddress: Int? = null,
    val statusHint: String? = null,
    val progress: Float = 0f,
    val symbolMs: Int = DEFAULT_SYMBOL_MS
) {
    val isScanning: Boolean
        get() = phase == ScanPhase.Calibrating || phase == ScanPhase.Scanning

    companion object {
        const val DEFAULT_SYMBOL_MS = 50
        val SUPPORTED_SYMBOL_MS = listOf(50, 100, 200)
    }
}

/**
 * Orchestrates live ROI light capture (fast capture thread) and sliding-window
 * optical pattern decoding (background coroutine every 100 ms, 10 s timeout).
 */
class OpticalScanController(
    private val scope: CoroutineScope
) {
    companion object {
        private const val MAX_SCAN_SECONDS = 10.0
        private const val SCAN_STEP_S = 0.100
        private const val POLL_INTERVAL_MS = 50L
    }

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val lock = Any()
    private var active = false
    private var sessionId = 0L
    private var decoder = PatternDecoder(symbolS = ScannerUiState.DEFAULT_SYMBOL_MS / 1000.0)
    private var photometer = RoiPhotometer(calibrationS = decoder.windowS)

    private var firstFrameNs: Long? = null
    private var lastFrameS: Double = -1.0
    private var scanStartWallNs: Long = 0L
    private val times = ArrayList<Double>(640)
    private val brightnesses = ArrayList<Double>(640)
    private var lastDecodedEndS: Double = -1.0
    private val rejectionCounts = HashMap<String, Int>()
    private var receivedAnyFrame = false

    private var decodeJob: Job? = null

    fun setSymbolMs(symbolMs: Int) {
        if (symbolMs == _uiState.value.symbolMs) return
        stopInternal()
        _uiState.value = ScannerUiState(
            phase = ScanPhase.Idle,
            symbolMs = symbolMs
        )
    }

    fun toggleScan() {
        if (_uiState.value.isScanning) {
            stopScan()
        } else {
            startScan()
        }
    }

    fun startScan() {
        decodeJob?.cancel()
        val symbolMs = _uiState.value.symbolMs
        val newDecoder = PatternDecoder(symbolS = symbolMs / 1000.0)
        val newPhotometer = RoiPhotometer(calibrationS = newDecoder.windowS)
        val currentSession: Long

        synchronized(lock) {
            sessionId++
            currentSession = sessionId
            decoder = newDecoder
            photometer = newPhotometer
            firstFrameNs = null
            lastFrameS = -1.0
            scanStartWallNs = System.nanoTime()
            times.clear()
            brightnesses.clear()
            lastDecodedEndS = -1.0
            rejectionCounts.clear()
            receivedAnyFrame = false
            active = true
        }

        _uiState.value = ScannerUiState(
            phase = ScanPhase.Calibrating,
            symbolMs = symbolMs,
            progress = 0f
        )

        decodeJob = scope.launch(Dispatchers.Default) {
            runDecodeLoop(currentSession, newDecoder, symbolMs)
        }
    }

    fun stopScan() {
        stopInternal()
        _uiState.value = ScannerUiState(
            phase = ScanPhase.Idle,
            symbolMs = _uiState.value.symbolMs
        )
    }

    fun reset() {
        stopInternal()
        _uiState.value = ScannerUiState(
            phase = ScanPhase.Idle,
            symbolMs = _uiState.value.symbolMs
        )
    }

    /**
     * Called from the camera analysis thread for every frame while scanning.
     * Kept lightweight so the camera never drops frames during decoding.
     */
    fun onRoiFrame(timestampNs: Long, rgb: ByteArray, width: Int, height: Int) {
        val clippedError: String? = synchronized(lock) {
            if (!active) return
            receivedAnyFrame = true

            val originNs = firstFrameNs ?: timestampNs.also { firstFrameNs = it }
            val stampS = (timestampNs - originNs) / 1_000_000_000.0
            if (stampS <= lastFrameS) {
                return
            }
            lastFrameS = stampS

            try {
                val samples = photometer.add(stampS, rgb, width, height)
                for (sample in samples) {
                    times.add(sample.timeS)
                    brightnesses.add(sample.brightness)
                }
                null
            } catch (_: PhotometryClippedException) {
                active = false
                "För ljust i rutan – håll lampan något längre bort eller ta med kanten runt lampan."
            } catch (_: Exception) {
                null
            }
        }

        if (clippedError != null) {
            decodeJob?.cancel()
            _uiState.value = ScannerUiState(
                phase = ScanPhase.Failed,
                statusHint = clippedError,
                symbolMs = _uiState.value.symbolMs
            )
        }
    }

    fun release() {
        stopInternal()
    }

    private fun stopInternal() {
        synchronized(lock) {
            active = false
            sessionId++
        }
        decodeJob?.cancel()
        decodeJob = null
    }

    private suspend fun runDecodeLoop(
        expectedSession: Long,
        activeDecoder: PatternDecoder,
        symbolMs: Int
    ) {
        while (scope.isActive) {
            delay(POLL_INTERVAL_MS)

            var windowTimes: DoubleArray? = null
            var windowLights: DoubleArray? = null
            var latestTimeS = 0.0
            val wallElapsedS: Double
            val calibrated: Boolean
            val anyFrame: Boolean

            synchronized(lock) {
                if (!active || sessionId != expectedSession) return
                wallElapsedS = (System.nanoTime() - scanStartWallNs) / 1_000_000_000.0
                calibrated = photometer.isCalibrated
                anyFrame = receivedAnyFrame

                if (times.isNotEmpty()) {
                    val firstT = times.first()
                    val lastT = times.last()
                    latestTimeS = lastT
                    if (lastT - firstT >= activeDecoder.windowS - 1e-8 &&
                        lastT - lastDecodedEndS >= SCAN_STEP_S
                    ) {
                        val cutoff = lastT - activeDecoder.windowS - 1e-8
                        val pair = sliceFrom(cutoff)
                        if (pair.first.size >= 2) {
                            windowTimes = pair.first
                            windowLights = pair.second
                            lastDecodedEndS = lastT
                        }
                    }
                }
            }

            // Update progress UI cleanly
            val progress = (wallElapsedS / MAX_SCAN_SECONDS).toFloat().coerceIn(0f, 1f)
            val currentPhase = if (calibrated) ScanPhase.Scanning else ScanPhase.Calibrating
            if (_uiState.value.phase != currentPhase || kotlin.math.abs(_uiState.value.progress - progress) >= 0.01f) {
                _uiState.value = ScannerUiState(
                    phase = currentPhase,
                    progress = progress,
                    symbolMs = symbolMs
                )
            }

            // Run decoder outside lock so capture thread never blocks
            val ts = windowTimes
            val lights = windowLights
            if (ts != null && lights != null) {
                val result = activeDecoder.decodeWindow(ts, lights)
                if (handleDecodeResult(expectedSession, result, symbolMs)) {
                    return
                }
            }

            // Timeout check (10 s)
            if (wallElapsedS >= MAX_SCAN_SECONDS || latestTimeS >= MAX_SCAN_SECONDS) {
                val finalHint = finishOnTimeout(expectedSession, activeDecoder, symbolMs, anyFrame)
                if (finalHint != null) {
                    _uiState.value = ScannerUiState(
                        phase = ScanPhase.Failed,
                        statusHint = finalHint,
                        symbolMs = symbolMs
                    )
                }
                return
            }
        }
    }

    private fun handleDecodeResult(
        expectedSession: Long,
        result: DecodeResult,
        symbolMs: Int
    ): Boolean {
        synchronized(lock) {
            if (!active || sessionId != expectedSession) return true
            if (result.accepted && result.address != null) {
                active = false
                _uiState.value = ScannerUiState(
                    phase = ScanPhase.Matched,
                    matchedAddress = result.address,
                    progress = 1f,
                    symbolMs = symbolMs
                )
                return true
            }
            rejectionCounts[result.reason] = (rejectionCounts[result.reason] ?: 0) + 1
            return false
        }
    }

    private fun finishOnTimeout(
        expectedSession: Long,
        activeDecoder: PatternDecoder,
        symbolMs: Int,
        anyFrame: Boolean
    ): String? {
        var finalSlice: Pair<DoubleArray, DoubleArray>? = null
        var clippedDuringFinish = false

        synchronized(lock) {
            if (!active || sessionId != expectedSession) return null
            if (!photometer.isCalibrated) {
                try {
                    val flushed = photometer.finish()
                    for (sample in flushed) {
                        times.add(sample.timeS)
                        brightnesses.add(sample.brightness)
                    }
                } catch (_: PhotometryClippedException) {
                    clippedDuringFinish = true
                } catch (_: Exception) {
                    // Ignore incomplete buffer on timeout
                }
            }
            if (!clippedDuringFinish && times.size >= 2) {
                val cutoff = times.last() - activeDecoder.windowS - 1e-8
                finalSlice = sliceFrom(cutoff)
            }
            active = false
        }

        if (clippedDuringFinish) {
            return "För ljust i rutan – håll lampan något längre bort eller ta med kanten runt lampan."
        }

        val slice = finalSlice
        if (slice != null && slice.first.size >= 2) {
            val finalResult = activeDecoder.decodeWindow(slice.first, slice.second)
            if (finalResult.accepted && finalResult.address != null) {
                _uiState.value = ScannerUiState(
                    phase = ScanPhase.Matched,
                    matchedAddress = finalResult.address,
                    progress = 1f,
                    symbolMs = symbolMs
                )
                return null
            }
            synchronized(lock) {
                rejectionCounts[finalResult.reason] = (rejectionCounts[finalResult.reason] ?: 0) + 1
            }
        }

        if (!anyFrame) {
            return "Ingen kamerabild mottogs – försök igen."
        }

        val dominantReason = synchronized(lock) {
            rejectionCounts.maxByOrNull { it.value }?.key
        }
        return mapReasonToHint(dominantReason, symbolMs)
    }

    private fun sliceFrom(cutoff: Double): Pair<DoubleArray, DoubleArray> {
        var startIdx = 0
        while (startIdx < times.size && times[startIdx] < cutoff) {
            startIdx++
        }
        val count = times.size - startIdx
        val outT = DoubleArray(count)
        val outL = DoubleArray(count)
        for (i in 0 until count) {
            outT[i] = times[startIdx + i]
            outL[i] = brightnesses[startIdx + i]
        }
        return outT to outL
    }

    private fun mapReasonToHint(reason: String?, symbolMs: Int): String {
        return when (reason) {
            "low_contrast" ->
                "För svag ljussignal – håll rutan stadigt rakt över lampan."
            "missing_frames_or_too_slow_camera", "too_few_samples" ->
                "Kameran är för långsam för $symbolMs ms – prova en längre symboltid i menyn."
            "ambiguous_address" ->
                "Tvetydig signal – håll telefonen stadigt mot en ensam lampa."
            else ->
                "Ingen adress hittades – rikta rutan mot en blinkande lampa och försök igen."
        }
    }
}
