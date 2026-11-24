package com.standby.opticalscanner.decoder

/**
 * Common contract for optical waveform decoders (Legacy 16-symbol & Standby 14-slot).
 */
interface IOpticalDecoder {
    val windowS: Double
    fun decodeWindow(times: DoubleArray, brightness: DoubleArray): DecodeResult
}
