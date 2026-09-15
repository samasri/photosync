package com.photoprism.uploader.lab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import com.photoprism.uploader.BuildConfig
import com.photoprism.uploader.domain.model.Album
import com.photoprism.uploader.domain.model.MediaImage
import java.io.File

/** App-private fixtures only. Never inserts into or queries the phone's MediaStore. */
class SyntheticLibrary(context: Context) {
    private val root = File(context.filesDir, "synthetic-library")
    init { check(BuildConfig.BUILD_TYPE == "experiment") }

    @Synchronized fun seed() {
        if (File(root, ".seeded").exists()) return
        for (i in 1..4) create("camera", "SYNTHETIC-CAMERA-$i.jpg", i)
        for (i in 1..6) create("whatsapp", "IMG-20260901-WA${i.toString().padStart(4, '0')}.jpg", i + 8)
        File(root, ".seeded").writeText("1")
    }

    @Synchronized fun addCamera(): File {
        seed()
        val number = (File(root, "camera").listFiles()?.size ?: 0) + 1
        return create("camera", "SYNTHETIC-CAMERA-$number.jpg", number)
    }

    private fun create(bucket: String, name: String, number: Int): File {
        val folder = File(root, bucket).apply { mkdirs() }
        val file = File(folder, name)
        val bitmap = Bitmap.createBitmap(720, 960, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.HSVToColor(floatArrayOf((number * 47 % 360).toFloat(), .55f, .7f)))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 48f }
        canvas.drawCircle(360f, 380f, 130f, paint)
        paint.color = Color.DKGRAY
        canvas.drawText("SYNTHETIC $number", 100f, 650f, paint)
        val temp = File.createTempFile(".synthetic-", ".tmp", folder)
        temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        check(temp.renameTo(file))
        return file
    }

    @Synchronized fun files(): List<File> {
        seed()
        return File(root, "camera").listFiles()?.filter { it.extension == "jpg" }?.sortedBy { it.name } ?: emptyList()
    }

    @Synchronized fun images(bucket: String): List<MediaImage> {
        seed()
        if (bucket !in listOf("camera", "whatsapp")) return emptyList()
        return File(root, bucket).listFiles()?.filter { it.extension == "jpg" }?.sortedBy { it.name }?.map {
            MediaImage((it.name.hashCode().toLong() and 0xffffffffL), Uri.fromFile(it), it.name, it.length(), bucket, 1788220800L)
        } ?: emptyList()
    }

    fun albums(): List<Album> = listOf("whatsapp" to "WhatsApp Images", "camera" to "Camera").map { (id, name) ->
        val images = images(id)
        Album(id, name, images.size, images.firstOrNull()?.contentUri)
    }
}
