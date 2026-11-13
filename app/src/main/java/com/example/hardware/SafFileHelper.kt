package com.example.hardware

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.example.data.model.FileCategory
import com.example.data.model.SharedFile
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PreparedSafFile(
  val fileName: String,
  val mimeType: String,
  val sizeBytes: Long,
  val category: FileCategory,
  val thumbnailBase64: String?,
  val textPreview: String?,
  val localUriString: String,
)

object SafFileHelper {

  suspend fun inspectUri(context: Context, uri: Uri): PreparedSafFile = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    var displayName = "authorized_file"
    var sizeBytes = 0L

    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) sizeBytes = cursor.getLong(sizeIdx)
      }
    }

    val category = when {
      mime.startsWith("image/") -> FileCategory.PHOTO
      mime.startsWith("video/") -> FileCategory.VIDEO
      else -> FileCategory.DOCUMENT
    }

    var thumbB64: String? = null
    var textSnippet: String? = null

    when (category) {
      FileCategory.PHOTO -> {
        thumbB64 = decodeScaledImageToBase64(context, uri)
      }
      FileCategory.VIDEO -> {
        thumbB64 = extractVideoThumbnailBase64(context, uri)
      }
      FileCategory.DOCUMENT -> {
        if (mime.startsWith("text/") || displayName.endsWith(".txt", true) || displayName.endsWith(".csv", true) || displayName.endsWith(".json", true)) {
          textSnippet = runCatching {
            resolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
              val buffer = CharArray(3000)
              val read = reader.read(buffer)
              if (read > 0) String(buffer, 0, read) else null
            }
          }.getOrNull()
        } else {
          textSnippet = "Document authorized via Storage Access Framework ($mime, ${formatFileSize(sizeBytes)})."
        }
      }
    }

    PreparedSafFile(
      fileName = displayName.take(120),
      mimeType = mime.take(80),
      sizeBytes = sizeBytes,
      category = category,
      thumbnailBase64 = thumbB64,
      textPreview = textSnippet?.take(4000),
      localUriString = uri.toString().take(500),
    )
  }

  suspend fun exportSharedFileToUri(context: Context, file: SharedFile, destUri: Uri): Boolean = withContext(Dispatchers.IO) {
    runCatching {
      val resolver = context.contentResolver
      // If original local URI is still accessible on this device, copy full bytes; otherwise export preview/image payload
      val copiedFromLocal = file.localUriString?.let { srcStr ->
        runCatching {
          resolver.openInputStream(Uri.parse(srcStr))?.use { input ->
            resolver.openOutputStream(destUri)?.use { output ->
              input.copyTo(output)
              true
            } ?: false
          } ?: false
        }.getOrDefault(false)
      } ?: false

      if (!copiedFromLocal) {
        resolver.openOutputStream(destUri)?.use { output ->
          val thumb = file.thumbnailBase64
          if (!thumb.isNullOrBlank()) {
            output.write(Base64.decode(thumb, Base64.DEFAULT))
          } else {
            val content = buildString {
              appendLine("SafeNest Authorized File Export")
              appendLine("File: ${file.fileName}")
              appendLine("MIME: ${file.mimeType}")
              appendLine("Size: ${formatFileSize(file.sizeBytes)}")
              appendLine()
              appendLine(file.textPreview ?: "Metadata-only export (original binary resides on sender device).")
            }
            output.write(content.toByteArray(Charsets.UTF_8))
          }
        }
      }
      true
    }.getOrDefault(false)
  }

  private fun decodeScaledImageToBase64(context: Context, uri: Uri): String? = runCatching {
    val resolver = context.contentResolver
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    var sample = 1
    while (opts.outWidth / sample > 480 || opts.outHeight / sample > 480) {
      sample *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) } ?: return null
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 72, out)
    bmp.recycle()
    val bytes = out.toByteArray()
    if (bytes.size > 90_000) null else Base64.encodeToString(bytes, Base64.NO_WRAP)
  }.getOrNull()

  private fun extractVideoThumbnailBase64(context: Context, uri: Uri): String? = runCatching {
    val retriever = MediaMetadataRetriever()
    retriever.setDataSource(context, uri)
    val frame = retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    retriever.release()
    if (frame != null) {
      val scaled = Bitmap.createScaledBitmap(frame, 360, (360f * frame.height / frame.width.coerceAtLeast(1)).toInt().coerceAtLeast(1), true)
      if (scaled !== frame) frame.recycle()
      val out = ByteArrayOutputStream()
      scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
      scaled.recycle()
      val bytes = out.toByteArray()
      if (bytes.size <= 90_000) Base64.encodeToString(bytes, Base64.NO_WRAP) else null
    } else null
  }.getOrNull()

  fun formatFileSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(java.util.Locale.US, "%.1f MB", mb)
  }
}
