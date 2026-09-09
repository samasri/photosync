package com.photoprism.uploader.data.webdav

import android.content.ContentResolver
import android.net.Uri
import com.photoprism.uploader.domain.model.ServerSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.util.concurrent.TimeUnit

class WebDavUploader(private val contentResolver: ContentResolver) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(2, TimeUnit.MINUTES)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(3, TimeUnit.MINUTES).build()

    suspend fun uploadFile(contentUri: Uri, remoteUrl: String, settings: ServerSettings): UploadResult =
        withContext(Dispatchers.IO) {
            try {
                val requestBody = object : RequestBody() {
                    override fun contentType() =
                        (contentResolver.getType(contentUri) ?: "application/octet-stream").toMediaTypeOrNull()
                    override fun writeTo(sink: BufferedSink) {
                        // Open per attempt, including OkHttp transport retries, and always close.
                        val input = contentResolver.openInputStream(contentUri) ?: throw IOException("Missing photo")
                        input.use { sink.writeAll(it.source()) }
                    }
                }
                val builder = Request.Builder().url(remoteUrl).put(requestBody)
                if (settings.username.isNotBlank()) {
                    builder.header("Authorization", Credentials.basic(settings.username, settings.password))
                }
                client.newCall(builder.build()).execute().use { response ->
                    if (response.isSuccessful) UploadResult.Success
                    else UploadResult.Failure(when (response.code) {
                        401, 403 -> "HTTP ${response.code}: Check your PhotoPrism credentials and WebDAV access in Settings."
                        else -> "HTTP ${response.code}: Upload was not accepted by the server."
                    }, response.code in listOf(401, 403, 408, 429) || response.code >= 500)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                UploadResult.Failure("Cannot reach the server or read the saved photo. Will retry later.", true)
            } catch (_: Exception) {
                UploadResult.Failure("Could not upload. Check the server URL and saved photo.", true)
            }
        }

    sealed class UploadResult {
        data object Success : UploadResult()
        data class Failure(val error: String, val retryLater: Boolean = false) : UploadResult()
    }
}
