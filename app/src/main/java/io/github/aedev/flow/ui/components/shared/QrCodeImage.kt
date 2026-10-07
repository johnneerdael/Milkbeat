package io.github.aedev.flow.ui.components.shared

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.github.aedev.flow.R

/** Renders [text] as a QR code (ZXing, FOSS). */
@Composable
fun QrCodeImage(
    text: String,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.sync_qr_content_description),
) {
    val bitmap = remember(text) { generateQrBitmap(text, 640) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
        )
    }
}

private fun generateQrBitmap(
    text: String,
    size: Int,
): Bitmap? =
    try {
        // The spec's minimum quiet zone is 4 modules; the code is shown on a dark container in dark
        // theme, so anything less leaves scanners hunting for the finder patterns.
        val hints = mapOf(EncodeHintType.MARGIN to 4)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val offset = y * size
            for (x in 0 until size) {
                pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    } catch (e: Exception) {
        null
    }
