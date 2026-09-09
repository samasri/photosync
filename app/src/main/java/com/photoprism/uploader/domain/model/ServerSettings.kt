package com.photoprism.uploader.domain.model

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
            baseUrl = "https://photoprism.example.com/import/",
            username = "admin",
            password = ""
        )
    }
}
