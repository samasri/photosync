package com.photoprism.duplicatestudy

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class DetectorContractTest {
    @Test
    fun labelledImagePairsMatchContract() {
        val fixtureRootValue = System.getenv("DUPLICATE_STUDY_FIXTURES")
        assumeTrue("DUPLICATE_STUDY_FIXTURES is not configured", !fixtureRootValue.isNullOrBlank())
        val fixtureRoot = Path.of(fixtureRootValue)
        val casesFile = fixtureRoot.resolve("tests/duplicate-cases.tsv")
        assumeTrue("contract cases are unavailable", casesFile.exists())

        for (line in casesFile.readLines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val (caseId, expectedValue, leftValue, rightValue) = line.split('\t')
            val left = fingerprint(fixtureRoot.resolve(leftValue))
            val right = fingerprint(fixtureRoot.resolve(rightValue))
            val actual = DuplicateMath.compare(left, right).likelyDuplicate

            assertEquals(caseId, expectedValue.toBooleanStrict(), actual)
        }
    }

    private fun fingerprint(path: Path): DuplicateMath.Fingerprint {
        val process = ProcessBuilder(
            "magick",
            path.toString(),
            "-auto-orient",
            "-colorspace", "Gray",
            "-filter", "Lanczos",
            "-resize", "${DuplicateMath.FINGERPRINT_SIZE}x${DuplicateMath.FINGERPRINT_SIZE}!",
            "-depth", "8",
            "gray:-",
        ).start()
        val pixels = process.inputStream.readBytes()
        val error = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "normalizer failed for a contract fixture: $error" }
        check(pixels.size == DuplicateMath.PIXEL_COUNT) {
            "normalizer returned ${pixels.size} bytes instead of ${DuplicateMath.PIXEL_COUNT}"
        }
        return DuplicateMath.fingerprint(pixels)
    }
}
