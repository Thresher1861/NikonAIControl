package ru.dimar.nikonaicontrol.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.util.*

class GallerySaver {
    companion object {
        fun saveImageToGallery(context: Context, imageBytes: ByteArray, fileName: String): Uri? {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NikonAIControl")
            }

            val resolver = context.contentResolver
            val uri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            uri?.let {
                val outputStream = resolver.openOutputStream(it)
                imageBytes.forEach { byte -> outputStream?.write(byte.toInt()) }
                outputStream?.close()
            }

            return uri
        }

        // Placeholder for video saving functionality
        fun saveVideoFrameOrClip(context: Context, data: ByteArray, fileName: String): Uri? {
            // Implementation for video saving will go here
            return null
        }
    }
}