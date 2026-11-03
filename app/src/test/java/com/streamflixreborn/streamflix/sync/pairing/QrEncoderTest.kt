package com.streamflixreborn.streamflix.sync.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes the encoder output with an independent reader (format info lookup,
 * zig-zag read-back, de-interleaving, Reed-Solomon syndromes, byte-mode
 * parsing) to check that the symbol is self-consistent and standard.
 */
class QrEncoderTest {

    @Test
    fun formatBitsMatchIsoTableForLevelM() {
        val expected = listOf(
            "101010000010010", "101000100100101", "101111001111100", "101101101001011",
            "100010111111001", "100000011001110", "100111110010111", "100101010100000",
        )
        expected.forEachIndexed { mask, bits ->
            assertEquals(bits, QrEncoder.formatBits(mask).toString(2).padStart(15, '0'))
        }
    }

    @Test
    fun versionBitsMatchIsoTable() {
        assertEquals("000111110010010100", QrEncoder.versionBits(7).toString(2).padStart(18, '0'))
        assertEquals("001010010011010011", QrEncoder.versionBits(10).toString(2).padStart(18, '0'))
    }

    @Test
    fun capacityTable() {
        // ISO 18004 data codewords for level M.
        val expected = intArrayOf(16, 28, 44, 64, 86, 108, 124, 154, 182, 216)
        for (v in 1..10) assertEquals("v$v", expected[v - 1], QrEncoder.numDataCodewords(v))
    }

    @Test
    fun alignmentPositions() {
        assertEquals(listOf(6, 18), QrEncoder.alignmentPatternPositions(2).toList())
        assertEquals(listOf(6, 22, 38), QrEncoder.alignmentPatternPositions(7).toList())
        assertEquals(listOf(6, 28, 50), QrEncoder.alignmentPatternPositions(10).toList())
    }

    @Test
    fun pairingUriDecodesBack() {
        val uri = PairingProtocol.PairingUri("192.168.100.200", 65535, PairingCrypto.newToken()).format()
        assertEquals(uri, decode(QrEncoder.encode(uri)))
    }

    @Test
    fun allVersionsDecodeBack() {
        for (length in listOf(1, 14, 26, 42, 62, 84, 106, 122, 152, 180, 213)) {
            val text = buildString { repeat(length) { append(('a' + it % 26)) } }
            assertEquals("length $length", text, decode(QrEncoder.encode(text)))
        }
    }

    private fun decode(code: QrEncoder.QrCode): String {
        val size = code.size
        val version = (size - 17) / 4
        assertTrue(code.isDark(0, 0) && code.isDark(6, 6) && !code.isDark(7, 7))

        // Format information, first copy.
        var format = 0
        fun put(bit: Int, dark: Boolean) { if (dark) format = format or (1 shl bit) }
        for (i in 0..5) put(i, code.isDark(8, i))
        put(6, code.isDark(8, 7))
        put(7, code.isDark(8, 8))
        put(8, code.isDark(7, 8))
        for (i in 9 until 15) put(i, code.isDark(14 - i, 8))
        val mask = (0 until 8).first { QrEncoder.formatBits(it) == format }
        // Second copy must agree.
        var second = 0
        for (i in 0 until 8) if (code.isDark(size - 1 - i, 8)) second = second or (1 shl i)
        for (i in 8 until 15) if (code.isDark(8, size - 15 + i)) second = second or (1 shl i)
        assertEquals(format, second)

        val function = QrEncoder.Builder(version).apply { drawFunctionPatterns() }.isFunction
        val rawCodewords = QrEncoder.numRawDataModules(version) / 8
        val bits = ArrayList<Boolean>()
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) for (j in 0 until 2) {
                val x = right - j
                val upward = ((right + 1) and 2) == 0
                val y = if (upward) size - 1 - vert else vert
                if (!function[y][x] && bits.size < rawCodewords * 8) {
                    val invert = when (mask) {
                        0 -> (x + y) % 2 == 0
                        1 -> y % 2 == 0
                        2 -> x % 3 == 0
                        3 -> (x + y) % 3 == 0
                        4 -> (x / 3 + y / 2) % 2 == 0
                        5 -> x * y % 2 + x * y % 3 == 0
                        6 -> (x * y % 2 + x * y % 3) % 2 == 0
                        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    }
                    bits += code.isDark(x, y) != invert
                }
            }
            right -= 2
        }
        val codewords = IntArray(rawCodewords) { i ->
            (0 until 8).fold(0) { acc, b -> (acc shl 1) or if (bits[i * 8 + b]) 1 else 0 }
        }

        // De-interleave.
        val numBlocks = QrEncoder.NUM_ERROR_CORRECTION_BLOCKS[version]
        val eccLen = QrEncoder.ECC_CODEWORDS_PER_BLOCK[version]
        val numShort = numBlocks - rawCodewords % numBlocks
        val shortLen = rawCodewords / numBlocks
        val blocks = List(numBlocks) { i -> IntArray(shortLen + if (i < numShort) 0 else 1) }
        var k = 0
        val dataLenShort = shortLen - eccLen
        for (i in 0..dataLenShort) for (b in 0 until numBlocks) {
            if (i < dataLenShort || b >= numShort) blocks[b][i] = codewords[k++]
        }
        for (i in 0 until eccLen) for (b in 0 until numBlocks) {
            val block = blocks[b]
            block[block.size - eccLen + i] = codewords[k++]
        }
        assertEquals(rawCodewords, k)

        // Every syndrome S_i = C(alpha^i), i in 0 until eccLen, must be zero.
        for (block in blocks) {
            var alphaI = 1
            for (i in 0 until eccLen) {
                var s = 0
                for (c in block) s = QrEncoder.gfMultiply(s, alphaI) xor c
                assertEquals("syndrome $i", 0, s)
                alphaI = QrEncoder.gfMultiply(alphaI, 2)
            }
        }

        // Byte-mode segment.
        val data = blocks.flatMap { it.take(it.size - eccLen) }
        val stream = data.flatMap { byte -> (7 downTo 0).map { (byte shr it) and 1 } }
        var pos = 0
        fun read(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or stream[pos++] }; return v }
        assertEquals(0x4, read(4))
        val count = read(if (version <= 9) 8 else 16)
        val bytes = ByteArray(count) { read(8).toByte() }
        return String(bytes, Charsets.UTF_8)
    }
}
