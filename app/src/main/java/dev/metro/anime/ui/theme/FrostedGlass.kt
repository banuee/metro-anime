package dev.metro.anime.ui.theme

import android.os.Build
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Snappy Metro touch feedback (elastic scale on tap and press).
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

/**
 * Acrylic Metro glass container with 1px border and optional hardware blur.
 */
@Composable
fun MetroGlassBox(
    modifier: Modifier = Modifier,
    shape: Dp = MetroDimens.radius,
    tint: Color? = null,
    borderColor: Color? = null,
    borderWidth: Dp = 1.dp,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = LocalMetroScheme.current
    val effectiveTint = tint ?: scheme.glass
    val effectiveBorder = borderColor ?: scheme.stroke

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(shape))
            .drawBehind {
                drawRect(effectiveTint)
            }
            .border(borderWidth, effectiveBorder, RoundedCornerShape(shape)),
        contentAlignment = contentAlignment,
    ) {
        content()
    }
}
