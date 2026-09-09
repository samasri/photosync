package com.photoprism.duplicatestudy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateMathTest {
    @Test
    fun hashesMatchPythonPrototypeForSyntheticPixels() {
        val fingerprint = DuplicateMath.fingerprint(patternedPixels())

        assertEquals(0x052b552a55ab55abL, fingerprint.phash)
        assertEquals(0x0000000008010001L, fingerprint.dhash)
    }

    @Test
    fun identicalFingerprintsMatch() {
        val pixels = patternedPixels()
        val score = DuplicateMath.compare(
            DuplicateMath.fingerprint(pixels),
            DuplicateMath.fingerprint(pixels),
        )

        assertEquals(0, score.phashDistance)
        assertEquals(0, score.dhashDistance)
        assertEquals(1.0, score.correlation, 0.0)
        assertTrue(score.likelyDuplicate)
    }

    @Test
    fun brightnessOffsetPreservesStructure() {
        val left = patternedPixels(maximum = 180)
        val right = ByteArray(left.size) { ((left[it].toInt() and 0xff) + 40).toByte() }
        val score = DuplicateMath.compare(
            DuplicateMath.fingerprint(left),
            DuplicateMath.fingerprint(right),
        )

        assertEquals(0, score.phashDistance)
        assertEquals(0, score.dhashDistance)
        assertEquals(1.0, score.correlation, 1e-12)
        assertTrue(score.likelyDuplicate)
    }

    @Test
    fun reversedStructureDoesNotMatch() {
        val left = patternedPixels()
        val right = left.reversedArray()
        val score = DuplicateMath.compare(
            DuplicateMath.fingerprint(left),
            DuplicateMath.fingerprint(right),
        )

        assertFalse(score.likelyDuplicate)
    }

    private fun patternedPixels(maximum: Int = 255): ByteArray {
        return ByteArray(DuplicateMath.PIXEL_COUNT) { index ->
            val x = index % DuplicateMath.FINGERPRINT_SIZE
            val y = index / DuplicateMath.FINGERPRINT_SIZE
            ((x * 3 + y * 5 + (x * y) % 17) % (maximum + 1)).toByte()
        }
    }
}
