package dev.metro.anime.ui.theme

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch

/**
 * Предблюренные обои на весь экран. Плитки рисуют срез обоев 1:1 ровно под своей позицией в окне.
 * null = нет обоев / отключен блюр — стекло рисуется полупрозрачной акриловой подложкой.
 */
val LocalBlurredWallpaper: ProvidableCompositionLocal<ImageBitmap?> =
    compositionLocalOf { null }

/**
 * Аппаратный RenderEffect блюр для слоев Compose на Android 12+ (API 31+).
 * Включает boost насыщенности (vibrancy = 1.24), чтобы цвета не блекли при размытии.
 */
fun Modifier.metroBlurEffect(
    radiusPx: Float = 32f,
    saturationBoost: Float = 1.24f,
): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || radiusPx <= 0f) {
        return this
    }
    return this.graphicsLayer {
        val blurEffect = android.graphics.RenderEffect.createBlurEffect(
            radiusPx,
            radiusPx,
            android.graphics.Shader.TileMode.CLAMP,
        )
        val cm = android.graphics.ColorMatrix().apply {
            setSaturation(saturationBoost)
        }
        val colorFilter = android.graphics.RenderEffect.createColorFilterEffect(
            android.graphics.ColorMatrixColorFilter(cm),
        )
        renderEffect = android.graphics.RenderEffect.createChainEffect(
            colorFilter,
            blurEffect,
        ).asComposeRenderEffect()
    }
}

/**
 * Включает аппаратный блюр фона под окном диалога через SurfaceFlinger на Android 12+ (API 31+).
 */
@Composable
fun DialogWindowBlurEffect(
    blurRadiusPx: Int = 60,
    dimAmount: Float = 0.20f,
) {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            val lp = window.attributes
            lp.blurBehindRadius = blurRadiusPx
            window.attributes = lp
            window.setDimAmount(dimAmount)
        }
        onDispose { }
    }
}

/**
 * Стеклянный бокс с фрост-подложкой: идеальный 1:1 срез блюра под своей позицией в окне.
 * Никаких сдвигов или темных непрозрачных пятен поверх обоев.
 */
@Composable
fun FrostedGlassBox(
    modifier: Modifier = Modifier,
    shape: Dp = MetroDimens.radius,
    tint: Color = Color.Transparent,
    borderColor: Color? = null,
    borderWidth: Dp = 1.dp,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = LocalMetroScheme.current
    val blurred = LocalBlurredWallpaper.current
    var pos by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                pos = coordinates.positionOnScreen()
            }
            .clip(RoundedCornerShape(shape))
            .drawBehind {
                if (blurred != null) {
                    // Срез блюра ровно в экранных координатах элемента — 1:1 совпадение с фоном
                    drawImage(
                        image = blurred,
                        topLeft = Offset(-pos.x, -pos.y),
                    )
                    if (tint != Color.Transparent) {
                        drawRect(tint)
                    }
                } else {
                    // Фолбэк без обоев: чистый полупрозрачный акрил
                    drawRect(if (tint != Color.Transparent) tint else scheme.glass)
                }
            }
            .then(
                if (borderColor != null) {
                    Modifier.border(borderWidth, borderColor, RoundedCornerShape(shape))
                } else Modifier
            ),
        contentAlignment = contentAlignment,
    ) {
        content()
    }
}

/**
 * Тактильный клик-модификатор Metro (упругий отскок при тапе и легкое сжатие при удержании).
 */
@Composable
fun Modifier.metroClickable(
    enabled: Boolean = true,
    targetScale: Float = 0.95f,
    onClick: () -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val pulse = remember { Animatable(1f) }
    val heldScale by animateFloatAsState(
        targetValue = if (pressed) targetScale else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "metro-click-held",
    )
    val scale = if (pressed) heldScale else pulse.value

    return this
        .graphicsLayer(scaleX = scale, scaleY = scale)
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
        ) {
            scope.launch {
                pulse.animateTo(targetScale, tween(60, easing = FastOutSlowInEasing))
                pulse.animateTo(
                    1f,
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
            }
            onClick()
        }
}
