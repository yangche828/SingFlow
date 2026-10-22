package com.singflow.android.core.export

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 节点分享链接二维码生成器。
 */
object QrCodeGenerator {

    /**
     * 将文本编码为黑白二维码位图。
     *
     * @param content 分享链接
     * @param size 边长像素
     * @return 位图；内容为空或编码失败返回 null
     */
    fun generate(content: String, size: Int = 640): Bitmap? {
        if (content.isBlank()) return null
        return runCatching {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, size, 0, 0, size, size)
            }
        }.getOrNull()
    }
}
