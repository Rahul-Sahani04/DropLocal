package com.droplocal.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

fun makeQrBitmap(content: String, size: Int = 512): Bitmap {
    val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) {
        bmp.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
    }
    return bmp
}

@Composable
fun QrImage(content: String, modifier: Modifier = Modifier) {
    val bmp = remember(content) { makeQrBitmap(content).asImageBitmap() }
    Image(bitmap = bmp, contentDescription = "Pairing QR", modifier = modifier)
}
