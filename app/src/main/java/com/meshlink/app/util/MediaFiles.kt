package com.meshlink.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/**
 * Turns a photo or document picked by the user into a file inside the app's
 * private storage, so a message keeps working after the original content URI
 * (or the camera's temporary file) is gone.
 *
 * Photos are downscaled and re-compressed before they enter the mesh: a raw
 * 12 MP camera shot is several megabytes, which would take many minutes over
 * Bluetooth LE. 1024 px / JPEG 72 looks fine on a phone and is roughly
 * 80–200 KB.
 */
object MediaFiles {

    private const val MAX_DIMENSION = 1024
    private const val JPEG_QUALITY = 72

    /** Name shown to the user (gallery picks rarely carry a useful one). */
    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        val name = c.getString(index)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
    }

    fun mimeType(context: Context, uri: Uri, fallback: String = "application/octet-stream"): String =
        runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: fallback

    /**
     * Decodes a picked image, scales it down and saves it as JPEG in app
     * storage. Returns null when the URI is not a readable image.
     */
    fun importImage(context: Context, uri: Uri): File? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / sample > MAX_DIMENSION * 2 ||
                bounds.outHeight / sample > MAX_DIMENSION * 2
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            // Camera and gallery photos often store rotation in EXIF only;
            // BitmapFactory ignores it, so apply it before re-compressing.
            saveJpeg(context, upright(bitmap, orientationOf(context, uri)))
        }.getOrNull()
    }

    /** Same as [importImage] but for a file the camera already wrote. */
    fun compressImageFile(context: Context, source: File): File? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / sample > MAX_DIMENSION * 2 ||
                bounds.outHeight / sample > MAX_DIMENSION * 2
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeFile(source.absolutePath, options) ?: return null
            val saved = saveJpeg(context, upright(bitmap, orientationOf(source)))
            if (source.absolutePath.startsWith(context.cacheDir.absolutePath)) source.delete()
            saved
        }.getOrNull()
    }

    /** Copies any document into app storage. Returns the file and its name. */
    fun importDocument(context: Context, uri: Uri): Pair<File, String>? {
        return runCatching {
            val name = safeName(displayName(context, uri))
            val dir = mediaDir(context)
            val out = File(dir, "out-" + UUID.randomUUID().toString().take(8) + "-" + name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (out.length() == 0L) {
                out.delete()
                return null
            }
            out to name
        }.getOrNull()
    }

    /** A fresh cache file for the camera intent. */
    fun newCameraFile(context: Context): File {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        return File(dir, "IMG_" + UUID.randomUUID().toString().take(8) + ".jpg")
    }

    /** Small bitmap for chat bubbles. */
    fun thumbnail(path: String, maxPx: Int = 640): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > maxPx * 2 || bounds.outHeight / sample > maxPx * 2) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, options)
        }.getOrNull()
    }

    private fun orientationOf(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            android.media.ExifInterface(it).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrNull() ?: android.media.ExifInterface.ORIENTATION_NORMAL

    private fun orientationOf(file: File): Int = runCatching {
        android.media.ExifInterface(file.absolutePath).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)

    /** Applies the EXIF rotation/flip so the photo is not sent sideways. */
    private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = android.graphics.Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching {
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true,
            )
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        }.getOrDefault(bitmap)
    }

    private fun saveJpeg(context: Context, bitmap: Bitmap): File {
        val scaled = scaleDown(bitmap)
        val out = File(mediaDir(context), "out-" + UUID.randomUUID().toString().take(8) + ".jpg")
        out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return out
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION) return bitmap
        val ratio = MAX_DIMENSION.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun mediaDir(context: Context): File =
        File(context.filesDir, "media").apply { mkdirs() }

    fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "file" }
}
