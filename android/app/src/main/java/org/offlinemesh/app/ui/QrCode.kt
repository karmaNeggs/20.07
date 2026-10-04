package org.offlinemesh.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Standard black-on-white, not theme-colored — inverted/light-on-dark QR codes fail to scan
 * with some camera apps, and reliability matters more here than matching the dark theme. Wrapped
 * in a white card so it still sits cleanly on a dark background instead of looking like an error.
 */
@Composable
fun QrCodeCard(content: String, modifier: Modifier = Modifier, sizeDp: Dp = 180.dp) {
    val bitmap = remember(content) { generateQrBitmap(content, 512) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR code — scan to join",
            modifier = modifier
                .size(sizeDp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(12.dp)
        )
    }
}

private fun generateQrBitmap(content: String, sizePx: Int): Bitmap? = try {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    bmp
} catch (e: Exception) {
    null
}
