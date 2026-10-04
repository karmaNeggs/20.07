package org.offlinemesh.app.ui

import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Full-screen camera preview that decodes a QR code and calls [onScanned] once, then stops —
 * this app never captures or stores an image, only reads a QR's text out of live preview frames
 * and discards them. Reuses zxing:core (already a dependency for QR *generation*) for decoding
 * instead of adding a second QR library; CameraX supplies the preview surface and per-frame
 * analysis pipeline zxing needs.
 *
 * Caller is responsible for the CAMERA permission check/request — this composable assumes it's
 * already granted by the time it's shown (see AddGroupScreen).
 */
@Composable
fun QrScannerScreen(onScanned: (String) -> Unit, onDismiss: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // Guards against decoding + calling onScanned more than once for the same code while the
    // camera is still winding down after a successful read.
    val scanned = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                        if (!scanned.get()) {
                            val text = decodeQr(imageProxy)
                            if (text != null && scanned.compareAndSet(false, true)) {
                                onScanned(text)
                            }
                        }
                        imageProxy.close()
                    }
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    } catch (_: Exception) {
                        // Camera unavailable (in use elsewhere, hardware absent despite the permission
                        // being granted, etc.) — dismiss back to manual code entry rather than show a
                        // frozen/black preview with no way forward.
                        onDismiss()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        // A simple centered square outline — not pixel-precise to the analyzer's actual crop, just
        // a "point it here" cue, which is all a viewfinder needs for a QR that fills a good chunk
        // of frame at arm's length.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val boxSize = minOf(maxWidth, maxHeight) * 0.6f
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(boxSize)
                    .clip(RoundedCornerShape(16.dp))
                    .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
            )
        }

        Text(
            "Point at the QR code",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel scan", tint = Color.White)
        }
    }
}

private val qrReader = MultiFormatReader().apply {
    setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
}

@SuppressLint("UnsafeOptInUsageError")
private fun decodeQr(imageProxy: ImageProxy): String? {
    return try {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val source = PlanarYUVLuminanceSource(
            data, imageProxy.width, imageProxy.height,
            0, 0, imageProxy.width, imageProxy.height, false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        qrReader.decode(bitmap).text
    } catch (e: NotFoundException) {
        null // no QR in this frame — expected on almost every frame, not a real error
    } catch (e: Exception) {
        null
    } finally {
        qrReader.reset()
    }
}
