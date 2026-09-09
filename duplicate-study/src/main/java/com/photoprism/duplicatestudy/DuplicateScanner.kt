package com.photoprism.duplicatestudy

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

class DuplicateScanner(
    private val resolver: ContentResolver,
    private val cache: FingerprintCache,
) {
    data class Progress(
        val phase: String,
        val completed: Int,
        val total: Int,
        val matches: Int = 0,
        val failures: Int = 0,
    )

    data class Result(
        val referenceCount: Int,
        val candidateCount: Int,
        val matchCount: Int,
        val pairCount: Int,
        val failures: Int,
        val durationMillis: Long,
    )

    private data class MediaRecord(
        val id: Long,
        val uri: Uri?,
        val file: File?,
        val size: Long,
        val modifiedAt: Long,
        val relativePath: String,
    )

    private data class IdentifiedFingerprint(
        val id: Long,
        val fingerprint: DuplicateMath.Fingerprint,
    )

    suspend fun scan(onProgress: (Progress) -> Unit): Result = withContext(Dispatchers.IO) {
        lateinit var result: Result
        val duration = measureTimeMillis {
            val mediaStoreRecords = queryImages()
            val directSentRecords = queryDirectSentImages()
            val records = mediaStoreRecords + directSentRecords
            val references = records.filter { it.relativePath.startsWith("DCIM/", ignoreCase = true) }
            val candidates = records.filter { isWhatsAppImagePath(it.relativePath) }
            Log.i(
                TAG,
                "scan-start referenceCount=${references.size} candidateCount=${candidates.size} " +
                    "directSentCount=${directSentRecords.size}",
            )

            var failures = 0
            val referenceFingerprints = fingerprintAll("reference", references, onProgress) {
                failures++
            }
            val candidateFingerprints = fingerprintAll("candidate", candidates, onProgress) {
                failures++
            }

            var matches = 0
            val matchedCandidateIds = mutableSetOf<Long>()
            var comparisons = 0L
            for ((candidateIndex, candidate) in candidateFingerprints.withIndex()) {
                for (reference in referenceFingerprints) {
                    comparisons++
                    val score = DuplicateMath.compare(candidate.fingerprint, reference.fingerprint)
                    if (score.likelyDuplicate) {
                        matches++
                        matchedCandidateIds += candidate.id
                        Log.i(
                            TAG,
                            "match candidateId=${candidate.id} referenceId=${reference.id} " +
                                "p=${score.phashDistance} d=${score.dhashDistance} " +
                                "corr=${"%.6f".format(java.util.Locale.US, score.correlation)}",
                        )
                    }
                }
                if ((candidateIndex + 1) % 25 == 0 || candidateIndex + 1 == candidateFingerprints.size) {
                    onProgress(
                        Progress(
                            phase = "compare",
                            completed = candidateIndex + 1,
                            total = candidateFingerprints.size,
                            matches = matches,
                            failures = failures,
                        )
                    )
                }
            }
            Log.i(
                TAG,
                "comparison-complete comparisons=$comparisons pairMatches=$matches " +
                    "duplicateCandidates=${matchedCandidateIds.size}",
            )
            result = Result(
                references.size,
                candidates.size,
                matchedCandidateIds.size,
                matches,
                failures,
                0,
            )
        }
        result.copy(durationMillis = duration).also {
            Log.i(
                TAG,
                "scan-complete referenceCount=${it.referenceCount} candidateCount=${it.candidateCount} " +
                    "duplicateCandidates=${it.matchCount} pairMatches=${it.pairCount} " +
                    "failures=${it.failures} durationMs=${it.durationMillis}",
            )
        }
    }

    private fun queryImages(): List<MediaRecord> {
        val result = mutableListOf<MediaRecord>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        resolver.query(collection, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                result += MediaRecord(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    file = null,
                    size = cursor.getLong(sizeColumn),
                    modifiedAt = cursor.getLong(modifiedColumn),
                    relativePath = cursor.getString(pathColumn).orEmpty(),
                )
            }
        }
        return result
    }

    private fun queryDirectSentImages(): List<MediaRecord> {
        val sentDirectory = File(
            "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent"
        )
        if (!sentDirectory.isDirectory) return emptyList()
        return sentDirectory.walkTopDown()
            .filter { it.isFile && it.extension.lowercase(java.util.Locale.ROOT) in IMAGE_EXTENSIONS }
            .map { file ->
                MediaRecord(
                    id = stableDirectFileId(file.absolutePath),
                    uri = null,
                    file = file,
                    size = file.length(),
                    modifiedAt = file.lastModified() / 1000,
                    relativePath = "WhatsApp Images/Sent/",
                )
            }
            .toList()
    }

    private fun fingerprintAll(
        phase: String,
        records: List<MediaRecord>,
        onProgress: (Progress) -> Unit,
        onFailure: () -> Unit,
    ): List<IdentifiedFingerprint> {
        val result = ArrayList<IdentifiedFingerprint>(records.size)
        for ((index, record) in records.withIndex()) {
            try {
                val fingerprint = cache.get(record.id, record.size, record.modifiedAt)
                    ?: decodeFingerprint(record).also {
                        cache.put(record.id, record.size, record.modifiedAt, it)
                    }
                result += IdentifiedFingerprint(record.id, fingerprint)
            } catch (error: Exception) {
                onFailure()
                Log.w(TAG, "fingerprint-failed mediaId=${record.id} type=${error.javaClass.simpleName}")
            }
            if ((index + 1) % 25 == 0 || index + 1 == records.size) {
                onProgress(Progress(phase, index + 1, records.size))
                Log.i(TAG, "fingerprint-progress phase=$phase completed=${index + 1} total=${records.size}")
            }
        }
        return result
    }

    private fun decodeFingerprint(record: MediaRecord): DuplicateMath.Fingerprint {
        val source = record.file?.let(ImageDecoder::createSource)
            ?: ImageDecoder.createSource(resolver, requireNotNull(record.uri))
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(DuplicateMath.FINGERPRINT_SIZE, DuplicateMath.FINGERPRINT_SIZE)
        }
        return bitmap.useAsFingerprint()
    }

    private fun Bitmap.useAsFingerprint(): DuplicateMath.Fingerprint {
        try {
            val colors = IntArray(DuplicateMath.PIXEL_COUNT)
            getPixels(
                colors,
                0,
                DuplicateMath.FINGERPRINT_SIZE,
                0,
                0,
                DuplicateMath.FINGERPRINT_SIZE,
                DuplicateMath.FINGERPRINT_SIZE,
            )
            val gray = ByteArray(colors.size)
            for (index in colors.indices) {
                val color = colors[index]
                val alpha = color ushr 24 and 0xff
                val red = color ushr 16 and 0xff
                val green = color ushr 8 and 0xff
                val blue = color and 0xff
                val luminance = (77 * red + 150 * green + 29 * blue) ushr 8
                // Composite transparency over white for a deterministic result.
                gray[index] = ((luminance * alpha + 255 * (255 - alpha)) / 255).toByte()
            }
            return DuplicateMath.fingerprint(gray)
        } finally {
            recycle()
        }
    }

    private fun isWhatsAppImagePath(path: String): Boolean {
        val normalized = path.lowercase(java.util.Locale.ROOT)
        // Match the prototype's recursive scan of the complete WhatsApp Images
        // tree. Sent copies are especially important: they commonly originate
        // in DCIM and are re-encoded by WhatsApp.
        return normalized.contains("whatsapp") && normalized.contains("images")
    }

    private fun stableDirectFileId(path: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (digest[index].toLong() and 0xff)
        }
        return value or Long.MIN_VALUE
    }

    private companion object {
        const val TAG = "DuplicateStudy"
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "dng")
    }
}
