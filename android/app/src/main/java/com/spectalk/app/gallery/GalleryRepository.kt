package com.spectalk.app.gallery

import android.content.Context
import java.io.File

data class GalleryImage(
    val file: File,
    val timestamp: Long,
    val source: String, // "glasses" or "camera"
)

object GalleryRepository {
    private const val GALLERY_DIR = "gallery"

    private fun galleryDir(context: Context): File =
        File(context.filesDir, GALLERY_DIR).also { it.mkdirs() }

    fun saveImage(context: Context, jpegBytes: ByteArray, source: String): File {
        val file = File(galleryDir(context), "${source}_${System.currentTimeMillis()}.jpg")
        file.writeBytes(jpegBytes)
        return file
    }

    fun listImages(context: Context): List<GalleryImage> =
        galleryDir(context)
            .listFiles { f -> f.extension == "jpg" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                GalleryImage(
                    file = file,
                    timestamp = file.lastModified(),
                    source = if (file.name.startsWith("glasses")) "glasses" else "camera",
                )
            }
            ?: emptyList()

    fun deleteImage(file: File) { file.delete() }
}
