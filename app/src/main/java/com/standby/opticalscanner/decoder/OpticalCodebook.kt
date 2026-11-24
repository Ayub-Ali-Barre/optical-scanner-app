package com.standby.opticalscanner.decoder

/**
 * Optical bus protocol options:
 * - STANDBY_14: Standby AB modern 14-slot Manchester burst standard (500 ms period, GLRT + persistence).
 * - LEGACY_16: Legacy 16-symbol continuous codebook (800 ms cycle, QR/SVD symbol evidence).
 */
enum class OpticalProtocol(
    val id: String,
    val displayName: String,
    val description: String,
    val defaultSymbolMs: Int,
    val supportedSymbolMs: List<Int>
) {
    STANDBY_14(
        id = "standby",
        displayName = "Standby 14-slot",
        description = "Ny standard (500 ms burst, Manchester + GLRT)",
        defaultSymbolMs = 36,
        supportedSymbolMs = listOf(36, 50, 71)
    ),
    LEGACY_16(
        id = "legacy",
        displayName = "Legacy 16-symbol",
        description = "Gammal standard (800 ms kontinuerlig)",
        defaultSymbolMs = 50,
        supportedSymbolMs = listOf(50, 100, 200)
    )
}

/**
 * Optical codebooks for addresses 1..15.
 */
object OpticalCodebook {
    const val SYMBOL_COUNT = 16
    const val STANDBY_SLOT_COUNT = 14

    /**
     * Continuous 16-symbol optical codebook (legacy standard, 800 ms cycle).
     */
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

    /**
     * Standby AB 14-slot Manchester burst patterns (header 1110 + 4 data bits manchester + 00 idle).
     * Period: 500 ms (14 slots á 35.7 ms).
     */
    val STANDBY_14_PATTERNS: Map<Int, String> = linkedMapOf(
        1 to "11101010100100",
        2 to "11101010011000",
        3 to "11101010010100",
        4 to "11101001101000",
        5 to "11101001100100",
        6 to "11101001011000",
        7 to "11101001010100",
        8 to "11100110101000",
        9 to "11100110100100",
        10 to "11100110011000",
        11 to "11100110010100",
        12 to "11100101101000",
        13 to "11100101100100",
        14 to "11100101011000",
        15 to "11100101010100"
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

    val standbyWords: Array<DoubleArray> = Array(addresses.size) { idx ->
        val bits = STANDBY_14_PATTERNS.getValue(addresses[idx])
        DoubleArray(STANDBY_SLOT_COUNT) { k -> if (bits[k] == '1') 1.0 else 0.0 }
    }

    val standbyPrefix: Array<DoubleArray> = Array(addresses.size) { idx ->
        val p = DoubleArray(STANDBY_SLOT_COUNT + 1)
        var acc = 0.0
        for (k in 0 until STANDBY_SLOT_COUNT) {
            acc += standbyWords[idx][k]
            p[k + 1] = acc
        }
        p
    }
}
