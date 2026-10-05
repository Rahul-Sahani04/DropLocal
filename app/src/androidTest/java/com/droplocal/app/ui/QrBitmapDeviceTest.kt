package com.droplocal.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droplocal.app.pairing.QrPayload
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QrBitmapDeviceTest {
    @Test fun actualQrBitmapRoundTripsUnicodeAndQuotedDeviceIdentity() {
        val payload = QrPayload(device = "手机, Téléphone \"😀\"")
        val bitmap = makeQrBitmap(payload.toJson())
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(
                RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
            assertEquals(payload, QrPayload.parse(decoded.text))
        } finally { bitmap.recycle() }
    }
}
