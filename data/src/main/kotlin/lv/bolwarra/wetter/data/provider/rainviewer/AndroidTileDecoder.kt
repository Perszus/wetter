// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.data.provider.rainviewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import lv.bolwarra.wetter.domain.radar.RadarGeometry

/**
 * Decodes a radar tile with the platform's PNG decoder, behind [TileDecoder] so
 * the palette can be tested on a plain JVM. [Bitmap.Config.ARGB_8888] keeps the
 * alpha channel, which carries the faint end of the scale.
 */
internal class AndroidTileDecoder : TileDecoder {

    override fun decode(bytes: ByteArray): IntArray? {
        if (bytes.isEmpty()) return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // Tiles are small and read once. Mutability would only cost a copy.
            inMutable = false
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return try {
            val size = RadarGeometry.TILE_SIZE
            if (bitmap.width != size || bitmap.height != size) return null
            IntArray(size * size).also { bitmap.getPixels(it, 0, size, 0, 0, size, size) }
        } finally {
            bitmap.recycle()
        }
    }
}
