package com.standby.opticalscanner.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow

class PatternDecoderTest {

    private fun generateSignal(
        address: Int,
        symbolS: Double,
        fps: Double,
        durationS: Double,
        phaseS: Double = 0.0,
        exposureS: Double = 0.0,
        lowLevel: Double = 25.0,
        highLevel: Double = 190.0
    ): Pair<DoubleArray, DoubleArray> {
        val bits = OpticalCodebook.PATTERNS.getValue(address)
        val word = DoubleArray(16) { k -> if (bits[k] == '1') 1.0 else 0.0 }
        val prefix = DoubleArray(17)
        for (k in 0 until 16) prefix[k + 1] = prefix[k] + word[k]

        fun integral(z: Double): Double {
            val cycles = floor(z / 16.0)
            val rem = z - cycles * 16.0
            val idx = min(floor(rem).toInt(), 15).coerceAtLeast(0)
            return cycles * 8.0 + prefix[idx] + (rem - idx) * word[idx]
        }

        val dt = 1.0 / fps
        val count = floor(durationS * fps).toInt() + 1
        val times = DoubleArray(count) { i -> i * dt }
        val lights = DoubleArray(count) { i ->
            val u = (times[i] + phaseS) / symbolS
            val level = if (exposureS <= 1e-9) {
                val idx = ((floor(u).toInt() % 16) + 16) % 16
                word[idx]
            } else {
                val half = exposureS / (2.0 * symbolS)
                (integral(u + half) - integral(u - half)) / (2.0 * half)
            }
            lowLevel + level * (highLevel - lowLevel)
        }
        return times to lights
    }

    @Test
    fun decodesAll15AddressesAt60FpsWithClockDriftAndExposureSmoothing() {
        val decoder = PatternDecoder(symbolS = 0.050)
        for (address in 1..15) {
            val (times, lights) = generateSignal(
                address = address,
                symbolS = 0.0515, // +3% clock drift (within 8% tolerance)
                fps = 60.0,
                durationS = 0.95,
                phaseS = 0.137 + address * 0.023,
                exposureS = 1.0 / 60.0
            )
            val result = decoder.decodeWindow(times, lights)
            assertTrue("Expected address $address to be accepted, got ${result.rejectionReasons}", result.accepted)
            assertEquals(address, result.address)
            assertTrue((result.rmse ?: 1.0) < 0.05)
            assertTrue((result.rmseMargin ?: 0.0) > 0.15)
        }
    }

    @Test
    fun decodesAt30FpsWithFullFrameExposureMixing() {
        val decoder = PatternDecoder(symbolS = 0.050)
        for (address in listOf(1, 5, 7, 11, 15)) {
            val (times, lights) = generateSignal(
                address = address,
                symbolS = 0.050,
                fps = 30.0,
                durationS = 0.92,
                phaseS = 0.21,
                exposureS = 1.0 / 30.0
            )
            val result = decoder.decodeWindow(times, lights)
            assertTrue("Expected 30fps address $address to decode, got ${result.rejectionReasons}", result.accepted)
            assertEquals(address, result.address)
        }
    }

    @Test
    fun decodesAt100MsAnd200MsSymbolSpeeds() {
        val decoder100 = PatternDecoder(symbolS = 0.100)
        val (t100, l100) = generateSignal(address = 9, symbolS = 0.100, fps = 30.0, durationS = 1.85, phaseS = 0.42)
        val r100 = decoder100.decodeWindow(t100, l100)
        assertTrue(r100.accepted)
        assertEquals(9, r100.address)

        val decoder200 = PatternDecoder(symbolS = 0.200)
        val (t200, l200) = generateSignal(address = 14, symbolS = 0.200, fps = 30.0, durationS = 3.65, phaseS = 0.88)
        val r200 = decoder200.decodeWindow(t200, l200)
        assertTrue(r200.accepted)
        assertEquals(14, r200.address)
    }

    @Test
    fun rejectsLowContrastAndSlowCameraAndInvalidWaveform() {
        val decoder = PatternDecoder(symbolS = 0.050)

        // Low contrast
        val (tLow, lLow) = generateSignal(address = 3, symbolS = 0.050, fps = 60.0, durationS = 0.92, lowLevel = 100.0, highLevel = 108.0)
        val rLow = decoder.decodeWindow(tLow, lLow)
        assertFalse(rLow.accepted)
        assertEquals("low_contrast", rLow.reason)
        assertNull(rLow.address)

        // Frame gap > 0.9 * rate (15 fps -> 66.7ms gap > 45ms)
        val (tSlow, lSlow) = generateSignal(address = 3, symbolS = 0.050, fps = 15.0, durationS = 1.2)
        val rSlow = decoder.decodeWindow(tSlow, lSlow)
        assertFalse(rSlow.accepted)
        assertTrue("missing_frames_or_too_slow_camera" in rSlow.rejectionReasons)

        // Alternating 1010... non-codebook signal
        val tAlt = DoubleArray(60) { it / 60.0 }
        val lAlt = DoubleArray(60) { i -> if ((i / 3) % 2 == 0) 200.0 else 20.0 }
        val rAlt = decoder.decodeWindow(tAlt, lAlt)
        assertFalse(rAlt.accepted)
        assertNull(rAlt.address)
    }

    @Test
    fun photometerLinearisesAndExcludesClippedPixels() {
        val photometer = RoiPhotometer(calibrationS = 0.1, clipLevel = 245)
        val width = 10
        val height = 10 // 100 pixels; 80 unclipped, 20 clipped

        fun makeFrame(unclippedValue: Int, clippedInThisFrame: Boolean): ByteArray {
            val rgb = ByteArray(width * height * 3)
            for (p in 0 until width * height) {
                val v = if (p < 20 && clippedInThisFrame) 250 else unclippedValue
                rgb[p * 3] = v.toByte()
                rgb[p * 3 + 1] = v.toByte()
                rgb[p * 3 + 2] = v.toByte()
            }
            return rgb
        }

        assertTrue(photometer.add(0.0, makeFrame(128, clippedInThisFrame = true), width, height).isEmpty())
        val calibrated = photometer.add(0.11, makeFrame(64, clippedInThisFrame = false), width, height)
        assertEquals(2, calibrated.size)

        val expected128 = ((128.0 / 255.0 + 0.055) / 1.055).pow(2.4) * 255.0
        val expected64 = ((64.0 / 255.0 + 0.055) / 1.055).pow(2.4) * 255.0
        assertEquals(expected128, calibrated[0].brightness, 1e-6)
        assertEquals(expected64, calibrated[1].brightness, 1e-6)
    }

    @Test(expected = PhotometryClippedException::class)
    fun photometerThrowsWhenAllPixelsClip() {
        val photometer = RoiPhotometer(calibrationS = 0.05, clipLevel = 245)
        val allWhite = ByteArray(10 * 10 * 3) { 255.toByte() }
        photometer.add(0.0, allWhite, 10, 10)
        photometer.add(0.06, allWhite, 10, 10)
    }
}
