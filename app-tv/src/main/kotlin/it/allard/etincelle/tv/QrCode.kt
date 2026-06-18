// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Encodes [content] as a square QR [ImageBitmap], or null if encoding fails. */
fun qrImageBitmap(content: String, sizePx: Int): ImageBitmap? = runCatching {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    // Fill a row-major pixel array and blit it in one call, rather than ~sizePx^2 setPixel JNI hops.
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        val row = y * sizePx
        for (x in 0 until sizePx) {
            pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    val bmp = createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    bmp.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
    bmp.asImageBitmap()
}.getOrNull()
