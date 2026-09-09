package com.photoprism.duplicatestudy

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

object DuplicateMath {
    const val FINGERPRINT_SIZE = 32
    const val PIXEL_COUNT = FINGERPRINT_SIZE * FINGERPRINT_SIZE
    const val PHASH_THRESHOLD = 10
    const val DHASH_THRESHOLD = 12
    const val CORRELATION_THRESHOLD = 0.90

    private val cosine = Array(8) { frequency ->
        DoubleArray(FINGERPRINT_SIZE) { position ->
            cos((2 * position + 1) * frequency * Math.PI / (2 * FINGERPRINT_SIZE))
        }
    }

    data class Fingerprint(
        val pixels: ByteArray,
        val phash: Long,
        val dhash: Long,
    )

    data class Score(
        val phashDistance: Int,
        val dhashDistance: Int,
        val correlation: Double,
    ) {
        val likelyDuplicate: Boolean
            get() = phashDistance <= PHASH_THRESHOLD &&
                dhashDistance <= DHASH_THRESHOLD &&
                correlation >= CORRELATION_THRESHOLD
    }

    fun fingerprint(pixels: ByteArray): Fingerprint {
        require(pixels.size == PIXEL_COUNT)
        return Fingerprint(pixels.copyOf(), perceptualHash(pixels), differenceHash(pixels))
    }

    fun compare(left: Fingerprint, right: Fingerprint): Score {
        val phashDistance = java.lang.Long.bitCount(left.phash xor right.phash)
        val dhashDistance = java.lang.Long.bitCount(left.dhash xor right.dhash)
        val correlation = if (
            phashDistance <= PHASH_THRESHOLD && dhashDistance <= DHASH_THRESHOLD
        ) {
            pixelCorrelation(left.pixels, right.pixels)
        } else {
            -1.0
        }
        return Score(phashDistance, dhashDistance, correlation)
    }

    fun perceptualHash(pixels: ByteArray): Long {
        require(pixels.size == PIXEL_COUNT)

        // A separable 2-D DCT computes the same low-frequency coefficients as
        // the prototype while doing substantially less work.
        val horizontal = Array(FINGERPRINT_SIZE) { DoubleArray(8) }
        for (y in 0 until FINGERPRINT_SIZE) {
            val row = y * FINGERPRINT_SIZE
            for (u in 0 until 8) {
                var value = 0.0
                for (x in 0 until FINGERPRINT_SIZE) {
                    value += pixels[row + x].unsigned() * cosine[u][x]
                }
                horizontal[y][u] = value
            }
        }

        val coefficients = DoubleArray(64)
        for (v in 0 until 8) {
            for (u in 0 until 8) {
                var value = 0.0
                for (y in 0 until FINGERPRINT_SIZE) {
                    value += horizontal[y][u] * cosine[v][y]
                }
                coefficients[v * 8 + u] = value
            }
        }

        val nonDc = coefficients.copyOfRange(1, coefficients.size).sortedArray()
        val median = nonDc[nonDc.size / 2]
        var result = 0L
        for (index in 1 until coefficients.size) {
            result = (result shl 1) or if (coefficients[index] > median) 1L else 0L
        }
        return result
    }

    fun differenceHash(pixels: ByteArray): Long {
        require(pixels.size == PIXEL_COUNT)
        val xs = IntArray(9) { (it * (FINGERPRINT_SIZE - 1) / 8.0).roundToInt() }
        val ys = IntArray(8) { (it * (FINGERPRINT_SIZE - 1) / 7.0).roundToInt() }
        var result = 0L
        for (y in ys) {
            val row = y * FINGERPRINT_SIZE
            for (index in 0 until 8) {
                val bit = pixels[row + xs[index]].unsigned() >
                    pixels[row + xs[index + 1]].unsigned()
                result = (result shl 1) or if (bit) 1L else 0L
            }
        }
        return result
    }

    fun pixelCorrelation(left: ByteArray, right: ByteArray): Double {
        require(left.size == right.size && left.isNotEmpty())
        val leftMean = left.sumOf { it.unsigned().toLong() }.toDouble() / left.size
        val rightMean = right.sumOf { it.unsigned().toLong() }.toDouble() / right.size
        var numerator = 0.0
        var leftSquared = 0.0
        var rightSquared = 0.0
        for (index in left.indices) {
            val leftDelta = left[index].unsigned() - leftMean
            val rightDelta = right[index].unsigned() - rightMean
            numerator += leftDelta * rightDelta
            leftSquared += leftDelta * leftDelta
            rightSquared += rightDelta * rightDelta
        }
        val denominator = sqrt(leftSquared * rightSquared)
        return if (denominator == 0.0) {
            if (left.contentEquals(right)) 1.0 else 0.0
        } else {
            numerator / denominator
        }
    }

    private fun Byte.unsigned(): Int = toInt() and 0xff
}
