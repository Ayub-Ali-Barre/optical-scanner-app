package com.standby.opticalscanner.decoder

/**
 * Continuous 16-symbol optical codebook matching led_decoder.py (addresses 1..15).
 */
object OpticalCodebook {
    const val SYMBOL_COUNT = 16

    val PATTERNS: Map<Int, String> = linkedMapOf(
        1 to "0010011001110011",
        2 to "0001011001010111",
        3 to "0001110101001101",
        4 to "0001010011011101",
        5 to "0001001101011101",
        6 to "0001010110010111",
        7 to "0001110100110101",
        8 to "0010010110100111",
        9 to "0001101001011011",
        10 to "0010010101101101",
        11 to "0010100101110011",
        12 to "0001011100101101",
        13 to "0001110010101101",
        14 to "0001101110101001",
        15 to "0001110100101011"
    )

    val addresses: IntArray = PATTERNS.keys.sorted().toIntArray()

    val words: Array<DoubleArray> = Array(addresses.size) { idx ->
        val bits = PATTERNS.getValue(addresses[idx])
        DoubleArray(SYMBOL_COUNT) { k -> if (bits[k] == '1') 1.0 else 0.0 }
    }

    val prefix: Array<DoubleArray> = Array(addresses.size) { idx ->
        val p = DoubleArray(SYMBOL_COUNT + 1)
        var acc = 0.0
        for (k in 0 until SYMBOL_COUNT) {
            acc += words[idx][k]
            p[k + 1] = acc
        }
        p
    }
}
