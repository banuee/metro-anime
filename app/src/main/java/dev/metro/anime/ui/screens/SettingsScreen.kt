package dev.metro.anime.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.metro.anime.BuildConfig
import dev.metro.anime.data.ReleaseInfo
import dev.metro.anime.data.UpdateRepository
import dev.metro.anime.data.UpdateState
import dev.metro.anime.data.settings.MetroSettingsRepository
import kotlinx.coroutines.launch
import dev.metro.anime.ui.components.MetroButton
import dev.metro.anime.ui.components.MetroIconButton
import dev.metro.anime.ui.components.MetroTopBar
import dev.metro.anime.ui.theme.FrostedGlassBox
import dev.metro.anime.ui.theme.LocalMetroScheme
import dev.metro.anime.ui.theme.MetroDimens
import dev.metro.anime.ui.theme.MetroFonts
import dev.metro.anime.ui.theme.metroClickable
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settingsRepo: MetroSettingsRepository,
    updateRepo: UpdateRepository,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalMetroScheme.current
    val settings by settingsRepo.settings.collectAsState()
    val wallpaper by settingsRepo.wallpaper.collectAsState()
    val updateState by updateRepo.updateState.collectAsState()
    val scope = rememberCoroutineScope()

    val pickPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            settingsRepo.setCustomWallpaper(uri)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Top Bar
        MetroTopBar(
            title = "Параметры",
            onBackClick = onBackClick,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // =================================================================
            // Wallpaper Section
            // =================================================================
            item {
                Text(
                    text = "ОБОИ И ЗАДНИЙ ФОН",
                    fontFamily = MetroFonts.headline,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    letterSpacing = 1.2.sp,
                    color = scheme.textDim,
                )
                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(MetroDimens.radius))
                        .background(scheme.glass)
                        .border(1.dp, scheme.strokeStrong, RoundedCornerShape(MetroDimens.radius)),
                    contentAlignment = Alignment.Center,
                ) {
                    val wp = wallpaper
                    if (wp != null) {
                        Image(
                            bitmap = if (settings.blurEnabled) wp.blurred else wp.sharp,
                            contentDescription = "Текущие обои",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // Dim overlay preview
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = settings.backgroundDim)),
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                tint = scheme.textDim,
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Стандартный тёмный фон Metro",
                                fontFamily = MetroFonts.text,
                                fontSize = 13.sp,
                                color = scheme.textDim,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MetroButton(
                        text = "Выбрать обои...",
                        onClick = {
                            pickPhotoLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )

                    if (settings.hasCustomWallpaper) {
                        MetroButton(
                            text = "Сбросить",
                            isPrimary = false,
                            onClick = { settingsRepo.clearWallpaper() },
                        )
                    }
                }
            }

            // =================================================================
            // Blur & Dimming Section
            // =================================================================
            item {
                Text(
                    text = "РАЗМЫТИЕ И ЗАТЕМНЕНИЕ",
                    fontFamily = MetroFonts.headline,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    letterSpacing = 1.2.sp,
                    color = scheme.textDim,
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MetroDimens.radius))
                        .background(scheme.glass)
                        .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radius))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Blur toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Размытие фона (блюр)",
                                fontFamily = MetroFonts.headline,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = scheme.text,
                            )
                            Text(
                                text = "Мягкий акриловый фрост для читаемости",
                                fontFamily = MetroFonts.text,
                                fontSize = 12.sp,
                                color = scheme.textDim,
                            )
                        }
                        Switch(
                            checked = settings.blurEnabled,
                            onCheckedChange = { settingsRepo.setBlurEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = scheme.accent,
                                checkedTrackColor = scheme.glassHover,
                                uncheckedThumbColor = scheme.textDim,
                                uncheckedTrackColor = scheme.glass,
                            ),
                        )
                    }

                    // Blur Radius Slider
                    if (settings.blurEnabled) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Сила размытия",
                                    fontFamily = MetroFonts.text,
                                    fontSize = 13.sp,
                                    color = scheme.text,
                                )
                                Text(
                                    text = "${settings.blurRadius} px",
                                    fontFamily = MetroFonts.text,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = scheme.accent,
                                )
                            }
                            Slider(
                                value = settings.blurRadius.toFloat(),
                                onValueChange = { settingsRepo.setBlurRadius(it.roundToInt()) },
                                valueRange = 2f..30f,
                                colors = SliderDefaults.colors(
                                    thumbColor = scheme.accent,
                                    activeTrackColor = scheme.accent,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                                ),
                            )
                        }
                    }

                    // Background Dimming Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Затемнение фона",
                                fontFamily = MetroFonts.text,
                                fontSize = 13.sp,
                                color = scheme.text,
                            )
                            Text(
                                text = "${(settings.backgroundDim * 100).roundToInt()}%",
                                fontFamily = MetroFonts.text,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = scheme.accent,
                            )
                        }
                        Slider(
                            value = settings.backgroundDim,
                            onValueChange = { settingsRepo.setBackgroundDim(it) },
                            valueRange = 0.15f..0.85f,
                            colors = SliderDefaults.colors(
                                thumbColor = scheme.accent,
                                activeTrackColor = scheme.accent,
                                inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                            ),
                        )
                    }
                }
            }

            // =================================================================
            // Wallpaper Extracted Palette Section
            // =================================================================
            if (settings.hasCustomWallpaper && settings.wallpaperPalette.isNotEmpty()) {
                item {
                    Text(
                        text = "ЦВЕТА ИЗ ОБОЕВ",
                        fontFamily = MetroFonts.headline,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        letterSpacing = 1.2.sp,
                        color = scheme.textDim,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MetroDimens.radius))
                            .background(scheme.glass)
                            .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radius))
                            .padding(14.dp),
                    ) {
                        Text(
                            text = "Палитра, автоматически выделенная из вашего фона:",
                            fontFamily = MetroFonts.text,
                            fontSize = 12.sp,
                            color = scheme.textDim,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        val paletteChunked = settings.wallpaperPalette.chunked(4)
                        paletteChunked.forEachIndexed { rowIndex, rowColors ->
                            if (rowIndex > 0) Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                rowColors.forEach { colorInt ->
                                    val isSelected = settings.accentColor == colorInt
                                    val swatchColor = Color(colorInt)
                                    val borderW by animateDpAsState(
                                        targetValue = if (isSelected) 2.5.dp else 1.dp,
                                        label = "palette_bw",
                                    )
                                    val borderColor by animateColorAsState(
                                        targetValue = if (isSelected) Color.White else Color.White.copy(alpha = 0.25f),
                                        label = "palette_bc",
                                    )
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.width(68.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(50.dp)
                                                .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                                                .background(swatchColor)
                                                .border(borderW, borderColor, RoundedCornerShape(MetroDimens.radiusSmall))
                                                .metroClickable {
                                                    settingsRepo.setAccentColor(colorInt)
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Выбран",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(24.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =================================================================
            // Preset Accent Color Section
            // =================================================================
            item {
                Text(
                    text = "ПРЕДУСТАНОВЛЕННЫЕ METRO АКЦЕНТЫ",
                    fontFamily = MetroFonts.headline,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    letterSpacing = 1.2.sp,
                    color = scheme.textDim,
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MetroDimens.radius))
                        .background(scheme.glass)
                        .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radius))
                        .padding(14.dp),
                ) {
                    val presets = MetroSettingsRepository.ACCENT_PRESETS
                    // Render in rows of 4
                    val chunked = presets.chunked(4)
                    chunked.forEachIndexed { rowIndex, rowItems ->
                        if (rowIndex > 0) Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            rowItems.forEach { option ->
                                val isSelected = settings.accentColor == option.colorInt
                                val swatchColor = Color(option.colorInt)
                                val borderW by animateDpAsState(
                                    targetValue = if (isSelected) 2.5.dp else 1.dp,
                                    label = "swatch_bw",
                                )
                                val borderColor by animateColorAsState(
                                    targetValue = if (isSelected) Color.White else Color.White.copy(alpha = 0.25f),
                                    label = "swatch_bc",
                                )

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(68.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(50.dp)
                                            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                                            .background(swatchColor)
                                            .border(borderW, borderColor, RoundedCornerShape(MetroDimens.radiusSmall))
                                            .metroClickable {
                                                settingsRepo.setAccentColor(option.colorInt)
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Выбран",
                                                tint = Color.White,
                                                modifier = Modifier.size(24.dp),
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = option.name,
                                        fontFamily = MetroFonts.text,
                                        fontSize = 10.sp,
                                        color = if (isSelected) scheme.accent else scheme.textDim,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =================================================================
            // Style Preview Tile
            // =================================================================
            item {
                Text(
                    text = "ПРЕДПРОСМОТР СТИЛЯ",
                    fontFamily = MetroFonts.headline,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    letterSpacing = 1.2.sp,
                    color = scheme.textDim,
                )
                Spacer(modifier = Modifier.height(8.dp))

                FrostedGlassBox(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MetroDimens.radius,
                    tint = scheme.glassHover,
                    borderColor = scheme.accent.copy(alpha = 0.50f),
                    borderWidth = 1.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                                .background(scheme.accent.copy(alpha = 0.85f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Плитка в стиле Metro",
                                fontFamily = MetroFonts.headline,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = scheme.text,
                            )
                            Text(
                                text = "Акцент, полупрозрачный акрил и шрифт Segoe UI",
                                fontFamily = MetroFonts.text,
                                fontSize = 12.sp,
                                color = scheme.accent,
                            )
                        }
                    }
                }
            }

            // =================================================================
            // GitHub OTA Updates Section
            // =================================================================
            item {
                Text(
                    text = "ОБНОВЛЕНИЕ ПО ВОЗДУХУ (GITHUB OTA)",
                    fontFamily = MetroFonts.headline,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    letterSpacing = 1.2.sp,
                    color = scheme.textDim,
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MetroDimens.radius))
                        .background(scheme.glass)
                        .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radius))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column {
                        Text(
                            text = "Текущая версия: v${BuildConfig.VERSION_NAME}",
                            fontFamily = MetroFonts.headline,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = scheme.text,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Репозиторий: banuee/metro-anime",
                            fontFamily = MetroFonts.text,
                            fontSize = 12.sp,
                            color = scheme.textDim,
                        )
                    }

                    when (val state = updateState) {
                        is UpdateState.Idle -> {
                            MetroButton(
                                text = "Проверить обновления",
                                onClick = {
                                    scope.launch { updateRepo.checkForUpdates() }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        is UpdateState.Checking -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = scheme.accent,
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = "Проверка наличия новых релизов...",
                                    fontFamily = MetroFonts.text,
                                    fontSize = 13.sp,
                                    color = scheme.text,
                                )
                            }
                        }

                        is UpdateState.UpToDate -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "✓ У вас установлена самая последняя версия",
                                    fontFamily = MetroFonts.text,
                                    fontSize = 13.sp,
                                    color = Color(0xFF339933),
                                )
                                MetroButton(
                                    text = "Проверить снова",
                                    isPrimary = false,
                                    onClick = {
                                        scope.launch { updateRepo.checkForUpdates() }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        is UpdateState.Available -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Доступна новая версия: v${state.info.versionName}",
                                    fontFamily = MetroFonts.headline,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = scheme.accent,
                                )
                                if (state.info.changelog.isNotBlank()) {
                                    Text(
                                        text = state.info.changelog,
                                        fontFamily = MetroFonts.text,
                                        fontSize = 12.sp,
                                        color = scheme.textDim,
                                        maxLines = 4,
                                    )
                                }
                                val mb = state.info.apkSize / (1024 * 1024)
                                val sizeText = if (mb > 0) "$mb МБ" else "${state.info.apkSize / 1024} КБ"
                                MetroButton(
                                    text = "Скачать и обновить ($sizeText)",
                                    onClick = {
                                        scope.launch { updateRepo.downloadUpdate(state.info) }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        is UpdateState.Downloading -> {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Загрузка обновления: ${(state.progress * 100).toInt()}%",
                                    fontFamily = MetroFonts.text,
                                    fontSize = 13.sp,
                                    color = scheme.text,
                                )
                                LinearProgressIndicator(
                                    progress = { state.progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = scheme.accent,
                                    trackColor = Color.White.copy(alpha = 0.15f),
                                )
                            }
                        }

                        is UpdateState.ReadyToInstall -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Файл обновления загружен и готов к установке",
                                    fontFamily = MetroFonts.text,
                                    fontSize = 13.sp,
                                    color = scheme.accent,
                                )
                                MetroButton(
                                    text = "Установить обновление сейчас",
                                    onClick = {
                                        updateRepo.installApk(state.apkFile)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        is UpdateState.Error -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Ошибка: ${state.message}",
                                    fontFamily = MetroFonts.text,
                                    fontSize = 13.sp,
                                    color = scheme.red,
                                )
                                MetroButton(
                                    text = "Повторить попытку",
                                    isPrimary = false,
                                    onClick = {
                                        scope.launch { updateRepo.checkForUpdates() }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }

            // =================================================================
            // About App
            // =================================================================
            item {
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "METRO ANIME",
                        fontFamily = MetroFonts.headline,
                        fontWeight = FontWeight.Light,
                        fontSize = 18.sp,
                        letterSpacing = 2.sp,
                        color = scheme.text,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Версия ${BuildConfig.VERSION_NAME} • Quickshell Fluent Style",
                        fontFamily = MetroFonts.text,
                        fontSize = 12.sp,
                        color = scheme.textDim,
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}
