package dev.metro.anime.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.res.painterResource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.metro.anime.BuildConfig
import dev.metro.anime.data.AnimeBackupRepository
import dev.metro.anime.data.AutoUpdateManager
import dev.metro.anime.data.AutoUpdateNotificationHelper
import dev.metro.anime.data.ReleaseInfo
import dev.metro.anime.data.UpdateRepository
import dev.metro.anime.data.UpdateState
import dev.metro.anime.data.repository.AnimeRepository
import dev.metro.anime.data.settings.MetroSettingsRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    animeRepo: AnimeRepository,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalMetroScheme.current
    val settings by settingsRepo.settings.collectAsState()
    val wallpaper by settingsRepo.wallpaper.collectAsState()
    val updateState by updateRepo.updateState.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val intervalMinutesList = remember { listOf(0, 10, 30, 60, 180, 360, 720, 1440) }
    val intervalLabels = remember {
        listOf(
            "Никогда",
            "Каждые 10 минут",
            "Каждые 30 минут",
            "Каждый 1 час",
            "Каждые 3 часа",
            "Каждые 6 часов",
            "Каждые 12 часов",
            "Каждые 24 часа",
        )
    }

    var hasNotificationPermission by remember {
        mutableStateOf(AutoUpdateNotificationHelper.canPostNotifications(context))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasNotificationPermission = granted || AutoUpdateNotificationHelper.canPostNotifications(context)
    }

    val initialSliderIndex = remember(settings.autoUpdateIntervalMinutes) {
        val idx = intervalMinutesList.indexOf(settings.autoUpdateIntervalMinutes)
        if (idx >= 0) idx.toFloat() else 0f
    }
    var sliderValue by remember(initialSliderIndex) { mutableFloatStateOf(initialSliderIndex) }

    val pickPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            settingsRepo.setCustomWallpaper(uri)
        }
    }

    var backupStatus by remember { mutableStateOf<String?>(null) }
    var backupError by remember { mutableStateOf(false) }
    var backupWorking by remember { mutableStateOf(false) }

    var cacheSizeBytes by remember { mutableLongStateOf(0L) }
    var isClearingCache by remember { mutableStateOf(false) }
    var cacheClearedMsg by remember { mutableStateOf(false) }

    var serostClickCount by remember { mutableIntStateOf(0) }
    var lastSerostClickTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        cacheSizeBytes = updateRepo.getCacheSizeBytes()
    }

    val backupRepo = remember { AnimeBackupRepository(context) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            backupWorking = true
            backupStatus = "Экспорт данных..."
            backupError = false
            scope.launch {
                val res = backupRepo.exportBackup(uri, settingsRepo, animeRepo)
                backupWorking = false
                backupError = !res.success
                backupStatus = res.message ?: if (res.success) "Резервная копия успешно создана" else "Ошибка экспорта"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            backupWorking = true
            backupStatus = "Восстановление данных..."
            backupError = false
            scope.launch {
                val res = backupRepo.importBackup(uri, settingsRepo, animeRepo)
                backupWorking = false
                backupError = !res.success
                backupStatus = res.message ?: if (res.success) "Данные успешно восстановлены" else "Ошибка импорта"
            }
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
                            if (settings.serostMode) {
                                Image(
                                    painter = painterResource(dev.metro.anime.R.drawable.serost_cat),
                                    contentDescription = "Серость",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (settings.serostMode) "Плитка в стиле Серости" else "Плитка в стиле Metro",
                                fontFamily = MetroFonts.headline,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = scheme.text,
                            )
                            Text(
                                text = if (settings.serostMode) "Самый серый и пушистый режим 🐱" else "Акцент, полупрозрачный акрил и шрифт Segoe UI",
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

                    // Автоматическое сканирование на обновления
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                            .background(scheme.glassHover)
                            .border(1.dp, scheme.strokeStrong, RoundedCornerShape(MetroDimens.radiusSmall))
                            .padding(14.dp),
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "АВТОМАТИЧЕСКОЕ СКАНИРОВАНИЕ",
                                        fontSize = 11.sp,
                                        fontFamily = MetroFonts.text,
                                        fontWeight = FontWeight.SemiBold,
                                        color = scheme.textDim,
                                        letterSpacing = 1.sp,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = intervalLabels.getOrElse(sliderValue.roundToInt()) { "Никогда" },
                                        fontSize = 16.sp,
                                        fontFamily = MetroFonts.headline,
                                        fontWeight = FontWeight.Normal,
                                        color = scheme.accent,
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(scheme.glass),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Schedule,
                                        contentDescription = null,
                                        tint = scheme.accent,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }

                            Slider(
                                value = sliderValue,
                                onValueChange = { newValue ->
                                    sliderValue = newValue
                                    val idx = newValue.roundToInt().coerceIn(0, intervalMinutesList.lastIndex)
                                    val minutes = intervalMinutesList[idx]
                                    settingsRepo.setAutoUpdateInterval(minutes)
                                    AutoUpdateManager.schedule(context, minutes)

                                    if (minutes > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                valueRange = 0f..7f,
                                steps = 6,
                                colors = SliderDefaults.colors(
                                    thumbColor = scheme.accent,
                                    activeTrackColor = scheme.accent,
                                    inactiveTrackColor = scheme.glass,
                                ),
                            )

                            val activeMinutes = intervalMinutesList.getOrElse(sliderValue.roundToInt()) { 0 }
                            if (activeMinutes > 0) {
                                if (!hasNotificationPermission) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(scheme.red.copy(alpha = 0.12f))
                                            .border(1.dp, scheme.red.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Уведомления отключены",
                                                color = scheme.red,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = MetroFonts.text,
                                            )
                                            Text(
                                                text = "Разрешите уведомления для оповещения о новых релизах",
                                                color = scheme.textDim,
                                                fontSize = 11.sp,
                                                fontFamily = MetroFonts.text,
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(scheme.red)
                                                .metroClickable(targetScale = 0.94f) {
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                    } else {
                                                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        }
                                                        context.startActivity(intent)
                                                    }
                                                }
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                        ) {
                                            Text(
                                                text = "Включить",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = MetroFonts.text,
                                            )
                                        }
                                    }
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(horizontal = 2.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF339933)),
                                        )
                                        Text(
                                            text = "Уведомления о релизах включены",
                                            color = scheme.textDim,
                                            fontSize = 11.sp,
                                            fontFamily = MetroFonts.text,
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = "Автоматическая проверка выключена. Обновления проверяются только вручную.",
                                    color = scheme.textDim.copy(alpha = 0.65f),
                                    fontSize = 11.sp,
                                    fontFamily = MetroFonts.text,
                                    modifier = Modifier.padding(horizontal = 2.dp),
                                )
                            }
                        }
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
            // Backup and Restore Section
            // =================================================================
            item {
                Text(
                    text = "РЕЗЕРВНАЯ КОПИЯ И ЭКСПОРТ",
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
                    if (backupStatus != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                                .background(if (backupError) scheme.red.copy(alpha = 0.15f) else scheme.accent.copy(alpha = 0.15f))
                                .border(1.dp, if (backupError) scheme.red.copy(alpha = 0.5f) else scheme.accent.copy(alpha = 0.5f), RoundedCornerShape(MetroDimens.radiusSmall))
                                .padding(12.dp),
                        ) {
                            Text(
                                text = backupStatus ?: "",
                                color = if (backupError) scheme.red else scheme.accent,
                                fontSize = 13.sp,
                                fontFamily = MetroFonts.text,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }

                    Text(
                        text = "Экспорт и импорт всех настроек, фоновых обоев, закладок избранного и полной истории просмотров с прогрессом серий в один портативный файл .json.",
                        color = scheme.textDim,
                        fontSize = 12.sp,
                        fontFamily = MetroFonts.text,
                        lineHeight = 16.sp,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MetroButton(
                            text = "Экспорт",
                            isPrimary = true,
                            enabled = !backupWorking,
                            onClick = {
                                val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                exportLauncher.launch("metro-anime-backup-$dateStr.json")
                            },
                            modifier = Modifier.weight(1f),
                        )

                        MetroButton(
                            text = "Импорт",
                            isPrimary = false,
                            enabled = !backupWorking,
                            onClick = {
                                importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // =================================================================
            // Cache and Storage Section
            // =================================================================
            item {
                Text(
                    text = "КЭШ И ХРАНИЛИЩЕ",
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
                    val sizeText = when {
                        cacheSizeBytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f МБ", cacheSizeBytes / (1024f * 1024f))
                        cacheSizeBytes >= 1024 -> "${cacheSizeBytes / 1024} КБ"
                        else -> "$cacheSizeBytes Б"
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "Занято кэшем",
                                fontFamily = MetroFonts.headline,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = scheme.text,
                            )
                            Text(
                                text = "Обложки аниме и временные файлы",
                                fontFamily = MetroFonts.text,
                                fontSize = 12.sp,
                                color = scheme.textDim,
                            )
                        }

                        Text(
                            text = sizeText,
                            fontFamily = MetroFonts.headline,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = scheme.accent,
                        )
                    }

                    if (cacheClearedMsg) {
                        Text(
                            text = "✓ Кэш успешно очищен",
                            fontFamily = MetroFonts.text,
                            fontSize = 12.sp,
                            color = Color(0xFF339933),
                        )
                    }

                    MetroButton(
                        text = if (isClearingCache) "Очистка..." else "Очистить кэш",
                        isPrimary = false,
                        enabled = !isClearingCache && cacheSizeBytes > 0L,
                        onClick = {
                            scope.launch {
                                isClearingCache = true
                                updateRepo.clearAllCache()
                                cacheSizeBytes = updateRepo.getCacheSizeBytes()
                                isClearingCache = false
                                cacheClearedMsg = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // =================================================================
            // About App
            // =================================================================
            item {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MetroDimens.radius))
                        .background(if (settings.serostMode) scheme.glassHover else Color.Transparent)
                        .border(
                            width = if (settings.serostMode) 1.dp else 0.dp,
                            color = if (settings.serostMode) scheme.accent.copy(alpha = 0.5f) else Color.Transparent,
                            shape = RoundedCornerShape(MetroDimens.radius),
                        )
                        .metroClickable(
                            targetScale = 0.96f,
                            onClick = {
                                val now = System.currentTimeMillis()
                                if (now - lastSerostClickTime > 1500L) {
                                    serostClickCount = 1
                                } else {
                                    serostClickCount++
                                }
                                lastSerostClickTime = now

                                if (serostClickCount >= 5) {
                                    serostClickCount = 0
                                    scope.launch {
                                        val newState = settingsRepo.toggleSerostMode()
                                        val text = if (newState) "Серость активирована 🐱" else "Серость отключена"
                                        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        )
                        .padding(vertical = 12.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (settings.serostMode) {
                            Image(
                                painter = painterResource(dev.metro.anime.R.drawable.serost_cat),
                                contentDescription = "Серость",
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .border(1.5.dp, scheme.accent, CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(
                            text = if (settings.serostMode) "СЕРОСТЬ АНИМЕ 🐱" else "METRO ANIME",
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
                            color = if (settings.serostMode) scheme.accent else scheme.textDim,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}
