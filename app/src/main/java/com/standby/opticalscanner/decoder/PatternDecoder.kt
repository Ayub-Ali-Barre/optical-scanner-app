package com.standby.opticalscanner.decoder

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class DecodeResult(
    val accepted: Boolean,
    val address: Int?,
    val candidateAddress: Int?,
    val reason: String,
    val rejectionReasons: List<String>,
    val rmse: Double? = null,
    val rmseMargin: Double? = null,
    val fittedSymbolMs: Double? = null,
    val observedFps: Double? = null,
    val contrast: Double = 0.0
)

/**
 * Exact Kotlin port of PatternDecoder from led_decoder.py.
 *
 * Whole-waveform timing search across all 15 continuous 16-symbol optical patterns,
 * per-address fine refinement, and exposure-aware symbol evidence verification.
 */
class PatternDecoder(
    val symbolS: Double = 0.050,
    val clockTolerance: Double = 0.08,
    val maxRmse: Double = 0.20,
    val minMargin: Double = 0.10,
    val minContrast: Double = 15.0,
    val exposureS: Double? = null
) {
    val windowS: Double = 18.0 * symbolS

    private val addresses = OpticalCodebook.addresses
    private val words = OpticalCodebook.words
    private val prefix = OpticalCodebook.prefix

    init {
        require(symbolS.isFinite() && symbolS > 0.0) { "symbolS must be positive." }
        require(clockTolerance.isFinite() && clockTolerance in 0.0..0.25) {
            "clockTolerance must be in 0..0.25."
        }
        require(maxRmse.isFinite() && maxRmse > 0.0 && maxRmse < 1.0) { "Invalid maxRmse." }
        require(minMargin.isFinite() && minMargin >= 0.0 && minMargin < 1.0) { "Invalid minMargin." }
        require(minContrast.isFinite() && minContrast > 0.0) { "Invalid minContrast." }
        require(exposureS == null || (exposureS.isFinite() && exposureS >= 0.0)) {
            "Invalid exposureS."
        }
    }

    private class FitCandidate(
        var mse: Double = Double.POSITIVE_INFINITY,
        var rate: Double = 0.0,
        var phase: Double = 0.0,
        var exposure: Double = 0.0
    ) {
        fun copy(): FitCandidate = FitCandidate(mse, rate, phase, exposure)
    }

    fun decodeWindow(times: DoubleArray, brightness: DoubleArray): DecodeResult {
        require(times.size == brightness.size && times.size >= 2) {
            "At least two matching timestamps and brightness samples are required."
        }
        for (i in times.indices) {
            require(times[i].isFinite() && brightness[i].isFinite()) {
                "Timestamps and brightness values must be finite."
            }
            if (i > 0) {
                require(times[i] > times[i - 1]) { "Timestamps must be strictly increasing." }
            }
        }

        val n = times.size
        val t0 = times[0]
        val relative = DoubleArray(n) { i -> times[i] - t0 }
        val gaps = DoubleArray(n - 1) { i -> times[i + 1] - times[i] }
        var maxGap = 0.0
        for (g in gaps) {
            if (g > maxGap) maxGap = g
        }
        val medianDt = median(gaps)
        val observedFps = 1.0 / medianDt

        val sortedLight = brightness.copyOf().also { it.sort() }
        val low = percentile(sortedLight, 5.0)
        val high = percentile(sortedLight, 95.0)
        val contrast = high - low

        if (relative[n - 1] < 16.0 * symbolS * (1.0 - clockTolerance)) {
            return rejected("insufficient_duration", observedFps, contrast)
        }
        if (contrast < minContrast) {
            return rejected("low_contrast", observedFps, contrast)
        }
        if (n < 16) {
            return rejected("too_few_samples", observedFps, contrast)
        }

        val obs = DoubleArray(n) { i -> ((brightness[i] - low) / contrast).coerceIn(0.0, 1.0) }
        val lowRate = symbolS * (1.0 - clockTolerance)
        val highRate = symbolS * (1.0 + clockTolerance)
        val rates = if (clockTolerance > 0.0) linspace(lowRate, highRate, 9) else doubleArrayOf(symbolS)
        val rateStep = (highRate - lowRate) / 8.0
        val exposures = if (exposureS != null) {
            doubleArrayOf(exposureS)
        } else {
            doubleArrayOf(0.0, 0.5 * medianDt, medianDt)
        }

        val numAddresses = addresses.size
        val best = Array(numAddresses) { FitCandidate() }

        // Pre-allocated scratch buffers for zero-allocation coarse search
        val idxDiscrete = IntArray(n)
        val cyclesPlus = DoubleArray(n)
        val remPlus = DoubleArray(n)
        val idxPlus = IntArray(n)
        val cyclesMinus = DoubleArray(n)
        val remMinus = DoubleArray(n)
        val idxMinus = IntArray(n)
        val invN = 1.0 / n

        // Coarse search across all 15 addresses
        for (rate in rates) {
            val invRate = 1.0 / rate
            val phaseStep = rate / 8.0
            for (exposure in exposures) {
                val isInstant = exposure <= 1e-9
                val half = exposure / (2.0 * rate)
                val invTwoHalf = if (isInstant) 0.0 else 1.0 / (2.0 * half)

                for (phaseIdx in 0 until 128) {
                    val phase = phaseIdx * phaseStep
                    if (isInstant) {
                        for (t in 0 until n) {
                            val u = (relative[t] + phase) * invRate
                            idxDiscrete[t] = floorMod16(floor(u).toInt())
                        }
                        for (a in 0 until numAddresses) {
                            val word = words[a]
                            var sumSq = 0.0
                            for (t in 0 until n) {
                                val diff = word[idxDiscrete[t]] - obs[t]
                                sumSq += diff * diff
                            }
                            val score = sumSq * invN
                            if (score < best[a].mse) {
                                best[a].mse = score
                                best[a].rate = rate
                                best[a].phase = phase
                                best[a].exposure = exposure
                            }
                        }
                    } else {
                        for (t in 0 until n) {
                            val u = (relative[t] + phase) * invRate
                            val zP = u + half
                            val cP = floor(zP / 16.0)
                            val rP = zP - cP * 16.0
                            cyclesPlus[t] = cP * 8.0
                            remPlus[t] = rP
                            idxPlus[t] = min(floor(rP).toInt(), 15).coerceAtLeast(0)

                            val zM = u - half
                            val cM = floor(zM / 16.0)
                            val rM = zM - cM * 16.0
                            cyclesMinus[t] = cM * 8.0
                            remMinus[t] = rM
                            idxMinus[t] = min(floor(rM).toInt(), 15).coerceAtLeast(0)
                        }
                        for (a in 0 until numAddresses) {
                            val word = words[a]
                            val pref = prefix[a]
                            var sumSq = 0.0
                            for (t in 0 until n) {
                                val iP = idxPlus[t]
                                val intP = cyclesPlus[t] + pref[iP] + (remPlus[t] - iP) * word[iP]
                                val iM = idxMinus[t]
                                val intM = cyclesMinus[t] + pref[iM] + (remMinus[t] - iM) * word[iM]
                                val pred = (intP - intM) * invTwoHalf
                                val diff = pred - obs[t]
                                sumSq += diff * diff
                            }
                            val score = sumSq * invN
                            if (score < best[a].mse) {
                                best[a].mse = score
                                best[a].rate = rate
                                best[a].phase = phase
                                best[a].exposure = exposure
                            }
                        }
                    }
                }
            }
        }

        // Refine each address separately so the runner-up is refined too.
        val phaseOffsets = linspace(-symbolS / 8.0, symbolS / 8.0, 17)
        for (a in 0 until numAddresses) {
            val center = best[a].copy()
            val fineRates = if (rateStep > 0.0) {
                linspace(
                    max(lowRate, center.rate - rateStep),
                    min(highRate, center.rate + rateStep),
                    7
                )
            } else {
                doubleArrayOf(symbolS)
            }
            val word = words[a]
            val pref = prefix[a]

            for (rate in fineRates) {
                val invRate = 1.0 / rate
                for (exposure in exposures) {
                    val isInstant = exposure <= 1e-9
                    val half = exposure / (2.0 * rate)
                    val invTwoHalf = if (isInstant) 0.0 else 1.0 / (2.0 * half)

                    for (offset in phaseOffsets) {
                        val phase = center.phase + offset
                        var sumSq = 0.0
                        if (isInstant) {
                            for (t in 0 until n) {
                                val u = (relative[t] + phase) * invRate
                                val pred = word[floorMod16(floor(u).toInt())]
                                val diff = pred - obs[t]
                                sumSq += diff * diff
                            }
                        } else {
                            for (t in 0 until n) {
                                val u = (relative[t] + phase) * invRate
                                val intP = integralWord(u + half, word, pref)
                                val intM = integralWord(u - half, word, pref)
                                val pred = (intP - intM) * invTwoHalf
                                val diff = pred - obs[t]
                                sumSq += diff * diff
                            }
                        }
                        val score = sumSq * invN
                        if (score < best[a].mse) {
                            best[a].mse = score
                            best[a].rate = rate
                            best[a].phase = phase
                            best[a].exposure = exposure
                        }
                    }
                }
            }
        }

        val ranking = (0 until numAddresses).sortedBy { best[it].mse }
        val bestIdx = ranking[0]
        val runnerIdx = ranking[1]
        val fit = best[bestIdx]
        val rmse = sqrt(fit.mse)
        val runnerRmse = sqrt(best[runnerIdx].mse)
        val margin = runnerRmse - rmse

        val evidence = computeSymbolEvidence(relative, obs, fit, words[bestIdx])

        val failures = ArrayList<String>()
        if (relative[n - 1] < 16.0 * fit.rate) failures.add("insufficient_duration")
        if (maxGap > 0.9 * fit.rate) failures.add("missing_frames_or_too_slow_camera")
        if (rmse > maxRmse) failures.add("poor_pattern_fit")
        if (margin < minMargin) failures.add("ambiguous_address")
        if (evidence.hasMissing) failures.add("missing_symbol_information")
        if (!evidence.infoSufficient) failures.add("insufficient_symbol_information")
        if (evidence.hasInconsistent) failures.add("inconsistent_symbol_samples")
        if (evidence.hasUncertain) failures.add("uncertain_symbol_samples")

        val accepted = failures.isEmpty()
        val candidateAddress = addresses[bestIdx]
        return DecodeResult(
            accepted = accepted,
            address = if (accepted) candidateAddress else null,
            candidateAddress = candidateAddress,
            reason = failures.firstOrNull() ?: "ok",
            rejectionReasons = failures,
            rmse = rmse,
            rmseMargin = margin,
            fittedSymbolMs = fit.rate * 1000.0,
            observedFps = observedFps,
            contrast = contrast
        )
    }

    private data class SymbolEvidence(
        val hasMissing: Boolean,
        val infoSufficient: Boolean,
        val hasInconsistent: Boolean,
        val hasUncertain: Boolean
    )

    private fun computeSymbolEvidence(
        relative: DoubleArray,
        observed: DoubleArray,
        fit: FitCandidate,
        word: DoubleArray
    ): SymbolEvidence {
        val n = relative.size
        val basis = buildExposureBasis(relative, fit.rate, fit.phase, fit.exposure)
        val solution = LinearAlgebra.solveSymbolBasis(basis, observed)

        val counts = IntArray(OpticalCodebook.SYMBOL_COUNT)
        var residualNormSq = 0.0
        for (t in 0 until n) {
            var pred = 0.0
            val row = basis[t]
            for (k in 0 until OpticalCodebook.SYMBOL_COUNT) {
                val b = row[k]
                if (b > 1e-6) counts[k]++
                pred += b * solution.levels[k]
            }
            val r = observed[t] - pred
            residualNormSq += r * r
        }

        val dof = max(1, n - solution.rank)
        val sigma = max(0.02, sqrt(residualNormSq) / sqrt(dof.toDouble()))

        val lastSv = solution.singularValues[OpticalCodebook.SYMBOL_COUNT - 1]
        val condition = if (lastSv > 1e-10) solution.singularValues[0] / lastSv else null
        val infoSufficient = (solution.rank == OpticalCodebook.SYMBOL_COUNT) &&
            (condition != null) && (condition <= 20.0)

        var hasMissing = false
        var hasInconsistent = false
        var hasUncertain = false

        for (k in 0 until OpticalCodebook.SYMBOL_COUNT) {
            if (counts[k] == 0) hasMissing = true
            val expected = word[k] > 0.5
            val value = solution.levels[k]
            val band = 2.0 * sigma * sqrt(max(0.0, solution.diagPinvBtB[k]))

            if ((expected && value < 0.5) || (!expected && value >= 0.5)) {
                hasInconsistent = true
            } else if (
                (expected && (value < 0.65 || value - band <= 0.5)) ||
                (!expected && (value > 0.35 || value + band >= 0.5))
            ) {
                hasUncertain = true
            }
        }

        return SymbolEvidence(
            hasMissing = hasMissing,
            infoSufficient = infoSufficient,
            hasInconsistent = hasInconsistent,
            hasUncertain = hasUncertain
        )
    }

    private fun buildExposureBasis(
        relative: DoubleArray,
        rate: Double,
        phase: Double,
        exposure: Double
    ): Array<DoubleArray> {
        val n = relative.size
        val basis = Array(n) { DoubleArray(OpticalCodebook.SYMBOL_COUNT) }
        val invRate = 1.0 / rate
        if (exposure <= 1e-9) {
            for (t in 0 until n) {
                val pos = (relative[t] + phase) * invRate
                basis[t][floorMod16(floor(pos).toInt())] = 1.0
            }
            return basis
        }

        val half = exposure / (2.0 * rate)
        val invTwoHalf = 1.0 / (2.0 * half)
        for (t in 0 until n) {
            val pos = (relative[t] + phase) * invRate
            val zP = pos + half
            val cP = floor(zP / 16.0)
            val rP = zP - cP * 16.0

            val zM = pos - half
            val cM = floor(zM / 16.0)
            val rM = zM - cM * 16.0

            val row = basis[t]
            for (k in 0 until OpticalCodebook.SYMBOL_COUNT) {
                val intP = cP + (rP - k).coerceIn(0.0, 1.0)
                val intM = cM + (rM - k).coerceIn(0.0, 1.0)
                row[k] = (intP - intM) * invTwoHalf
            }
        }
        return basis
    }

    private fun integralWord(z: Double, word: DoubleArray, pref: DoubleArray): Double {
        val cycles = floor(z / 16.0)
        val rem = z - cycles * 16.0
        val idx = min(floor(rem).toInt(), 15).coerceAtLeast(0)
        return cycles * 8.0 + pref[idx] + (rem - idx) * word[idx]
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

    private fun floorMod16(value: Int): Int = ((value % 16) + 16) % 16

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
}
