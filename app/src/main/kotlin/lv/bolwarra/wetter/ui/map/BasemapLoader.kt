package lv.bolwarra.wetter.ui.map

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lv.bolwarra.wetter.data.map.MapTileSource

/**
 * Tiles, decoded and kept for as long as the picker is open.
 *
 * Keeping what has already been seen is explicitly fine under the tile policy -
 * it is *pre-emptive* fetching that is prohibited, and a tile already on screen
 * has plainly been fetched for a viewer. So panning back to where you were costs
 * nothing, while panning somewhere new costs exactly the tiles you are now
 * looking at.
 *
 * The cache is a plain map with a cap rather than anything cleverer. It lives
 * for one visit to the picker, and the ceiling is about what a large phone
 * screen covers at two zoom levels - past that the oldest go, because somebody
 * who has panned across a continent is not about to pan back across it.
 */
class BasemapLoader(private val source: MapTileSource) : TileLoader {

    private val cache = LinkedHashMap<TileKey, ImageBitmap>()

    override suspend fun load(key: TileKey): ImageBitmap? {
        cache[key]?.let { return it }

        val bytes = source.tile(key.zoom, key.x, key.y) ?: return null
        val image = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } ?: return null

        cache[key] = image
        while (cache.size > MAX_TILES) {
            cache.remove(cache.keys.first())
        }
        return image
    }

    private companion object {
        /** Roughly two screenfuls at 256 px a tile. */
        const val MAX_TILES = 64
    }
}
