package com.photoprism.uploader.domain.model

/**
 * Represents the current state of a sync operation.
 */
data class SyncProgress(
    val total: Int = 0,
    val uploaded: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val currentFileName: String? = null,
    val lastError: String? = null,
    val isRunning: Boolean = false,
    val isComplete: Boolean = false
) {
    val processed: Int get() = uploaded + skipped + failed
}
