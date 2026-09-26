package dev.metro.anime.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.metro.anime.ui.theme.LocalMetroScheme
import dev.metro.anime.ui.theme.MetroDimens
import dev.metro.anime.ui.theme.MetroFonts
import dev.metro.anime.ui.theme.metroClickable

@Composable
fun MetroHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalMetroScheme.current
    Column(modifier = modifier.fillMaxWidth()) {
        // Accent pill indicator
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(scheme.accent)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title.uppercase(),
            fontFamily = MetroFonts.headline,
            fontWeight = FontWeight.Light,
            fontSize = 26.sp,
            letterSpacing = 1.8.sp,
            color = scheme.text,
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontFamily = MetroFonts.text,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                color = scheme.textDim,
            )
        }
    }
}

@Composable
fun MetroTopBar(
    title: String,
    onBackClick: () -> Unit,
    trailingAction: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalMetroScheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetroIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Назад",
            onClick = onBackClick,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            fontFamily = MetroFonts.headline,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            color = scheme.text,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        trailingAction?.invoke()
    }
}

@Composable
fun MetroIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    size: Dp = 40.dp,
) {
    val scheme = LocalMetroScheme.current
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
            .background(scheme.glass)
            .border(MetroDimens.strokeWidth, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
            .metroClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: scheme.text,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun MetroSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    placeholder: String = "Поиск аниме...",
    modifier: Modifier = Modifier,
) {
    val scheme = LocalMetroScheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
            .background(scheme.glassHover)
            .border(MetroDimens.strokeWidth, scheme.strokeStrong, RoundedCornerShape(MetroDimens.radiusSmall))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "Поиск",
            tint = scheme.accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = placeholder,
                    fontFamily = MetroFonts.text,
                    fontSize = 14.sp,
                    color = scheme.textDim,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = MetroFonts.text,
                    fontSize = 14.sp,
                    color = scheme.text,
                ),
                cursorBrush = SolidColor(scheme.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .metroClickable { onQueryChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Очистить",
                    tint = scheme.textDim,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
fun MetroChip(
    text: String,
    isSelected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalMetroScheme.current
    val bg = if (isSelected) scheme.accent.copy(alpha = 0.85f) else scheme.glass
    val border = if (isSelected) scheme.accent else scheme.stroke
    val textColor = if (isSelected) Color.White else scheme.textDim

    val clickMod = if (onClick != null) Modifier.metroClickable(onClick = onClick) else Modifier

    Box(
        modifier = modifier
            .then(clickMod)
            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
            .background(bg)
            .border(MetroDimens.strokeWidth, border, RoundedCornerShape(MetroDimens.radiusSmall))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontFamily = MetroFonts.text,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 12.sp,
            color = textColor,
        )
    }
}

@Composable
fun MetroButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = true,
) {
    val scheme = LocalMetroScheme.current
    val bg = if (isPrimary) scheme.accent else scheme.glassHover
    val border = if (isPrimary) scheme.accent else scheme.strokeStrong

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
            .background(bg)
            .border(MetroDimens.strokeWidth, border, RoundedCornerShape(MetroDimens.radiusSmall))
            .metroClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            fontFamily = MetroFonts.headline,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            letterSpacing = 1.sp,
            color = Color.White,
        )
    }
}
