package com.standby.opticalscanner.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.math.min

class Standby14DecoderTest {

    private fun generateStandbySignal(
        address: Int,
        slotS: Double,
        fps: Double,
        durationS: Double,
        phaseS: Double = 0.0,
        exposureS: Double = 0.0,
        lowLevel: Double = 25.0,
        highLevel: Double = 190.0
    ): Pair<DoubleArray, DoubleArray> {
        val bits = OpticalCodebook.STANDBY_14_PATTERNS.getValue(address)
        val word = DoubleArray(14) { k -> if (bits[k] == '1') 1.0 else 0.0 }
        val prefix = DoubleArray(15)
        for (k in 0 until 14) prefix[k + 1] = prefix[k] + word[k]

        fun integral(z: Double): Double {
            val cycles = floor(z / 14.0)
            val rem = z - cycles * 14.0
            val idx = min(floor(rem).toInt(), 13).coerceAtLeast(0)
            return cycles * 7.0 + prefix[idx] + (rem - idx) * word[idx]
        }

        val dt = 1.0 / fps
        val count = floor(durationS * fps).toInt() + 1
        val times = DoubleArray(count) { i -> i * dt }
        val lights = DoubleArray(count) { i ->
            val u = (times[i] + phaseS) / slotS
            val level = if (exposureS <= 1e-9) {
                val idx = ((floor(u).toInt() % 14) + 14) % 14
                word[idx]
            } else {
                val half = exposureS / (2.0 * slotS)
                (integral(u + half) - integral(u - half)) / (2.0 * half)
            }
            lowLevel + level * (highLevel - lowLevel)
        }
        return times to lights
    }

    @Test
    fun decodesAll15AddressesAt60FpsWithStandby14Burst() {
        val decoder = Standby14Decoder(slotS = 0.500 / 14.0)
        for (address in 1..15) {
            val (times, lights) = generateStandbySignal(
                address = address,
                slotS = (0.500 / 14.0) * 1.02, // +2% clock drift
                fps = 60.0,
                durationS = 0.65, // Fast decode window!
                phaseS = 0.08 + address * 0.015,
                exposureS = 1.0 / 60.0
            )
            val result = decoder.decodeWindow(times, lights)
            assertTrue("Expected address $address to be accepted, got ${result.rejectionReasons}", result.accepted)
            assertEquals(address, result.address)
            assertTrue("GLR should be high, was ${result.glrRatio}", (result.glrRatio ?: 0.0) >= 1.75)
            assertTrue("Persistence should be high, was ${result.persistence}", (result.persistence ?: 0.0) >= 0.50)
        }
    }

    @Test
    fun decodesStandby14At30FpsWithExposureSmoothing() {
        val decoder = Standby14Decoder(slotS = 0.035714)
        for (address in listOf(1, 4, 7, 10, 15)) {
            val (times, lights) = generateStandbySignal(
                address = address,
                slotS = 0.035714,
                fps = 30.0,
                durationS = 0.70,
                phaseS = 0.12,
                exposureS = 1.0 / 30.0
            )
            val result = decoder.decodeWindow(times, lights)
            assertTrue("Expected 30fps address $address to decode, got ${result.rejectionReasons}", result.accepted)
            assertEquals(address, result.address)
        }
    }

    @Test
    fun decodesAt50MsAnd71MsSpeedPresets() {
        val decoder50 = Standby14Decoder(slotS = 0.050)
        val (t50, l50) = generateStandbySignal(address = 5, slotS = 0.050, fps = 30.0, durationS = 0.95, phaseS = 0.15)
        val r50 = decoder50.decodeWindow(t50, l50)
        assertTrue(r50.accepted)
        assertEquals(5, r50.address)

        val decoder71 = Standby14Decoder(slotS = 0.071)
        val (t71, l71) = generateStandbySignal(address = 12, slotS = 0.071, fps = 30.0, durationS = 1.35, phaseS = 0.25)
        val r71 = decoder71.decodeWindow(t71, l71)
        assertTrue(r71.accepted)
        assertEquals(12, r71.address)
    }

    @Test
    fun rejectsLowContrastAndShortDuration() {
        val decoder = Standby14Decoder(slotS = 0.035714)

        // Low contrast
        val (tLow, lLow) = generateStandbySignal(address = 2, slotS = 0.035714, fps = 60.0, durationS = 0.65, lowLevel = 100.0, highLevel = 105.0)
        val rLow = decoder.decodeWindow(tLow, lLow)
        assertFalse(rLow.accepted)
        assertEquals("low_contrast", rLow.reason)

        // Duration too short (under 14 slots)
        val (tShort, lShort) = generateStandbySignal(address = 2, slotS = 0.035714, fps = 60.0, durationS = 0.25)
        val rShort = decoder.decodeWindow(tShort, lShort)
        assertFalse(rShort.accepted)
        assertEquals("insufficient_duration", rShort.reason)
    }
}
