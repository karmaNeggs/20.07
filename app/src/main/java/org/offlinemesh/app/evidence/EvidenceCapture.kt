package org.offlinemesh.app.evidence

import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

/** Aggressive compression — the mesh's realistic throughput ceiling means low-def beats undelivered. */
object EvidenceCapture {
    // Not private: GroupChatScreen's picker uses this same number to downsample a large source
    // image *during* decode (see loadBitmap), before it ever becomes a full-resolution Bitmap —
    // otherwise a modern 12-48MP gallery photo can allocate 50-150MB decoding at full size even
    // though compress() below immediately throws almost all of it away.
    const val MAX_DIMENSION = 640
    private const val JPEG_QUALITY = 45

    fun compress(bitmap: Bitmap): ByteArray {
        val scale = MAX_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height)
        val resized = if (scale < 1f) {
            val matrix = Matrix().apply { postScale(scale, scale) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else bitmap
        val out = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }
}
