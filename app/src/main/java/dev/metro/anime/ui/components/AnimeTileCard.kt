package dev.metro.anime.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.metro.anime.data.model.AnimeTitle
import dev.metro.anime.ui.theme.LocalMetroScheme
import dev.metro.anime.ui.theme.MetroDimens
import dev.metro.anime.ui.theme.MetroFonts
import dev.metro.anime.ui.theme.metroClickable

@Composable
fun AnimeTileCard(
    anime: AnimeTitle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalMetroScheme.current
    val isSerost = dev.metro.anime.ui.theme.LocalSerostMode.current
    val posterModel: Any? = if (isSerost) dev.metro.anime.R.drawable.serost_cat else anime.posterUrl

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.68f)
            .clip(RoundedCornerShape(MetroDimens.radius))
            .background(scheme.glass)
            .border(MetroDimens.strokeWidth, scheme.stroke, RoundedCornerShape(MetroDimens.radius))
            .metroClickable(onClick = onClick),
    ) {
        // Poster Image
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(posterModel)
                .crossfade(true)
                .size(360, 520)
                .build(),
            contentDescription = anime.titleRu,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Gradient for readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.40f),
                            Color(0xFF0A0A0D).copy(alpha = 0.95f),
                        ),
                        startY = 180f,
                    )
                )
        )

        // Bottom text: Title, Metadata (Rating/Year • Episodes / Type, Genre)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            Text(
                text = anime.titleRu,
                fontFamily = MetroFonts.headline,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = scheme.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val epText = when {
                    anime.episodesCount != null -> "${anime.episodesCount} эп."
                    anime.type != null -> when (anime.type.lowercase().trim()) {
                        "anime-serial", "tv", "serial", "сериал" -> "Сериал"
                        "anime", "movie", "фильм" -> "Фильм"
                        "ova", "ова" -> "OVA"
                        "ona", "она" -> "ONA"
                        "special", "спешл", "спэшл" -> "Спешл"
                        else -> anime.type
                    }
                    else -> "Сериал"
                }

                val metaInfo = buildString {
                    if (anime.rating != null && anime.rating > 0) {
                        append("★ ${String.format(java.util.Locale.US, "%.1f", anime.rating)} • ")
                    } else if (anime.year != null) {
                        append("${anime.year} • ")
                    }
                    append(epText)
                }

                Text(
                    text = metaInfo,
                    fontFamily = MetroFonts.text,
                    fontSize = 11.sp,
                    color = scheme.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                if (anime.safeGenres.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = anime.safeGenres.first(),
                        fontFamily = MetroFonts.text,
                        fontSize = 10.sp,
                        color = scheme.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
