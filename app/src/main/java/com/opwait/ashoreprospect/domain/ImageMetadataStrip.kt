package com.opwait.ashoreprospect.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Phone photos keep GPS, camera, and time in EXIF / PNG text. Re-encode pixels only
 * so the host never stores that. Skip when the file already has no metadata markers
 * so a clean photo is not re-hashed every launch.
 */
object ImageMetadataStrip {
    private const val JPEG_Q = 88

    fun hasPrivacyMetadata(bytes: ByteArray): Boolean = when {
        bytes.isEmpty() -> false
        isJpeg(bytes) -> jpegHasPrivacyMarkers(bytes)
        isPng(bytes) -> pngHasTextChunks(bytes)
        isWebp(bytes) -> webpHasExifOrXmp(bytes)
        else -> true
    }

    fun strip(bytes: ByteArray, mimeHint: String? = null): Pair<ByteArray, String> {
        if (bytes.isEmpty()) error("Empty image")
        if (!hasPrivacyMetadata(bytes)) {
            return bytes to guessMime(bytes, mimeHint)
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Could not read image to wipe metadata")
        val oriented = applyExifOrientation(bytes, bmp)
        if (oriented != bmp && !bmp.isRecycled) bmp.recycle()
        val png = isPng(bytes) || (mimeHint?.contains("png", ignoreCase = true) == true)
        val out = ByteArrayOutputStream()
        val mime: String
        if (png) {
            oriented.compress(Bitmap.CompressFormat.PNG, 100, out)
            mime = "image/png"
        } else {
            var q = JPEG_Q
            do {
                out.reset()
                oriented.compress(Bitmap.CompressFormat.JPEG, q, out)
                q -= 12
            } while (out.size() > 4 * 1024 * 1024 && q >= 52)
            mime = "image/jpeg"
        }
        if (!oriented.isRecycled) oriented.recycle()
        val clean = out.toByteArray()
        if (clean.isEmpty()) error("Could not wipe image metadata")
        return clean to mime
    }

    fun safeFileName(name: String, mime: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val stem = base.substringBeforeLast('.')
            .ifBlank { "image" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(40)
            .ifBlank { "image" }
        val ext = when {
            mime.contains("png", ignoreCase = true) -> "png"
            mime.contains("webp", ignoreCase = true) -> "webp"
            else -> "jpg"
        }
        return "$stem.$ext"
    }

    fun guessMime(bytes: ByteArray, hint: String? = null): String = when {
        isPng(bytes) -> "image/png"
        isJpeg(bytes) -> "image/jpeg"
        isWebp(bytes) -> "image/webp"
        !hint.isNullOrBlank() -> hint
        else -> "image/jpeg"
    }

    fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size > 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()

    fun isWebp(bytes: ByteArray): Boolean =
        bytes.size > 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()

    internal fun jpegHasPrivacyMarkers(bytes: ByteArray): Boolean {
        var i = 2
        while (i + 3 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) break
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                i += 2
                continue
            }
            if (i + 3 >= bytes.size) break
            val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (marker == 0xE1 || marker == 0xED || marker == 0xFE) return true
            if (len < 2) break
            i += 2 + len
        }
        return false
    }

    internal fun pngHasTextChunks(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        var i = 8
        while (i + 12 <= bytes.size) {
            val len = readInt(bytes, i)
            if (len < 0 || i + 12 + len > bytes.size) break
            val type = String(bytes, i + 4, 4, Charsets.US_ASCII)
            if (type == "tEXt" || type == "iTXt" || type == "zTXt" || type == "tIME" || type == "eXIf") {
                return true
            }
            if (type == "IEND") break
            i += 12 + len
        }
        return false
    }

    internal fun webpHasExifOrXmp(bytes: ByteArray): Boolean {
        val ascii = runCatching { String(bytes, Charsets.ISO_8859_1) }.getOrDefault("")
        return ascii.contains("EXIF") || ascii.contains("XMP")
    }

    private fun readInt(bytes: ByteArray, i: Int): Int {
        return ((bytes[i].toInt() and 0xFF) shl 24) or
            ((bytes[i + 1].toInt() and 0xFF) shl 16) or
            ((bytes[i + 2].toInt() and 0xFF) shl 8) or
            (bytes[i + 3].toInt() and 0xFF)
    }

    private fun applyExifOrientation(bytes: ByteArray, bmp: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bmp
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}
