package dev.metro.anime.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

object MetroAnimations {
    val OpenEasing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    val MoveEasing = CubicBezierEasing(0.80f, 0.0f, 0.12f, 1.4f)
    val CloseEasing = CubicBezierEasing(0.46f, 1.0f, 0.29f, 0.99f)
    val TagEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
    val BouncyEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1.0f)

    const val DURATION_FAST = 180
    const val DURATION_NORMAL = 240
    const val DURATION_SLOW = 280

    fun popIn(
        initialScale: Float = 0.85f,
        durationMillis: Int = DURATION_NORMAL,
    ) = scaleIn(
        initialScale = initialScale,
        animationSpec = tween(durationMillis, easing = OpenEasing),
    ) + fadeIn(
        animationSpec = tween(durationMillis - 40, easing = OpenEasing),
    )

    fun popOut(
        targetScale: Float = 0.85f,
        durationMillis: Int = DURATION_FAST,
    ) = scaleOut(
        targetScale = targetScale,
        animationSpec = tween(durationMillis, easing = CloseEasing),
    ) + fadeOut(
        animationSpec = tween(durationMillis - 40, easing = CloseEasing),
    )

    fun slideIn(
        durationMillis: Int = DURATION_SLOW,
    ) = slideInHorizontally(
        initialOffsetX = { it },
        animationSpec = tween(durationMillis, easing = TagEasing),
    ) + fadeIn(
        animationSpec = tween(durationMillis - 60, easing = OpenEasing),
    )

    fun slideOut(
        durationMillis: Int = DURATION_NORMAL,
    ) = slideOutHorizontally(
        targetOffsetX = { it },
        animationSpec = tween(durationMillis, easing = CloseEasing),
    ) + fadeOut(
        animationSpec = tween(durationMillis - 60, easing = CloseEasing),
    )

    fun <T> smoothSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}
