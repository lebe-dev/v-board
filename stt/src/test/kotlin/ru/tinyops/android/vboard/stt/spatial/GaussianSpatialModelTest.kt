package ru.tinyops.android.vboard.stt.spatial

import ru.tinyops.android.vboard.stt.model.Key
import ru.tinyops.android.vboard.stt.qwertyLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GaussianSpatialModelTest {
    private val key = Key(1, "a", left = 0f, top = 0f, right = 100f, bottom = 100f)

    @Test
    fun `a perfect hit costs nothing`() {
        val model = GaussianSpatialModel(sigma = 35.0)
        assertEquals(0.0, model.matchCost(key.centerX, key.centerY, key), 1e-12)
    }

    @Test
    fun `cost grows monotonically with distance`() {
        val model = GaussianSpatialModel(sigma = 35.0)
        val onCenter = model.matchCostToPoint(50f, 50f, 50f, 50f)
        val near = model.matchCostToPoint(60f, 50f, 50f, 50f)
        val far = model.matchCostToPoint(100f, 50f, 50f, 50f)
        assertEquals(0.0, onCenter, 1e-12)
        assertTrue(near < far, "near=$near far=$far")
    }

    @Test
    fun `cost equals squared distance over two sigma squared`() {
        val model = GaussianSpatialModel(sigma = 10.0)
        // 20 px away => 400 / (2 * 100) = 2.0
        assertEquals(2.0, model.matchCostToPoint(20f, 0f, 0f, 0f), 1e-9)
    }

    @Test
    fun `cost is symmetric`() {
        val model = GaussianSpatialModel(sigma = 25.0)
        val a = model.matchCostToPoint(10f, 15f, 40f, 60f)
        val b = model.matchCostToPoint(40f, 60f, 10f, 15f)
        assertEquals(a, b, 1e-12)
    }

    @Test
    fun `anisotropic model weights axes independently`() {
        val model = GaussianSpatialModel(sigma = 10.0, sigmaY = 100.0)
        val horizontal = model.matchCostToPoint(20f, 0f, 0f, 0f)
        val vertical = model.matchCostToPoint(0f, 20f, 0f, 0f)
        assertTrue(horizontal > vertical, "horizontal=$horizontal vertical=$vertical")
    }

    @Test
    fun `key overload delegates to the key center`() {
        val model = GaussianSpatialModel(sigma = 35.0)
        assertEquals(
            model.matchCostToPoint(10f, 10f, key.centerX, key.centerY),
            model.matchCost(10f, 10f, key),
            1e-12,
        )
    }

    @Test
    fun `layout factory scales sigma with key height`() {
        val layout = qwertyLayout()
        val model = GaussianSpatialModel.forLayout(layout, sigmaFraction = 0.35)
        // Average key height is 100 => sigma 35 => 35 px away costs 0.5.
        assertEquals(0.5, model.matchCostToPoint(35f, 0f, 0f, 0f), 1e-9)
    }

    @Test
    fun `rejects non-positive sigma`() {
        assertFailsWith<IllegalArgumentException> { GaussianSpatialModel(0.0) }
        assertFailsWith<IllegalArgumentException> { GaussianSpatialModel(1.0, sigmaY = -1.0) }
    }
}
