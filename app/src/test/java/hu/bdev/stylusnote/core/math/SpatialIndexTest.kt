package hu.bdev.stylusnote.core.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpatialIndexTest {

    @Test
    fun queryRectMatchesBruteForceOver2000RandomBoundsIncludingInfiniteCanvas() {
        val rng = Random(20261113)
        val index = SpatialIndex<Int>()
        val truth = LinkedHashMap<String, DocRect>()

        for (i in 0 until 2000) {
            val id = "stroke-$i"
            val x = rng.nextFloat() * 10_000f - 5_000f
            val y = rng.nextFloat() * 10_000f - 5_000f
            val w = rng.nextFloat() * 180f
            val h = rng.nextFloat() * 180f
            val rect = DocRect(x, y, x + w, y + h)
            index.insert(id, rect, i)
            truth[id] = rect
        }

        assertEquals(2000, index.size)

        for (q in 0 until 50) {
            val qx = rng.nextFloat() * 10_000f - 5_000f
            val qy = rng.nextFloat() * 10_000f - 5_000f
            val qw = rng.nextFloat() * 1_200f + 10f
            val qh = rng.nextFloat() * 1_200f + 10f
            val query = DocRect(qx, qy, qx + qw, qy + qh)

            val actualIds = index.queryRect(query).map { it.id }.toSet()
            val expectedIds = truth.entries
                .filter { SpatialIndex.rectsOverlap(it.value, query) }
                .map { it.key }
                .toSet()

            assertEquals("Mismatch on query $q", expectedIds, actualIds)
        }
    }

    @Test
    fun insertUpdateAndRemovePreserveBruteForceEquivalence() {
        val rng = Random(42)
        val index = SpatialIndex<String>()
        val truth = LinkedHashMap<String, DocRect>()

        for (i in 0 until 1200) {
            val id = "item-$i"
            val x = rng.nextFloat() * 2000f - 1000f
            val y = rng.nextFloat() * 2000f - 1000f
            val rect = DocRect(x, y, x + 40f, y + 30f)
            index.insert(id, rect, id)
            truth[id] = rect
        }

        // Remove 400 items (tests R-Tree condenseTree and re-insertion)
        for (i in 0 until 400) {
            val id = "item-$i"
            assertTrue(index.remove(id))
            truth.remove(id)
        }
        assertFalse(index.remove("item-0"))

        // Update 400 items with translated coordinates
        for (i in 400 until 800) {
            val id = "item-$i"
            val old = truth.getValue(id)
            val moved = DocRect(old.leftPt + 500f, old.topPt - 350f, old.rightPt + 520f, old.bottomPt - 310f)
            assertTrue(index.update(id, moved))
            truth[id] = moved
        }

        assertEquals(800, index.size)

        for (q in 0 until 30) {
            val qx = rng.nextFloat() * 2500f - 1250f
            val qy = rng.nextFloat() * 2500f - 1250f
            val query = DocRect(qx, qy, qx + 450f, qy + 450f)

            val actualIds = index.queryRect(query).map { it.id }.toSet()
            val expectedIds = truth.entries
                .filter { SpatialIndex.rectsOverlap(it.value, query) }
                .map { it.key }
                .toSet()

            assertEquals("Mismatch after updates/removals on query $q", expectedIds, actualIds)
        }
    }

    @Test
    fun queryCircleRejectsCornerBoxesOutsideCircleRadius() {
        val index = SpatialIndex<String>()
        // Circle at (100, 100) radius 50 -> square search box is [50..150, 50..150].
        // Box at [51..55, 51..55] is inside the square box, but distance from (100, 100) to (55, 55)
        // is sqrt(45^2 + 45^2) = 63.64 > 50, so it must NOT match.
        index.insert("corner-outside", DocRect(51f, 51f, 55f, 55f), "corner-outside")
        index.insert("edge-inside", DocRect(120f, 95f, 145f, 105f), "edge-inside")
        index.insert("tangent-touch", DocRect(150f, 95f, 170f, 105f), "tangent-touch")

        val hitIds = index.queryCircle(centerXPt = 100f, centerYPt = 100f, radiusPt = 50f)
            .map { it.id }
            .toSet()

        assertEquals(setOf("edge-inside", "tangent-touch"), hitIds)
    }

    @Test
    fun queryLassoPolygonEnforcesSixtyPercentPointThreshold() {
        val index = SpatialIndex<List<DocPoint>>()
        val lasso = listOf(
            DocPoint(0f, 0f),
            DocPoint(100f, 0f),
            DocPoint(100f, 100f),
            DocPoint(0f, 100f)
        )

        // 5 points total: 3 inside (60%), 2 outside -> should be selected.
        val sixtyPercent = listOf(
            DocPoint(10f, 50f),
            DocPoint(30f, 50f),
            DocPoint(50f, 50f),
            DocPoint(120f, 50f),
            DocPoint(140f, 50f)
        )
        // 5 points total: 2 inside (40%), 3 outside -> should be rejected.
        val fortyPercent = listOf(
            DocPoint(10f, 20f),
            DocPoint(30f, 20f),
            DocPoint(110f, 20f),
            DocPoint(130f, 20f),
            DocPoint(150f, 20f)
        )

        index.insert("pass-60", DocRect(10f, 50f, 140f, 50f), sixtyPercent)
        index.insert("fail-40", DocRect(10f, 20f, 150f, 20f), fortyPercent)

        val selected = index.queryLassoPolygon(
            polygon = lasso,
            pointExtractor = { it }
        ).map { it.id }

        assertEquals(listOf("pass-60"), selected)
    }
}

