package com.photoprism.uploader.data.webdav

import com.photoprism.uploader.domain.model.MediaImage
import java.net.URLEncoder

/**
 * Resolves collision-safe remote filenames for uploads.
 *
 * Strategy: Always use "${baseName}-${mediaStoreId}.${ext}"
 * Example: IMG_20250101.jpg with ID 12345 -> IMG_20250101-12345.jpg
 *
 * This ensures:
 * - No filename collisions between different images
 * - Deterministic naming (same image always gets same remote name)
 * - Original filename is still recognizable
 */
class FileNameResolver {

    /**
     * Generate a collision-safe remote filename for an image.
     */
    fun resolveRemoteName(image: MediaImage): String {
        val displayName = image.displayName
        val dotIndex = displayName.lastIndexOf('.')

        return if (dotIndex > 0) {
            val baseName = displayName.substring(0, dotIndex)
            val extension = displayName.substring(dotIndex + 1)
            "$baseName-${image.id}.$extension"
        } else {
            "$displayName-${image.id}"
        }
    }

    /**
     * URL-encode a filename for use in HTTP requests.
     */
    fun encodeForUrl(filename: String): String {
        return URLEncoder.encode(filename, "UTF-8")
            .replace("+", "%20")
    }
}
