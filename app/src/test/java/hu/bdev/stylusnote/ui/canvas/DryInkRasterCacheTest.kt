package hu.bdev.stylusnote.ui.canvas

import hu.bdev.stylusnote.core.math.DocRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DryInkRasterCacheTest {

    @Test
    fun tileLruTrackerEnforces192MbCeilingAndEvictsOldestAccessed() {
        val evictedKeys = ArrayList<TileKey>()
        val tracker = TileLruTracker<TileKey, String>(
            maxBytes = DryInkRasterCache.DEFAULT_MAX_CACHE_BYTES,
            onEvict = { key, _ -> evictedKeys.add(key) }
        )

        val tileBytes = DryInkRasterCache.TILE_BYTES_ARGB_8888
        assertEquals(1_048_576L, tileBytes)

        // Fill exactly 192 tiles (192 MB)
        for (i in 0 until 192) {
            val key = TileKey(quantizedScaleMilli = 1000, tileX = i, tileY = 0, isOledMode = false)
            tracker.put(key, "tile-$i", tileBytes)
        }
        assertEquals(192, tracker.size)
        assertEquals(192L * 1024L * 1024L, tracker.usedBytes)
        assertTrue(evictedKeys.isEmpty())

        // Touch tile 0 so tile 1 becomes the least recently used entry
        val tile0 = TileKey(1000, 0, 0, false)
        val tile1 = TileKey(1000, 1, 0, false)
        assertNotNull(tracker.get(tile0))

        // Insert 193rd tile -> tile 1 must be evicted, tile 0 retained
        val tile192 = TileKey(1000, 192, 0, false)
        tracker.put(tile192, "tile-192", tileBytes)

        assertEquals(192, tracker.size)
        assertEquals(192L * 1024L * 1024L, tracker.usedBytes)
        assertEquals(listOf(tile1), evictedKeys)
        assertNull(tracker.get(tile1))
        assertNotNull(tracker.get(tile0))
    }

    @Test
    fun overlappingTileRangeHandlesNegativeInfiniteCanvasCoordinates() {
        // At 1.0x scale, each tile covers 512 x 512 doc points.
        val range = DryInkRasterCache.computeOverlappingTileRange(
            docRect = DocRect(leftPt = -600f, topPt = -10f, rightPt = 600f, bottomPt = 520f),
            scale = 1.0f
        )

        assertEquals(-2, range.minTileX)
        assertEquals(1, range.maxTileX)
        assertEquals(-1, range.minTileY)
        assertEquals(1, range.maxTileY)
    }

    @Test
    fun removeWhereInvalidatesOnlyIntersectingTiles() {
        val tracker = TileLruTracker<TileKey, String>()
        val tileBytes = DryInkRasterCache.TILE_BYTES_ARGB_8888

        val tile00 = TileKey(1000, 0, 0, false) // covers [0..512, 0..512]
        val tile10 = TileKey(1000, 1, 0, false) // covers [512..1024, 0..512]
        val tile55 = TileKey(1000, 5, 5, false) // covers [2560..3072, 2560..3072]

        tracker.put(tile00, "a", tileBytes)
        tracker.put(tile10, "b", tileBytes)
        tracker.put(tile55, "c", tileBytes)

        val dirty = DocRect(100f, 100f, 200f, 200f)
        val removedCount = tracker.removeWhere { key ->
            val rect = DryInkRasterCache.computeTileDocRect(key.tileX, key.tileY, key.quantizedScaleMilli / 1000f)
            hu.bdev.stylusnote.core.math.SpatialIndex.rectsOverlap(rect, dirty)
        }

        assertEquals(1, removedCount)
        assertNull(tracker.get(tile00))
        assertNotNull(tracker.get(tile10))
        assertNotNull(tracker.get(tile55))
    }
}

