// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.map

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lv.bolwarra.wetter.data.map.MapTileSource

/**
 * Tiles, decoded and kept while the picker is open. Keeping what has been viewed
 * is fine under the tile policy, which prohibits pre-emptive fetching. A capped
 * map, about two zoom levels of a large screen; past that the oldest go.
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
