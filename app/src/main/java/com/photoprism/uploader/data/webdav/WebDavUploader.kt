package com.photoprism.uploader.data.webdav

import android.content.ContentResolver
import android.net.Uri
import com.photoprism.uploader.domain.model.ServerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Handles individual file uploads to WebDAV server.
 */
class WebDavUploader(private val contentResolver: ContentResolver) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Upload a single file to the WebDAV server.
     *
     * @param contentUri The content:// URI of the file to upload
     * @param remoteUrl The full URL to upload to (base + encoded filename)
     * @param settings Server connection settings
     * @return Result with success or error message
     */
    suspend fun uploadFile(
        contentUri: Uri,
        remoteUrl: String,
        settings: ServerSettings
    ): UploadResult = withContext(Dispatchers.IO) {
        try {
            val inputStream = contentResolver.openInputStream(contentUri)
                ?: return@withContext UploadResult.Failure("Cannot open file")

            val mimeType = contentResolver.getType(contentUri) ?: "application/octet-stream"
            val mediaType = mimeType.toMediaTypeOrNull()

            val requestBody = object : RequestBody() {
                override fun contentType() = mediaType

                override fun writeTo(sink: BufferedSink) {
                    inputStream.use { stream ->
                        sink.writeAll(stream.source())
                    }
                }
            }

            val requestBuilder = Request.Builder()
                .url(remoteUrl)
                .put(requestBody)

            // Add Basic Auth if username is provided
            if (settings.username.isNotBlank()) {
                val credential = Credentials.basic(settings.username, settings.password)
                requestBuilder.header("Authorization", credential)
            }

            val request = requestBuilder.build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful || response.code == 201 || response.code == 204) {
                UploadResult.Success
            } else {
                UploadResult.Failure("HTTP ${response.code}: ${response.message}")
            }
        } catch (e: IOException) {
            UploadResult.Failure("Network error: ${e.message}")
        } catch (e: Exception) {
            UploadResult.Failure("Error: ${e.message}")
        }
    }

    sealed class UploadResult {
        data object Success : UploadResult()
        data class Failure(val error: String) : UploadResult()
    }
}
