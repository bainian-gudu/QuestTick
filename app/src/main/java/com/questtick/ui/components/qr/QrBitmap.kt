package com.questtick.ui.components.qr

// 将二维码内容渲染为可显示 Bitmap 的工具。

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

internal const val DEFAULT_QR_BITMAP_SIZE = 512

internal fun generateQrBitmap(
    content: String,
    size: Int = DEFAULT_QR_BITMAP_SIZE,
): Bitmap? =
    try {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (matrix.get(x, y)) QR_BLACK else QR_WHITE
            }
        }
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    } catch (_: Exception) {
        null
    }

private const val QR_BLACK = -0x1000000
private const val QR_WHITE = -1
