package lv.bolwarra.wetter.data.provider.rainviewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import lv.bolwarra.wetter.domain.radar.RadarGeometry

/**
 * Decodes a radar tile with the platform's own PNG decoder.
 *
 * Kept behind [TileDecoder] so everything that reads meaning out of the pixels
 * can be tested on a plain JVM. The Android graphics classes are stubs in unit
 * tests and return null for everything, which would otherwise make the palette -
 * the part most worth testing - untestable without a device.
 *
 * [Bitmap.Config.ARGB_8888] is requested explicitly because the tiles carry
 * meaning in their alpha channel: the faint end of the intensity scale *is*
 * transparency, so a decode that discarded it would silently throw away every
 * light shower.
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
