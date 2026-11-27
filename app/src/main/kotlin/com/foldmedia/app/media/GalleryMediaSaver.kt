package com.foldmedia.app.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes extracted images, Xiaomi HyperOS Motion Photos (`MVIMG_...jpg`), videos,
 * and audio files into Android MediaStore with atomic `IS_PENDING` handling.
 */
class GalleryMediaSaver(private val context: Context) {

    fun saveMotionPhotoBytes(motionJpegBytes: ByteArray, index: Int): SavedMediaResult {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        // Xiaomi Gallery specifically treats MVIMG_ prefix + MotionPhoto XMP as native Live Photo
        val fileName = "MVIMG_${timestamp}_$index.jpg"
        val relativeDir = "${Environment.DIRECTORY_DCIM}/Camera"
        val uri = insertAndWriteBytes(
            collection = imageCollection(),
            displayName = fileName,
            mimeType = "image/jpeg",
            relativePath = relativeDir,
            bytes = motionJpegBytes
        )
        return SavedMediaResult(uri = uri, displayPath = "$relativeDir/$fileName")
    }

    fun saveImageBytes(imageBytes: ByteArray, baseName: String, extensionHint: String? = null): SavedMediaResult {
        val (ext, mime) = detectImageType(imageBytes, extensionHint)
        val safeName = sanitizeFilename(baseName)
        val fileName = "${safeName}.$ext"
        val relativeDir = "${Environment.DIRECTORY_PICTURES}/FoldMedia"
        val uri = insertAndWriteBytes(
            collection = imageCollection(),
            displayName = fileName,
            mimeType = mime,
            relativePath = relativeDir,
            bytes = imageBytes
        )
        return SavedMediaResult(uri = uri, displayPath = "$relativeDir/$fileName")
    }

    fun saveVideoFile(sourceFile: File, baseName: String): SavedMediaResult {
        val ext = sourceFile.extension.lowercase().ifBlank { "mp4" }
        val mime = when (ext) {
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            else -> "video/mp4"
        }
        val fileName = "${sanitizeFilename(baseName)}.$ext"
        val relativeDir = "${Environment.DIRECTORY_MOVIES}/FoldMedia"
        val uri = insertAndWriteFile(
            collection = videoCollection(),
            displayName = fileName,
            mimeType = mime,
            relativePath = relativeDir,
            sourceFile = sourceFile
        )
        return SavedMediaResult(uri = uri, displayPath = "$relativeDir/$fileName")
    }

    fun saveAudioFile(sourceFile: File, baseName: String): SavedMediaResult {
        val ext = sourceFile.extension.lowercase().ifBlank { "m4a" }
        val mime = when (ext) {
            "mp3" -> "audio/mpeg"
            "opus" -> "audio/opus"
            "flac" -> "audio/flac"
            else -> "audio/mp4"
        }
        val fileName = "${sanitizeFilename(baseName)}.$ext"
        val relativeDir = "${Environment.DIRECTORY_MUSIC}/FoldMedia"
        val uri = insertAndWriteFile(
            collection = audioCollection(),
            displayName = fileName,
            mimeType = mime,
            relativePath = relativeDir,
            sourceFile = sourceFile
        )
        return SavedMediaResult(uri = uri, displayPath = "$relativeDir/$fileName")
    }

    private fun imageCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    private fun videoCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    private fun audioCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    private fun insertAndWriteBytes(
        collection: Uri,
        displayName: String,
        mimeType: String,
        relativePath: String,
        bytes: ByteArray
    ): Uri {
        val resolver = context.contentResolver
        val values = buildContentValues(displayName, mimeType, relativePath)
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("无法在系统媒体库创建文件: $displayName")
        try {
            resolver.openOutputStream(uri, "w")?.use { os ->
                os.write(bytes)
                os.flush()
            } ?: throw IllegalStateException("无法打开输出流写入: $displayName")
            clearPending(uri)
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    private fun insertAndWriteFile(
        collection: Uri,
        displayName: String,
        mimeType: String,
        relativePath: String,
        sourceFile: File
    ): Uri {
        val resolver = context.contentResolver
        val values = buildContentValues(displayName, mimeType, relativePath)
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("无法在系统媒体库创建文件: $displayName")
        try {
            resolver.openOutputStream(uri, "w")?.use { os ->
                FileInputStream(sourceFile).use { fis ->
                    fis.copyTo(os, bufferSize = 64 * 1024)
                }
                os.flush()
            } ?: throw IllegalStateException("无法打开输出流写入: $displayName")
            clearPending(uri)
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    private fun buildContentValues(displayName: String, mimeType: String, relativePath: String): ContentValues {
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
    }

    private fun clearPending(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, done, null, null)
        }
    }

    private fun detectImageType(bytes: ByteArray, hint: String?): Pair<String, String> {
        if (XiaomiMotionPhotoBuilder.isJpeg(bytes)) return "jpg" to "image/jpeg"
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte()
        ) {
            return "webp" to "image/webp"
        }
        if (bytes.size >= 8 &&
            (bytes[0].toInt() and 0xFF) == 0x89 &&
            bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        ) {
            return "png" to "image/png"
        }
        return when (hint?.lowercase()) {
            "png" -> "png" to "image/png"
            "webp" -> "webp" to "image/webp"
            else -> "jpg" to "image/jpeg"
        }
    }

    private fun sanitizeFilename(raw: String): String {
        val cleaned = raw.replace(Regex("""[\\/:*?"<>|\r\n]+"""), "_").trim('_', ' ')
        return cleaned.take(64).ifBlank { "foldmedia_${System.currentTimeMillis()}" }
    }

    data class SavedMediaResult(
        val uri: Uri,
        val displayPath: String
    )
}
