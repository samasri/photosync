package com.photoprism.uploader.data.webdav

import android.content.ContentResolver
import android.net.Uri
import com.photoprism.uploader.domain.model.ServerSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.util.concurrent.TimeUnit

class WebDavUploader(private val contentResolver: ContentResolver) {
    private val client = OkHttpClient.Builder()
        .followRedirects(com.photoprism.uploader.BuildConfig.BUILD_TYPE != "experiment")
        .followSslRedirects(com.photoprism.uploader.BuildConfig.BUILD_TYPE != "experiment")
        .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES).callTimeout(60, TimeUnit.MINUTES).build()

    suspend fun uploadFile(contentUri: Uri, remoteUrl: String, settings: ServerSettings): UploadResult =
        withContext(Dispatchers.IO) {
            try {
                if (com.photoprism.uploader.BuildConfig.BUILD_TYPE == "experiment") {
                    val url = remoteUrl.toHttpUrlOrNull()
                    require(url?.host == "127.0.0.1" || url?.host == "localhost") { "Lab uploads must use localhost" }
                }
                val file = java.io.File(requireNotNull(contentUri.path))
                require(contentUri.scheme == "file") { "Upload requires a saved queue file" }
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                val requestBody = object : RequestBody() {
                    override fun contentLength() = file.length()
                    override fun contentType() =
                        (contentResolver.getType(contentUri) ?: "application/octet-stream").toMediaTypeOrNull()
                    override fun writeTo(sink: BufferedSink) {
                        // Open per attempt, including OkHttp transport retries, and always close.
                        val input = contentResolver.openInputStream(contentUri) ?: throw IOException("Missing photo")
                        input.use { sink.writeAll(it.source()) }
                    }
                }
                val builder = Request.Builder().url(remoteUrl).put(requestBody)
                builder.header("Authorization", "Bearer ${settings.password}")
                builder.header("X-Content-SHA256", hash)
                client.newCall(builder.build()).execute().use { response ->
                    if (response.isSuccessful) UploadResult.Success
                    else UploadResult.Failure(when (response.code) {
                        401, 403 -> "HTTP ${response.code}: Check your delivery server token in Settings."
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
