package dev.metro.anime.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.metro.anime.R

object MetroFonts {
    val text = FontFamily(
        Font(R.font.segoe_text_light, FontWeight.Light),
        Font(R.font.segoe_text, FontWeight.Normal),
        Font(R.font.segoe_text_semibold, FontWeight.SemiBold),
    )
    val headline = FontFamily(
        Font(R.font.segoe_display_light, FontWeight.Light),
        Font(R.font.segoe_display_semibold, FontWeight.SemiBold),
    )
    val icon = FontFamily(
        Font(R.font.noto_sans_nerd_font, FontWeight.Normal),
    )
}
