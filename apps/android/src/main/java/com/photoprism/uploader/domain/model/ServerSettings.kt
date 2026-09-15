package com.photoprism.uploader.domain.model

import com.photoprism.uploader.BuildConfig

/**
 * WebDAV server connection settings.
 */
data class ServerSettings(
    val baseUrl: String,
    val username: String,
    val password: String
) {
    companion object {
        val DEFAULT = ServerSettings(
            baseUrl = BuildConfig.DEFAULT_SERVER_URL,
            username = BuildConfig.DEFAULT_USERNAME,
            password = ""
        )
    }
}
