package dev.metro.anime.data.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

import androidx.datastore.preferences.core.stringPreferencesKey

private val Context.animeSettingsStore by preferencesDataStore(name = "metro_anime_settings")

data class MetroAccentOption(
    val name: String,
    val colorInt: Int,
)

data class AnimeSettings(
    val accentColor: Int = 0xFF00ABA9.toInt(), // Quickshell Teal
    val blurEnabled: Boolean = true,
    val blurRadius: Int = 14,
    val backgroundDim: Float = 0.55f, // Dim overlay over wallpaper
    val hasCustomWallpaper: Boolean = false,
    val wallpaperPalette: List<Int> = emptyList(),
)

data class WallpaperBitmapHolder(
    val sharp: ImageBitmap,
    val blurred: ImageBitmap,
)

class MetroSettingsRepository(private val context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private val KEY_ACCENT = intPreferencesKey("accent_color")
        private val KEY_BLUR_ENABLED = booleanPreferencesKey("blur_enabled")
        private val KEY_BLUR_RADIUS = intPreferencesKey("blur_radius")
        private val KEY_BG_DIM = floatPreferencesKey("bg_dim")
        private val KEY_HAS_WALLPAPER = booleanPreferencesKey("has_wallpaper")
        private val KEY_WALLPAPER_PALETTE = stringPreferencesKey("wallpaper_palette")

        val ACCENT_PRESETS = listOf(
            MetroAccentOption("Бирюзовый", 0xFF00ABA9.toInt()),
            MetroAccentOption("Изумруд", 0xFF339933.toInt()),
            MetroAccentOption("Лайм", 0xFFA4C400.toInt()),
            MetroAccentOption("Небесный", 0xFF1BA1E2.toInt()),
            MetroAccentOption("Индиго", 0xFF6A00FF.toInt()),
            MetroAccentOption("Фиолетовый", 0xFFAA00FF.toInt()),
            MetroAccentOption("Пурпурный", 0xFFD80073.toInt()),
            MetroAccentOption("Красный", 0xFFE51400.toInt()),
            MetroAccentOption("Оранжевый", 0xFFFA6800.toInt()),
            MetroAccentOption("Янтарный", 0xFFF0A30A.toInt()),
            MetroAccentOption("Золотой", 0xFFE3C800.toInt()),
            MetroAccentOption("Стальной", 0xFF647687.toInt()),
        )
    }

    private val _wallpaper = MutableStateFlow<WallpaperBitmapHolder?>(null)
    val wallpaper: StateFlow<WallpaperBitmapHolder?> = _wallpaper.asStateFlow()

    val settingsFlow: Flow<AnimeSettings> = app.animeSettingsStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }.map { prefs ->
        val rawPalette = prefs[KEY_WALLPAPER_PALETTE]?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        AnimeSettings(
            accentColor = prefs[KEY_ACCENT] ?: 0xFF00ABA9.toInt(),
            blurEnabled = prefs[KEY_BLUR_ENABLED] ?: true,
            blurRadius = prefs[KEY_BLUR_RADIUS] ?: 14,
            backgroundDim = prefs[KEY_BG_DIM] ?: 0.55f,
            hasCustomWallpaper = prefs[KEY_HAS_WALLPAPER] ?: false,
            wallpaperPalette = rawPalette,
        )
    }

    val settings: StateFlow<AnimeSettings> = settingsFlow.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = AnimeSettings(),
    )

    init {
        reloadWallpaper()
        scope.launch {
            var prevBlur = settings.value.blurRadius
            var prevEnabled = settings.value.blurEnabled
            settings.collect { s ->
                if (s.blurRadius != prevBlur || s.blurEnabled != prevEnabled) {
                    prevBlur = s.blurRadius
                    prevEnabled = s.blurEnabled
                    reloadWallpaper()
                }
            }
        }
    }

    fun setAccentColor(color: Int) {
        scope.launch {
            app.animeSettingsStore.edit { prefs ->
                prefs[KEY_ACCENT] = color
            }
        }
    }

    fun setBlurEnabled(enabled: Boolean) {
        scope.launch {
            app.animeSettingsStore.edit { prefs ->
                prefs[KEY_BLUR_ENABLED] = enabled
            }
        }
    }

    fun setBlurRadius(radius: Int) {
        val clamped = radius.coerceIn(4, 32)
        scope.launch {
            app.animeSettingsStore.edit { prefs ->
                prefs[KEY_BLUR_RADIUS] = clamped
            }
        }
    }

    fun setBackgroundDim(dim: Float) {
        val clamped = dim.coerceIn(0.15f, 0.90f)
        scope.launch {
            app.animeSettingsStore.edit { prefs ->
                prefs[KEY_BG_DIM] = clamped
            }
        }
    }

    private fun wallpaperFile(): File = File(app.filesDir, "anime_wallpaper.jpg")

    fun setCustomWallpaper(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    wallpaperFile().outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                app.animeSettingsStore.edit { prefs ->
                    prefs[KEY_HAS_WALLPAPER] = true
                }
                reloadWallpaper()
            } catch (e: Exception) {
                Log.e("MetroAnime", "Failed to save wallpaper: ${e.message}")
            }
        }
    }

    fun clearWallpaper() {
        scope.launch(Dispatchers.IO) {
            val file = wallpaperFile()
            if (file.exists()) {
                file.delete()
            }
            app.animeSettingsStore.edit { prefs ->
                prefs[KEY_HAS_WALLPAPER] = false
                prefs.remove(KEY_WALLPAPER_PALETTE)
            }
            _wallpaper.value = null
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val metrics = app.getSystemService(android.view.WindowManager::class.java)
                    .currentWindowMetrics.bounds
                metrics.width() to metrics.height()
            } catch (_: Exception) {
                fallbackSize()
            }
        } else {
            fallbackSize()
        }
    }

    private fun fallbackSize(): Pair<Int, Int> {
        val dm = app.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private fun centerCrop(src: Bitmap, sw: Int, sh: Int): Bitmap? {
        return try {
            if (src.width == sw && src.height == sh) return src
            val scale = maxOf(sw / src.width.toFloat(), sh / src.height.toFloat())
            val dw = Math.round(src.width * scale)
            val dh = Math.round(src.height * scale)
            val scaled = Bitmap.createScaledBitmap(src, dw, dh, true)
            val x = ((dw - sw) / 2).coerceAtLeast(0)
            val y = ((dh - sh) / 2).coerceAtLeast(0)
            val result = Bitmap.createBitmap(
                scaled, x, y, sw.coerceAtMost(dw - x), sh.coerceAtMost(dh - y),
            )
            if (result !== scaled) {
                scaled.recycle()
            }
            if (result !== src) src.recycle()
            result
        } catch (_: Exception) {
            null
        }
    }

    fun reloadWallpaper() {
        scope.launch(Dispatchers.IO) {
            val file = wallpaperFile()
            if (!file.exists()) {
                _wallpaper.value = null
                return@launch
            }
            try {
                val (sw, sh) = screenSize()
                val rawBitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@launch
                val scaledSharp = centerCrop(rawBitmap, sw, sh) ?: return@launch

                // Extract wallpaper palette
                val palette = ColorPaletteExtractor.extractPalette(scaledSharp, limit = 8)
                app.animeSettingsStore.edit { prefs ->
                    prefs[KEY_WALLPAPER_PALETTE] = palette.joinToString(",") { it.toString() }
                }

                val radius = settings.value.blurRadius
                val blurred = if (settings.value.blurEnabled) {
                    val scaleFactor = 2
                    val w = (sw / scaleFactor).coerceAtLeast(1)
                    val h = (sh / scaleFactor).coerceAtLeast(1)
                    val small = Bitmap.createScaledBitmap(scaledSharp, w, h, true)
                    val blurredSmall = stackBlur(small, radius.coerceIn(2, 30))
                    val enhanced = applyHyprlandColorFilter(blurredSmall)
                    if (blurredSmall !== small) blurredSmall.recycle()
                    small.recycle()
                    Bitmap.createScaledBitmap(enhanced, sw, sh, true)
                } else {
                    scaledSharp.copy(Bitmap.Config.ARGB_8888, true)
                }

                _wallpaper.value = WallpaperBitmapHolder(
                    sharp = scaledSharp.asImageBitmap(),
                    blurred = blurred.asImageBitmap(),
                )
            } catch (e: Exception) {
                Log.e("MetroAnime", "Error loading wallpaper: ${e.message}")
                _wallpaper.value = null
            }
        }
    }

    private fun applyHyprlandColorFilter(bitmap: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val cm = ColorMatrix()
        cm.setSaturation(1.25f)

        val contrast = 0.90f
        val translate = (1f - contrast) * 128f
        val contrastCm = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f,
        ))
        cm.postConcat(contrastCm)

        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    private fun stackBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return sentBitmap
        val bitmap = sentBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(maxOf(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (idx in 0 until 256 * divsum) {
            dv[idx] = idx / divsum
        }

        yw = 0
        yi = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        for (curY in 0 until h) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            for (i in -radius..radius) {
                p = pix[yi + minOf(wm, maxOf(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rbs = r1 - kotlin.math.abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
            }
            stackpointer = radius

            for (curX in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (curY == 0) {
                    vmin[curX] = minOf(curX + radius + 1, wm)
                }
                p = pix[yw + vmin[curX]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
            }
            yw += w
        }

        for (curX in 0 until w) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            yp = -radius * w
            for (i in -radius..radius) {
                yi = maxOf(0, yp) + curX
                sir = stack[i + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                rbs = r1 - kotlin.math.abs(i)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (i < hm) {
                    yp += w
                }
            }
            yi = curX
            stackpointer = radius
            for (curY in 0 until h) {
                pix[yi] = (0xff000000.toInt()) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (curX == 0) {
                    vmin[curY] = minOf(curY + r1, hm) * w
                }
                p = curX + vmin[curY]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }
}
