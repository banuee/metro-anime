package dev.metro.anime.data.settings

import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils

/**
 * Экстрактор палитры цветов из обоев.
 * Находит доминантные и характерные цвета изображения (включая приглушённые, пастельные,
 * тёмные и нейтральные оттенки), группирует их по перцептивному расстоянию
 * и возвращает гармоничные тона для акцента интерфейса.
 */
object ColorPaletteExtractor {

    private val FALLBACK_PALETTE = listOf(
        0xFF00ABA9.toInt(), // Teal
        0xFF1BA1E2.toInt(), // Sky
        0xFF6A00FF.toInt(), // Indigo
        0xFFAA00FF.toInt(), // Violet
        0xFFD80073.toInt(), // Magenta
        0xFFE51400.toInt(), // Crimson Red
        0xFFFA6800.toInt(), // Orange
        0xFF339933.toInt(), // Emerald
    )

    private class BinAccumulator {
        var count: Int = 0
        var sumR: Long = 0
        var sumG: Long = 0
        var sumB: Long = 0
    }

    private data class CandidateColor(
        val color: Int,
        val score: Float,
    )

    fun extractPalette(sourceBitmap: Bitmap, limit: Int = 8): List<Int> {
        return try {
            val sampleSize = 120
            val scaled = Bitmap.createScaledBitmap(sourceBitmap, sampleSize, sampleSize, true)
            val pixels = IntArray(sampleSize * sampleSize)
            scaled.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)
            if (scaled !== sourceBitmap) scaled.recycle()

            // 4-битное квантование RGB (4096 корзин) с точным суммированием RGB внутри каждой корзины
            val bins = HashMap<Int, BinAccumulator>(512)
            for (p in pixels) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val binKey = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)

                val acc = bins.getOrPut(binKey) { BinAccumulator() }
                acc.count++
                acc.sumR += r
                acc.sumG += g
                acc.sumB += b
            }

            val hsl = FloatArray(3)
            val candidates = ArrayList<CandidateColor>(bins.size)

            for ((_, acc) in bins) {
                if (acc.count < 3) continue // Отсекаем единичный шум

                val avgR = (acc.sumR / acc.count).toInt()
                val avgG = (acc.sumG / acc.count).toInt()
                val avgB = (acc.sumB / acc.count).toInt()
                val color = (0xFF shl 24) or (avgR shl 16) or (avgG shl 8) or avgB

                ColorUtils.colorToHSL(color, hsl)
                val s = hsl[1]
                val l = hsl[2]

                // Отсекаем только абсолютный экстремум (глухой ноль/рамка и чистый ослепительный белый)
                if (l < 0.04f || l > 0.98f) continue

                val saturationBonus = 1.0f + s * 0.75f
                val lightnessWeight = if (l in 0.15f..0.85f) 1.2f else 0.85f
                val score = acc.count * saturationBonus * lightnessWeight

                candidates.add(CandidateColor(color, score))
            }

            candidates.sortByDescending { it.score }

            // Отбор разнообразных цветов с использованием формулы Compuphase redmean
            val result = ArrayList<Int>(limit)

            fun pickDistinct(distanceThreshold: Double) {
                for (cand in candidates) {
                    if (result.size >= limit) break
                    val tooClose = result.any { selected ->
                        colorDistance(cand.color, selected) < distanceThreshold
                    }
                    if (!tooClose) {
                        result.add(cand.color)
                    }
                }
            }

            // Первый проход: строго различные цвета
            pickDistinct(48.0)

            // Второй проход: если цветов мало (например, монохромные обои)
            if (result.size < limit) {
                pickDistinct(26.0)
            }

            // Дополняем дефолтными акцентами Metro, если всё ещё мало
            for (fb in FALLBACK_PALETTE) {
                if (result.size >= limit) break
                val tooClose = result.any { selected ->
                    colorDistance(fb, selected) < 36.0
                }
                if (!tooClose) {
                    result.add(fb)
                }
            }

            result
        } catch (_: Exception) {
            FALLBACK_PALETTE.take(limit)
        }
    }

    /**
     * Перцептивное расстояние между цветами (Compuphase redmean metric).
     */
    fun colorDistance(c1: Int, c2: Int): Double {
        val r1 = (c1 shr 16) and 0xFF
        val g1 = (c1 shr 8) and 0xFF
        val b1 = c1 and 0xFF
        val r2 = (c2 shr 16) and 0xFF
        val g2 = (c2 shr 8) and 0xFF
        val b2 = c2 and 0xFF

        val rmean = (r1 + r2) / 2.0
        val dr = (r1 - r2).toDouble()
        val dg = (g1 - g2).toDouble()
        val db = (b1 - b2).toDouble()

        return kotlin.math.sqrt(
            (2.0 + rmean / 256.0) * dr * dr +
            4.0 * dg * dg +
            (2.0 + (255.0 - rmean) / 256.0) * db * db
        )
    }
}
