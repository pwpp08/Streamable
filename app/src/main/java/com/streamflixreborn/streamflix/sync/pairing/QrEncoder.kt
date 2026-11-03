package com.streamflixreborn.streamflix.sync.pairing

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Minimal self-contained QR Code encoder: byte mode, error correction level M,
 * versions 1-10 (up to 213 bytes). Enough for the pairing URI, and avoids a
 * dependency until zxing is added (see app/src/pending).
 *
 * Follows ISO/IEC 18004 (the structure mirrors Project Nayuki's reference
 * implementation): data codewords + Reed-Solomon ECC, interleaving, function
 * patterns, zig-zag placement and the lowest-penalty of the 8 masks.
 */
object QrEncoder {

    class QrCode(val size: Int, private val modules: Array<BooleanArray>) {
        /** True when the module at column [x], row [y] is dark. */
        fun isDark(x: Int, y: Int): Boolean = modules[y][x]
    }

    private const val MAX_VERSION = 10
    // Level M, index = version.
    private val ECC_CODEWORDS_PER_BLOCK = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
    private val NUM_ERROR_CORRECTION_BLOCKS = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
    private const val ECC_FORMAT_BITS_M = 0

    fun encode(text: String): QrCode = encode(text.toByteArray(Charsets.UTF_8))

    fun encode(data: ByteArray): QrCode {
        val version = (1..MAX_VERSION).firstOrNull { v ->
            4 + charCountBits(v) + data.size * 8 <= numDataCodewords(v) * 8
        } ?: throw IllegalArgumentException("Data too long for a version $MAX_VERSION-M QR code")

        // Data bit stream.
        val bits = BitBuffer()
        bits.append(0x4, 4)
        bits.append(data.size, charCountBits(version))
        data.forEach { bits.append(it.toInt() and 0xFF, 8) }
        val capacityBits = numDataCodewords(version) * 8
        bits.append(0, minOf(4, capacityBits - bits.size))
        bits.append(0, (8 - bits.size % 8) % 8)
        var pad = 0xEC
        while (bits.size < capacityBits) {
            bits.append(pad, 8)
            pad = pad xor 0xEC xor 0x11
        }
        val dataCodewords = ByteArray(bits.size / 8)
        for (i in 0 until bits.size) {
            if (bits[i]) {
                dataCodewords[i ushr 3] = (dataCodewords[i ushr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()
            }
        }
        val allCodewords = addEccAndInterleave(version, dataCodewords)

        val builder = Builder(version)
        builder.drawFunctionPatterns()
        builder.drawCodewords(allCodewords)

        var bestMask = 0
        var minPenalty = Int.MAX_VALUE
        for (mask in 0 until 8) {
            builder.applyMask(mask)
            builder.drawFormatBits(mask)
            val penalty = builder.penaltyScore()
            if (penalty < minPenalty) {
                minPenalty = penalty
                bestMask = mask
            }
            builder.applyMask(mask) // XOR again to undo
        }
        builder.applyMask(bestMask)
        builder.drawFormatBits(bestMask)
        return QrCode(builder.size, builder.modules)
    }

    /** Renders [code] with a 4-module quiet zone, scaled with whole pixels per module. */
    fun toBitmap(code: QrCode, targetSizePx: Int, dark: Int = Color.BLACK, light: Int = Color.WHITE): Bitmap {
        val border = 4
        val modules = code.size + border * 2
        val scale = maxOf(1, targetSizePx / modules)
        val sizePx = modules * scale
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val my = y / scale - border
            for (x in 0 until sizePx) {
                val mx = x / scale - border
                val isDark = mx in 0 until code.size && my in 0 until code.size && code.isDark(mx, my)
                pixels[y * sizePx + x] = if (isDark) dark else light
            }
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.RGB_565)
    }

    // ---------------------------------------------------------------- tables

    internal fun charCountBits(version: Int): Int = if (version <= 9) 8 else 16

    internal fun numRawDataModules(version: Int): Int {
        var result = (16 * version + 128) * version + 64
        if (version >= 2) {
            val numAlign = version / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if (version >= 7) result -= 36
        }
        return result
    }

    internal fun numDataCodewords(version: Int): Int =
        numRawDataModules(version) / 8 -
            ECC_CODEWORDS_PER_BLOCK[version] * NUM_ERROR_CORRECTION_BLOCKS[version]

    internal fun alignmentPatternPositions(version: Int): IntArray {
        if (version == 1) return IntArray(0)
        val numAlign = version / 7 + 2
        val size = version * 4 + 17
        val step = (version * 8 + numAlign * 3 + 5) / (numAlign * 4 - 4) * 2
        val result = IntArray(numAlign)
        result[0] = 6
        var pos = size - 7
        for (i in numAlign - 1 downTo 1) {
            result[i] = pos
            pos -= step
        }
        return result
    }

    internal fun formatBits(mask: Int): Int {
        val data = (ECC_FORMAT_BITS_M shl 3) or mask
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        return ((data shl 10) or rem) xor 0x5412
    }

    internal fun versionBits(version: Int): Int {
        var rem = version
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        return (version shl 12) or rem
    }

    // ---------------------------------------------------------- Reed-Solomon

    internal fun addEccAndInterleave(version: Int, data: ByteArray): ByteArray {
        val numBlocks = NUM_ERROR_CORRECTION_BLOCKS[version]
        val blockEccLen = ECC_CODEWORDS_PER_BLOCK[version]
        val rawCodewords = numRawDataModules(version) / 8
        val numShortBlocks = numBlocks - rawCodewords % numBlocks
        val shortBlockLen = rawCodewords / numBlocks

        val divisor = reedSolomonDivisor(blockEccLen)
        val blocks = ArrayList<ByteArray>(numBlocks)
        var k = 0
        for (i in 0 until numBlocks) {
            val datLen = shortBlockLen - blockEccLen + if (i < numShortBlocks) 0 else 1
            val dat = data.copyOfRange(k, k + datLen)
            k += datLen
            val ecc = reedSolomonRemainder(dat, divisor)
            val block = ByteArray(shortBlockLen + 1)
            System.arraycopy(dat, 0, block, 0, dat.size)
            // Short blocks keep a placeholder byte at index datLen, skipped below.
            System.arraycopy(ecc, 0, block, block.size - ecc.size, ecc.size)
            blocks += block
        }
        val result = ByteArray(rawCodewords)
        var index = 0
        for (i in 0..shortBlockLen) {
            for (j in blocks.indices) {
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                    result[index++] = blocks[j][i]
                }
            }
        }
        check(index == rawCodewords)
        return result
    }

    internal fun reedSolomonDivisor(degree: Int): ByteArray {
        val result = ByteArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = gfMultiply(result[j].toInt() and 0xFF, root).toByte()
                if (j + 1 < degree) result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
            }
            root = gfMultiply(root, 0x02)
        }
        return result
    }

    internal fun reedSolomonRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
        val result = ByteArray(divisor.size)
        for (b in data) {
            val factor = (b.toInt() xor result[0].toInt()) and 0xFF
            System.arraycopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (i in result.indices) {
                result[i] = (result[i].toInt() xor gfMultiply(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
        }
        return result
    }

    /** Multiplication in GF(2^8) modulo x^8 + x^4 + x^3 + x^2 + 1. */
    internal fun gfMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z and 0xFF
    }

    // --------------------------------------------------------------- drawing

    private class Builder(val version: Int) {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        fun set(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            isFunction[y][x] = true
        }

        fun drawFunctionPatterns() {
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            drawFinder(3, 3)
            drawFinder(size - 4, 3)
            drawFinder(3, size - 4)
            val positions = alignmentPatternPositions(version)
            val n = positions.size
            for (i in 0 until n) {
                for (j in 0 until n) {
                    val corner = (i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)
                    if (!corner) drawAlignment(positions[i], positions[j])
                }
            }
            drawFormatBits(0) // reserve; overwritten later
            drawVersion()
        }

        private fun drawFinder(x: Int, y: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until size && yy in 0 until size) set(xx, yy, dist != 2 && dist != 4)
            }
        }

        private fun drawAlignment(x: Int, y: Int) {
            for (dy in -2..2) for (dx in -2..2) {
                set(x + dx, y + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
            }
        }

        fun drawFormatBits(mask: Int) {
            val bits = formatBits(mask)
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true) // dark module
        }

        private fun drawVersion() {
            if (version < 7) return
            val bits = versionBits(version)
            for (i in 0 until 18) {
                val bit = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, bit)
                set(b, a, bit)
            }
        }

        fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0 until 2) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!isFunction[y][x] && i < data.size * 8) {
                            modules[y][x] = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                            i++
                        }
                    }
                }
                right -= 2
            }
            check(i == data.size * 8)
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
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
                if (invert && !isFunction[y][x]) modules[y][x] = !modules[y][x]
            }
        }

        /** Simplified ISO penalty (rules N1-N4); only used to rank the masks. */
        fun penaltyScore(): Int {
            var result = 0
            for (line in 0 until 2) {
                for (a in 0 until size) {
                    var runColor = false
                    var runLength = 0
                    for (b in 0 until size) {
                        val color = if (line == 0) modules[a][b] else modules[b][a]
                        if (b > 0 && color == runColor) {
                            runLength++
                            if (runLength == 5) result += 3 else if (runLength > 5) result++
                        } else {
                            runColor = color
                            runLength = 1
                        }
                    }
                    // Finder-like 1:1:3:1:1 with 4 light modules on one side.
                    for (b in 0..size - 11) {
                        fun at(o: Int) = if (line == 0) modules[a][b + o] else modules[b + o][a]
                        val core = at(0) && !at(1) && at(2) && at(3) && at(4) && !at(5) && at(6)
                        if (core && !at(7) && !at(8) && !at(9) && !at(10)) result += 40
                        val core2 = at(4) && !at(5) && at(6) && at(7) && at(8) && !at(9) && at(10)
                        if (core2 && !at(0) && !at(1) && !at(2) && !at(3)) result += 40
                    }
                }
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3
            }
            var dark = 0
            modules.forEach { row -> row.forEach { if (it) dark++ } }
            val total = size * size
            val k = ((kotlin.math.abs(dark * 20L - total * 10L) + total - 1) / total).toInt() - 1
            result += k * 10
            return result
        }
    }

    private class BitBuffer {
        private val bits = ArrayList<Boolean>(1024)
        val size: Int get() = bits.size
        operator fun get(index: Int): Boolean = bits[index]
        fun append(value: Int, length: Int) {
            for (i in length - 1 downTo 0) bits += ((value ushr i) and 1) != 0
        }
    }
}
