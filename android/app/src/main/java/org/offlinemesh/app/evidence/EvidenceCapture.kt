package org.offlinemesh.app.evidence

import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import org.offlinemesh.app.ble.MeshFrameCodec

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

    // P5 slice 1 (docs/DECISIONS.md decision 45, PLAN-v2.md §4.3) — small enough that even at low
    // JPEG quality a genuinely recognizable preview (crowd vs. document vs. night scene) still fits
    // MeshFrameCodec.MAX_THUMBNAIL_BYTES with real margin. Not private: reused by callers that need
    // to know the size a preview will be scaled to before generating one.
    const val THUMBNAIL_MAX_DIMENSION = 48
    private const val THUMBNAIL_JPEG_QUALITY_START = 35
    private const val THUMBNAIL_JPEG_QUALITY_FLOOR = 5
    private const val THUMBNAIL_JPEG_QUALITY_STEP = 5

    // The PLAINTEXT target — caller (RelayEngine.createEvidence) seals this via
    // MeshFrameCodec.sealThumbnail before it ever reaches the wire, adding
    // MeshFrameCodec.GCM_OVERHEAD_BYTES on top. Targeting the sealed cap directly here would
    // produce a plaintext that overflows MAX_THUMBNAIL_BYTES by exactly that overhead once sealed.
    private val THUMBNAIL_PLAINTEXT_TARGET_BYTES =
        MeshFrameCodec.MAX_THUMBNAIL_BYTES - MeshFrameCodec.GCM_OVERHEAD_BYTES

    /** Derives a thumbnail from the SAME already-downsampled [bitmap] [compress] itself works from
     *  — never re-decodes the source a second time (see `GroupChatScreen.loadBitmap`'s own doc for
     *  why a second full-res decode is exactly the allocation spike [MAX_DIMENSION] exists to
     *  avoid). Unlike [compress]'s fixed [JPEG_QUALITY], this steps quality down from
     *  [THUMBNAIL_JPEG_QUALITY_START] until the PLAINTEXT output actually fits
     *  [THUMBNAIL_PLAINTEXT_TARGET_BYTES] (the sealed cap minus AES-GCM's own overhead — this
     *  function's own output is later sealed by the caller, never sent as-is) — a fixed quality
     *  alone isn't guaranteed to hit an arbitrary byte cap across all image content the way it's
     *  fine to be loose about for [compress]'s own, much larger, chunk-bounded budget. Falls back
     *  to an empty array (never bytes that would overflow the wire cap once sealed) in the
     *  astronomically unlikely case even the quality floor doesn't fit at
     *  [THUMBNAIL_MAX_DIMENSION]. */
    fun compressThumbnail(bitmap: Bitmap): ByteArray {
        val scale = THUMBNAIL_MAX_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height)
        val resized = if (scale < 1f) {
            val matrix = Matrix().apply { postScale(scale, scale) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else bitmap
        var quality = THUMBNAIL_JPEG_QUALITY_START
        while (quality >= THUMBNAIL_JPEG_QUALITY_FLOOR) {
            val out = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            if (bytes.size <= THUMBNAIL_PLAINTEXT_TARGET_BYTES) return bytes
            quality -= THUMBNAIL_JPEG_QUALITY_STEP
        }
        return ByteArray(0)
    }
}
