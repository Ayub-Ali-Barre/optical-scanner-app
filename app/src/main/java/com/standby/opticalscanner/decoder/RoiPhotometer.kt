package com.standby.opticalscanner.decoder

import kotlin.math.max
import kotlin.math.pow

data class LightSample(
    val timeS: Double,
    val brightness: Double
)

class PhotometryClippedException(message: String) : RuntimeException(message)

/**
 * Exact Kotlin port of RoiPhotometer (mode = "linear-unclipped") from led_decoder.py.
 *
 * Calibrates a fixed pixel mask during the first [calibrationS] seconds by omitting
 * any pixel whose R, G, or B channel ever reaches [clipLevel]. Pixel values are
 * approximately linearised with an inverse-sRGB LUT and combined with BT.601 weights
 * (0.299 R + 0.587 G + 0.114 B).
 */
class RoiPhotometer(
    val calibrationS: Double = 0.9,
    val clipLevel: Int = 245
) {
    private val linearLut = DoubleArray(256) { value ->
        val encoded = value / 255.0
        val linear = if (encoded <= 0.04045) {
            encoded / 12.92
        } else {
            ((encoded + 0.055) / 1.055).pow(2.4)
        }
        linear * 255.0
    }

    private var expectedWidth: Int = -1
    private var expectedHeight: Int = -1
    private val bufferedTimes = ArrayList<Double>()
    private val bufferedFrames = ArrayList<ByteArray>()
    private var usableIndices: IntArray? = null

    val isCalibrated: Boolean
        get() = usableIndices != null

    /**
     * Adds an RGB888 crop (3 bytes per pixel: R, G, B) captured at [stampS] seconds.
     */
    fun add(stampS: Double, rgb: ByteArray, width: Int, height: Int): List<LightSample> {
        require(width > 0 && height > 0) { "ROI must have positive dimensions." }
        require(rgb.size == width * height * 3) { "RGB buffer size must match width * height * 3." }

        if (expectedWidth == -1) {
            expectedWidth = width
            expectedHeight = height
        } else if (width != expectedWidth || height != expectedHeight) {
            throw IllegalStateException("ROI size changed during capture.")
        }

        if (usableIndices != null) {
            return listOf(LightSample(stampS, measure(rgb)))
        }

        bufferedTimes.add(stampS)
        bufferedFrames.add(rgb.copyOf())

        if (bufferedTimes.last() - bufferedTimes.first() >= calibrationS - 1e-8) {
            return finish()
        }
        return emptyList()
    }

    fun finish(): List<LightSample> {
        if (bufferedFrames.isEmpty()) return emptyList()

        val pixelCount = expectedWidth * expectedHeight
        val clipped = BooleanArray(pixelCount)

        for (frame in bufferedFrames) {
            var byteIdx = 0
            for (p in 0 until pixelCount) {
                if (!clipped[p]) {
                    val r = frame[byteIdx].toInt() and 0xFF
                    val g = frame[byteIdx + 1].toInt() and 0xFF
                    val b = frame[byteIdx + 2].toInt() and 0xFF
                    if (r >= clipLevel || g >= clipLevel || b >= clipLevel) {
                        clipped[p] = true
                    }
                }
                byteIdx += 3
            }
        }

        var usableCount = 0
        for (p in 0 until pixelCount) {
            if (!clipped[p]) usableCount++
        }

        val minimum = max(16, (pixelCount * 0.02).toInt())
        if (usableCount < minimum) {
            bufferedTimes.clear()
            bufferedFrames.clear()
            throw PhotometryClippedException(
                "For fa omattade bildpunkter i ROI ($usableCount < $minimum)."
            )
        }

        val indices = IntArray(usableCount)
        var out = 0
        for (p in 0 until pixelCount) {
            if (!clipped[p]) {
                indices[out++] = p * 3
            }
        }
        usableIndices = indices

        val measured = ArrayList<LightSample>(bufferedFrames.size)
        for (i in bufferedFrames.indices) {
            measured.add(LightSample(bufferedTimes[i], measure(bufferedFrames[i])))
        }
        bufferedTimes.clear()
        bufferedFrames.clear()
        return measured
    }

    private fun measure(rgb: ByteArray): Double {
        val indices = usableIndices ?: error("Photometer not calibrated.")
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        for (byteIdx in indices) {
            sumR += linearLut[rgb[byteIdx].toInt() and 0xFF]
            sumG += linearLut[rgb[byteIdx + 1].toInt() and 0xFF]
            sumB += linearLut[rgb[byteIdx + 2].toInt() and 0xFF]
        }
        val invCount = 1.0 / indices.size
        val meanR = sumR * invCount
        val meanG = sumG * invCount
        val meanB = sumB * invCount
        return meanR * 0.299 + meanG * 0.587 + meanB * 0.114
    }
}
