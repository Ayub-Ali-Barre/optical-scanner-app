package com.standby.opticalscanner.decoder

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Standby AB 14-slot Manchester burst decoder (500 ms period, 35.7 ms / slot).
 *
 * Implements scale-invariant GLRT (Generalized Likelihood Ratio Test) SSE ratio
 * against runner-up address and split-window correlation persistence, matching
 * Standby AB Light-code v4 research (StandbyResearchHV).
 */
class Standby14Decoder(
    val slotS: Double = 0.500 / 14.0, // ~0.035714 s default (35.7 ms per slot)
    val clockTolerance: Double = 0.08,
    val minGlr: Double = 1.75,
    val minPersistence: Double = 0.50,
    val minContrast: Double = 15.0,
    val minCorrelation: Double = 0.65,
    val exposureS: Double? = null
) : IOpticalDecoder {
    // 18 slots window covers full 14-slot period plus preamble overlap (~640 ms at 35.7 ms)
    override val windowS: Double = 18.0 * slotS

    private val addresses = OpticalCodebook.addresses
    private val words = OpticalCodebook.standbyWords
    private val prefix = OpticalCodebook.standbyPrefix
    private val slotCount = OpticalCodebook.STANDBY_SLOT_COUNT // 14

    init {
        require(slotS.isFinite() && slotS > 0.0) { "slotS must be positive." }
        require(clockTolerance.isFinite() && clockTolerance in 0.0..0.25) { "clockTolerance must be in 0..0.25." }
        require(minGlr.isFinite() && minGlr > 1.0) { "minGlr must be > 1.0." }
        require(minPersistence.isFinite() && minPersistence in 0.0..1.0) { "minPersistence must be in 0..1." }
        require(minContrast.isFinite() && minContrast > 0.0) { "minContrast must be positive." }
    }

    private class FitCandidate(
        var correlation: Double = -1.0,
        var rate: Double = 0.0,
        var phase: Double = 0.0,
        var exposure: Double = 0.0
    ) {
        fun copy(): FitCandidate = FitCandidate(correlation, rate, phase, exposure)
    }

    override fun decodeWindow(times: DoubleArray, brightness: DoubleArray): DecodeResult {
        require(times.size == brightness.size && times.size >= 2) {
            "At least two matching timestamps and brightness samples are required."
        }

        val n = times.size
        val t0 = times[0]
        val relative = DoubleArray(n) { i -> times[i] - t0 }
        val duration = relative[n - 1]

        val gaps = DoubleArray(n - 1) { i -> times[i + 1] - times[i] }
        var maxGap = 0.0
        for (g in gaps) if (g > maxGap) maxGap = g
        val medianDt = median(gaps)
        val observedFps = 1.0 / medianDt

        val sortedLight = brightness.copyOf().also { it.sort() }
        val low = percentile(sortedLight, 5.0)
        val high = percentile(sortedLight, 95.0)
        val contrast = high - low

        if (duration < slotCount * slotS * (1.0 - clockTolerance)) {
            return rejected("insufficient_duration", observedFps, contrast)
        }
        if (contrast < minContrast) {
            return rejected("low_contrast", observedFps, contrast)
        }
        if (n < 12) {
            return rejected("too_few_samples", observedFps, contrast)
        }

        // Center and normalize observed brightness
        var sumY = 0.0
        for (y in brightness) sumY += y
        val meanY = sumY / n

        var varY = 0.0
        for (y in brightness) {
            val d = y - meanY
            varY += d * d
        }
        val stdY = sqrt(varY / n)
        if (stdY < 1e-6) {
            return rejected("low_contrast", observedFps, contrast)
        }

        val yNorm = DoubleArray(n) { i -> (brightness[i] - meanY) / stdY }

        val lowRate = slotS * (1.0 - clockTolerance)
        val highRate = slotS * (1.0 + clockTolerance)
        val rates = if (clockTolerance > 0.0) linspace(lowRate, highRate, 9) else doubleArrayOf(slotS)
        val rateStep = (highRate - lowRate) / 8.0

        val exposures = if (exposureS != null) {
            doubleArrayOf(exposureS)
        } else {
            doubleArrayOf(0.0, 0.5 * medianDt, medianDt)
        }

        val numAddresses = addresses.size
        val best = Array(numAddresses) { FitCandidate() }

        // Coarse grid search: rates x exposures x 56 phases
        val numPhases = 56 // 4 samples per slot across 14 slots
        val tPred = DoubleArray(n)

        for (rate in rates) {
            val invRate = 1.0 / rate
            val phaseStep = (slotCount * rate) / numPhases.toDouble()

            for (exposure in exposures) {
                val isInstant = exposure <= 1e-9
                val half = exposure / (2.0 * rate)
                val invTwoHalf = if (isInstant) 0.0 else 1.0 / (2.0 * half)

                for (pIdx in 0 until numPhases) {
                    val phase = pIdx * phaseStep

                    for (a in 0 until numAddresses) {
                        val word = words[a]
                        val pref = prefix[a]

                        var sumT = 0.0
                        if (isInstant) {
                            for (t in 0 until n) {
                                val u = (relative[t] + phase) * invRate
                                val idx = floorMod14(floor(u).toInt())
                                val pt = word[idx]
                                tPred[t] = pt
                                sumT += pt
                            }
                        } else {
                            for (t in 0 until n) {
                                val u = (relative[t] + phase) * invRate
                                val intP = integralWord(u + half, word, pref)
                                val intM = integralWord(u - half, word, pref)
                                val pt = (intP - intM) * invTwoHalf
                                tPred[t] = pt
                                sumT += pt
                            }
                        }

                        val meanT = sumT / n
                        var varT = 0.0
                        var dotYT = 0.0
                        for (t in 0 until n) {
                            val tc = tPred[t] - meanT
                            varT += tc * tc
                            dotYT += yNorm[t] * tc
                        }

                        val stdT = sqrt(varT / n)
                        val r = if (stdT > 1e-6) dotYT / (n * stdT) else -1.0

                        if (r > best[a].correlation) {
                            best[a].correlation = r
                            best[a].rate = rate
                            best[a].phase = phase
                            best[a].exposure = exposure
                        }
                    }
                }
            }
        }

        // Fine refinement around top candidates
        val finePhaseSteps = linspace(-slotS * 0.5, slotS * 0.5, 11)
        for (a in 0 until numAddresses) {
            val center = best[a].copy()
            val fineRates = if (rateStep > 0.0) {
                linspace(
                    max(lowRate, center.rate - rateStep * 0.5),
                    min(highRate, center.rate + rateStep * 0.5),
                    5
                )
            } else {
                doubleArrayOf(slotS)
            }
            val word = words[a]
            val pref = prefix[a]

            for (rate in fineRates) {
                val invRate = 1.0 / rate
                val isInstant = center.exposure <= 1e-9
                val half = center.exposure / (2.0 * rate)
                val invTwoHalf = if (isInstant) 0.0 else 1.0 / (2.0 * half)

                for (dp in finePhaseSteps) {
                    val phase = center.phase + dp
                    var sumT = 0.0

                    if (isInstant) {
                        for (t in 0 until n) {
                            val u = (relative[t] + phase) * invRate
                            val idx = floorMod14(floor(u).toInt())
                            val pt = word[idx]
                            tPred[t] = pt
                            sumT += pt
                        }
                    } else {
                        for (t in 0 until n) {
                            val u = (relative[t] + phase) * invRate
                            val intP = integralWord(u + half, word, pref)
                            val intM = integralWord(u - half, word, pref)
                            val pt = (intP - intM) * invTwoHalf
                            tPred[t] = pt
                            sumT += pt
                        }
                    }

                    val meanT = sumT / n
                    var varT = 0.0
                    var dotYT = 0.0
                    for (t in 0 until n) {
                        val tc = tPred[t] - meanT
                        varT += tc * tc
                        dotYT += yNorm[t] * tc
                    }

                    val stdT = sqrt(varT / n)
                    val r = if (stdT > 1e-6) dotYT / (n * stdT) else -1.0

                    if (r > best[a].correlation) {
                        best[a].correlation = r
                        best[a].rate = rate
                        best[a].phase = phase
                    }
                }
            }
        }

        // Rank addresses by correlation
        val ranking = (0 until numAddresses).sortedByDescending { best[it].correlation }
        val bestIdx = ranking[0]
        val runnerIdx = ranking[1]

        val sc1 = best[bestIdx].correlation
        val sc2 = best[runnerIdx].correlation
        val margin = sc1 - sc2

        val sseFloor = 0.005
        val sse1 = max(1.0 - sc1 * sc1, sseFloor)
        val sse2 = max(1.0 - max(sc2, 0.0) * max(sc2, 0.0), sseFloor)
        val glrRatio = sse2 / sse1

        // Split-window persistence check
        val bestFit = best[bestIdx]
        val bestWord = words[bestIdx]
        val bestPref = prefix[bestIdx]
        val invRate = 1.0 / bestFit.rate
        val isInstant = bestFit.exposure <= 1e-9
        val half = bestFit.exposure / (2.0 * bestFit.rate)
        val invTwoHalf = if (isInstant) 0.0 else 1.0 / (2.0 * half)

        for (t in 0 until n) {
            val u = (relative[t] + bestFit.phase) * invRate
            val pt = if (isInstant) {
                bestWord[floorMod14(floor(u).toInt())]
            } else {
                val intP = integralWord(u + half, bestWord, bestPref)
                val intM = integralWord(u - half, bestWord, bestPref)
                (intP - intM) * invTwoHalf
            }
            tPred[t] = pt
        }

        val halfDuration = duration * 0.5
        val r1 = computeSubwindowCorrelation(relative, yNorm, tPred, 0.0, halfDuration)
        val r2 = computeSubwindowCorrelation(relative, yNorm, tPred, duration - halfDuration, duration)
        val persistence = min(r1, r2)

        val failures = ArrayList<String>()
        if (maxGap > 1.2 * bestFit.rate) failures.add("missing_frames_or_too_slow_camera")
        if (sc1 < minCorrelation) failures.add("poor_pattern_fit")
        if (glrRatio < minGlr && margin < 0.08) failures.add("ambiguous_address")
        if (persistence < minPersistence) failures.add("insufficient_persistence")

        val accepted = failures.isEmpty()
        val candidateAddress = addresses[bestIdx]

        return DecodeResult(
            accepted = accepted,
            address = if (accepted) candidateAddress else null,
            candidateAddress = candidateAddress,
            reason = failures.firstOrNull() ?: "ok",
            rejectionReasons = failures,
            rmse = sqrt(sse1),
            rmseMargin = margin,
            glrRatio = glrRatio,
            persistence = persistence,
            fittedSymbolMs = bestFit.rate * 1000.0,
            observedFps = observedFps,
            contrast = contrast
        )
    }

    private fun computeSubwindowCorrelation(
        times: DoubleArray,
        y: DoubleArray,
        t: DoubleArray,
        tStart: Double,
        tEnd: Double
    ): Double {
        var count = 0
        var sumY = 0.0
        var sumT = 0.0
        for (i in times.indices) {
            if (times[i] in tStart..tEnd) {
                count++
                sumY += y[i]
                sumT += t[i]
            }
        }
        if (count < 4) return 0.0

        val meanY = sumY / count
        val meanT = sumT / count
        var varY = 0.0
        var varT = 0.0
        var covYT = 0.0
        for (i in times.indices) {
            if (times[i] in tStart..tEnd) {
                val dy = y[i] - meanY
                val dt = t[i] - meanT
                varY += dy * dy
                varT += dt * dt
                covYT += dy * dt
            }
        }
        val denom = sqrt(varY * varT)
        return if (denom > 1e-9) covYT / denom else 0.0
    }

    private fun integralWord(z: Double, word: DoubleArray, pref: DoubleArray): Double {
        val cycles = floor(z / 14.0)
        val rem = z - cycles * 14.0
        val idx = min(floor(rem).toInt(), 13).coerceAtLeast(0)
        // Standby 14-slot burst has exactly 7 ones per cycle (preamble 1110 -> 3, data -> 4)
        return cycles * 7.0 + pref[idx] + (rem - idx) * word[idx]
    }

    private fun floorMod14(value: Int): Int = ((value % 14) + 14) % 14

    private fun linspace(start: Double, end: Double, count: Int): DoubleArray {
        if (count <= 1) return doubleArrayOf(start)
        val step = (end - start) / (count - 1)
        return DoubleArray(count) { i -> start + i * step }
    }

    private fun median(values: DoubleArray): Double {
        val sorted = values.copyOf().also { it.sort() }
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) * 0.5
        }
    }

    private fun percentile(sorted: DoubleArray, p: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val idx = (p / 100.0) * (sorted.size - 1)
        val lo = floor(idx).toInt()
        val hi = min(lo + 1, sorted.size - 1)
        val frac = idx - lo
        return sorted[lo] * (1.0 - frac) + sorted[hi] * frac
    }

    private fun rejected(reason: String, observedFps: Double, contrast: Double): DecodeResult {
        return DecodeResult(
            accepted = false,
            address = null,
            candidateAddress = null,
            reason = reason,
            rejectionReasons = listOf(reason),
            observedFps = observedFps,
            contrast = contrast
        )
    }
}
