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

        // Badges: Top Right Rating or Year
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (anime.rating != null && anime.rating > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.70f))
                        .border(1.dp, scheme.accent.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "★ ${String.format("%.1f", anime.rating)}",
                        fontFamily = MetroFonts.text,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = scheme.accent,
                    )
                }
            } else if (anime.year != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.70f))
                        .border(1.dp, scheme.stroke, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${anime.year}",
                        fontFamily = MetroFonts.text,
                        fontWeight = FontWeight.Normal,
                        fontSize = 11.sp,
                        color = scheme.textDim,
                    )
                }
            }
        }

        // Bottom text: Title, Season / Episodes
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
                    anime.type != null -> anime.type
                    else -> "Сериал"
                }
                Text(
                    text = epText,
                    fontFamily = MetroFonts.text,
                    fontSize = 11.sp,
                    color = scheme.textDim,
                )

                if (anime.safeGenres.isNotEmpty()) {
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
