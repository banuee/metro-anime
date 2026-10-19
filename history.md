# Metro Anime — журнал работ (handover)

Проект: `/home/banue/Проекты/metro-anime` (Android, Kotlin 2.x, Jetpack Compose, Media3 1.5.1).
Пакет: `dev.metro.anime` (debug: `dev.metro.anime.debug`).
Опубликованная версия: **0.5.0** (versionCode 32) — релиз https://github.com/banuee/metro-anime/releases/tag/v0.5.0

Обязательный цикл релиза (AGENTS.md):
1. `app/build.gradle.kts`: поднять `versionCode`/`versionName`
2. `git add . && git commit` → `git push origin main`
3. `./tools/release.sh "• ...changelog..."` (собирает подписанный APK, тег, GitHub Release)
4. `graphify update .`

Телефон для тестов: подключён по ADB (`redwood`, Redmi Note 12 Pro, Android 14 / API 34).
Команды: `adb install -r -d app/build/outputs/apk/release/app-release.apk`,
`adb logcat -d -s PlayerScreen:D`, `adb shell uiautomator dump /sdcard/ui.xml` (координаты для `adb shell input tap`).

---

## 1. Задачи, которые поставил пользователь (текущий статус)

| # | Задача | Статус |
|---|--------|--------|
| 1 | **Скачанное не проигрывается в плеере приложения** (в галерее играет) | Частично: причина найдена и почти исправлена, осталось доделать (раздел 3) |
| 2 | В меню «Параметры видео» вместо «Офлайнр» — две секции: «Скачано» (плашка с реальным разрешением) + «Онлайн» (обычный выбор качества) с возможностью переключиться на онлайн | **Не сделано** |
| 3 | Кнопка скачивания у уже скачанной серии: оставить кнопку на месте, сменить только иконку на галочку (скриншот из сообщения пользователя) | **Не сделано** |
| 4 | Порядок вкладок в шапке: `свежее > поиск > история > избранное > скачанное > настройки` | **Не сделано** |
| 5 | В «Скачано» названия отображаются криво («Атака титанов [ТВ-3]» → «Атака титанов 3») и не подгружаются превью (у тех, где есть 404-заглушка Shikimori) | **Не сделано** (диагноз есть, см. 4.4) |

Плюс ранее в сессии сделано и выпущено (0.5.0): скачивание серий в `Movies/MetroAnime`, мультивыбор,
фоновая загрузка с уведомлением, вкладка «Скачано», приоритет локального файла в плеере, кэш страниц аниме.

---

## 2. Что сделано в этой сессии

### 2.1 Диагностика «скачанное не играет»
Проверено на живом устройстве:
- Файл в галерее валиден: `ffprobe` даёт 2 трека (H.264 640x360, AAC-LC 44.1kHz), 1374 с.
- MP4 **некорректен по таймлайну**: `stts` состоит из тысяч записей с дельтой 9 (100 мкс) вместо
  равномерных 3754, а `ctts` содержит 29 909 записей с офсетами ~11280 (3 кадра). Почти все
  PTS немонотонны (14 564 из 32 944) — это следствие того, что в MPEG-TS PTS не монотонны (B-кадры),
  а `MediaMuxer` не умеет с ними работать и пишет сломанные таблицы.
- Media3 (`Mp4Extractor`) на таком файле зависал: `state=BUFFERING`, `position=0`,
  `buffered=72947` — бесконечная загрузка. Онлайн-HLS при этом играл нормально (`state=PLAYING`).
- Логи плеера показали: AAC-декодер получает 1947 буферов и **0 выходов** → аудио-рендерер стоит,
  а playback clock (он же привязан к аудио) не двигается → позиция 0.

### 2.2 Переписан ремукс TS→MP4 (`data/download/HlsDownloader.kt`)
- Два прохода по TS: первый собирает статистику (число сэмплов, длительность, число ADTS-кадров),
  второй пишет MP4.
- Видео: **равномерный CFR-таймлайн** (длительность кадра = span PTS / (N-1)) вместо исходных PTS —
  файл получается стандартным, `ctts` пустой, `stts` равномерный.
- Аудио: ADTS-кадры из одного сэмпла **разделяются** на отдельные AAC-фреймы, в MP4 пишутся
  **без ADTS-заголовка** (payload), плюс добавляется `csd-0` = AudioSpecificConfig (0x11 0x90 для 48 кГц стерео),
  иначе декодер AAC не может сконфигурироваться.
- **Важный грабли:** в первом проходе нельзя забывать `extractor.selectTrack(...)` — без него
  MediaExtractor отдаёт один и тот же сэмпл, и весь таймлайн схлопывается (получился файл длиной 3.7 с).

### 2.3 Добавлено логирование (временно, можно оставить или убрать)
- `PlayerScreen`: `resolve:` (найден ли офлайн-файл и какой URI), `resolve online keys=...`,
  `feed url=... seek=...`, `state=... pos=... buffered=...`, `tick:` (каждые 3 с), `player error:`.
- `HlsDownloader`: `remux plan: video=... frame=...us audio=... adts frames frame=...us`.

### 2.4 Проверенные на устройстве факты
- Онлайн-HLS играет нормально: `state=PLAYING`, позиция идёт.
- Офлайн-файл **после переписывания ремукса**: `dur=1540820` (25:41) — длительность верная,
  `state=READY`, `playing=true`, но `position=0` и снова уход в BUFFERING.
- **Текущий статус по жалобе пользователя:** файл скачался, видео идёт, но примерно 3 fps и **без звука**.
- `ffprobe` на новом файле: структура правильная (видео 36 925 кадров по 41.7 мс, аудио 72 244 кадра по 21.3 мс),
  но ffmpeg ругается: `[aac] Prediction is not allowed in AAC-LC` → payload аудио не совпадает с объявленным ASC.
- Файлы на устройстве: `Голубая шкатулка 2 - S01 _480p_.mp4` (новый ремукс), `Вечера с кошкой _ТВ-1_ - S01 _720p_.mp4`
  и `Чёрный клевер _ТВ-2_ - S01 _360p_.mp4` (старый, сломанный ремукс).

---

## 3. Что осталось по багу воспроизведения (главный приоритет)

Гипотеза (проверить в первую очередь): **ADTS-заголовки в исходном TS врут** —
в них `profile field = 1` (AAC-LC), а фактически payload содержит AAC-LTP
(ffmpeg: «Prediction is not allowed in AAC-LC»). Тогда:
1. Собрать ASC с object type 4 (LTP) вместо 2 (LC) и записать в `csd-0`;
   либо
2. Определить профиль честно (например, попробовать декодировать кадр и посмотреть на ошибки);
   либо
3. Как страховка: **откатиться к хранению .ts** и отдавать в плеер TS-файл (media3 TS-экстрактор
   этот поток играет корректно, т.к. онлайн-HLS с тем же источником работает) —
   но тогда файл не виден в галерее. Компромисс: MP4 для галереи + TS для плеера (расход места ×2).

Дополнительно проверить:
- Правильность разбиения ADTS: сейчас при рассинхроне ищем первую «0xFFF» и идём от неё;
  возможно, нужна непрерывная walked-разбивка по `frame_length` от начала сэмпла
  (иначе кадры смещаются → мусор в битстриме).
- После починки аудио проверить, уйдёт ли «3 fps» (похоже на побочный эффект мёртвого аудио-рендерера).

---

## 4. Остальные задачи (не начаты) — детали

### 4.1 Меню «Параметры видео» (PlayerScreen)
Сейчас: `streamsState = Pair(map, selectedQuality)`; в офлайн-режиме карта содержит только
`OFFLINE_STREAM_KEY = "Офлайн"`, и подпись чипа делается как `"${q}p"` → «Офлайнр».
Нужно:
- держать отдельно `offlineDownload: DownloadedEpisode?` (с реальным качеством) и `onlineStreams: Map<String,String>`;
- в меню показывать две секции:
  - «Скачано» — чип с реальным разрешением (например `360p`/`720p` из записи), выбран по умолчанию;
  - «Онлайн» — чипы качеств (`1080p/720p/480p`) с возможностью переключиться;
- офлайн-источник должен подтягиваться всегда (даже когда играем онлайн), а онлайн-качества —
  резолвиться в фоне, чтобы можно было переключиться;
- подпись качества — единый helper `qualityLabel(q)`: `if (q.toIntOrNull() != null) "${q}p" else q`
  (в `DetailsScreen` такой уже есть, продублировать или вынести в `AnimeModels.kt`).

### 4.2 Иконка кнопки скачивания (DetailsScreen, `EpisodeItemRow`)
Сейчас при `downloaded == true` рисуется голый `Icons.Default.DownloadDone` без фона.
Нужно: оставить ту же кнопку (glass-фон + рамка, 34 dp, `metroClickable`), заменив только иконку
на галочку (`Icons.Default.Check` / `CheckCircle`, тинт `scheme.accent`) — как на скриншоте пользователя.
Логично навесить на неё диалог «Удалить скачанную серию?» (`DownloadManager.removeDownload`).

### 4.3 Порядок вкладок
`HomeTab` (enum, `ui/screens/HomeScreen.kt`): сейчас `LATEST, HISTORY, DOWNLOADS, SEARCH, BOOKMARKS, SETTINGS`.
Нужно: `LATEST, SEARCH, HISTORY, BOOKMARKS, DOWNLOADS, SETTINGS`.
Pager и чипы строятся по `HomeTab.entries` — достаточно поменять порядок в enum
(проверить, что `pageToTab`/`tabToPage` не хардкодят индексы).

### 4.4 Названия и превью в «Скачано»
- `DownloadManager.enqueue` сохраняет `anime.sanitized()` — в записи нет нормального постера
  и название может отличаться от того, что в ленте («Атака титанов [ТВ-3]» → «Атака титанов 3»).
- Причина: в `DetailsScreen` в `enqueue` передаётся `anime` (из `Screen.Details`), а не
  обогащённый `details?.title` (у него есть постер с Shikimori и «правильное» название).
- Фикс: сохранять `details?.title ?: anime` в записи загрузки; в `DownloadManager.loadDownloads()`
  не перетирать сохранённое название на «санитизированное».
- Также в карточке «Скачано» (`DownloadAnimeCard` в HomeScreen) постер пустой — потому что
  в записи нет `posterUrl`; после фикса выше постер подтянется автоматически.

### 4.5 Мелочь по UX
- В `DownloadManager` накопились завершённые/упавшие задачи (`clearFinishedTasks()` нигде не вызывается) —
  стоит чистить их после завершения.
- Имя файла в галерее: `Чёрный клевер _ТВ-2_ - S01 _360p_.mp4` — символы `[ ]` заменяются на `_`
  (это `safeName` в `HlsDownloader`), можно оставить скобки.

---

## 5. Карта изменённых файлов

| Файл | Что там |
|---|---|
| `data/download/HlsDownloader.kt` | **переписан ремукс**: `scanTracks` + `muxTracks`, CFR-таймлайн видео, разделение ADTS, `buildAsc`, логи `remux plan` |
| `data/download/DownloadManager.kt` | очередь, `enqueue/cancel/removeDownload/downloadedFor/getDownloaded`, persistence в `metro_anime_downloads` |
| `data/download/DownloadService.kt` | foreground-service (`dataSync`), канал `downloads`, NOTIFICATION_ID 5001, прогресс в уведомлении |
| `ui/screens/PlayerScreen.kt` | приоритет офлайн-файла + новое логирование; ** TODO: секции «Скачано/Онлайн» в меню качества** |
| `ui/screens/DetailsScreen.kt` | кнопка скачивания, прогресс, мультивыбор, диалог качества; **TODO: иконка-галочка в кнопке** |
| `ui/screens/HomeScreen.kt` | вкладка «Скачано», карточки, чипы серий; **TODO: порядок вкладок, постеры/названия** |
| `MainActivity.kt` | `onPlayDownloaded` → `Screen.Player` с синтетическими эпизодами (`OFFLINE_STREAM_KEY -> fileUri`) |
| `data/model/AnimeModels.kt` | `DownloadStatus`, `DownloadTask`, `DownloadedEpisode`, `OFFLINE_STREAM_KEY` |
| `data/repository/AnimeRepository.kt` | кэш страниц аниме (`anime_details_cache.json`), группа «Скачано (офлайн)» |
| `AndroidManifest.xml` | `FOREGROUND_SERVICE(_DATA_SYNC)`, `WRITE_EXTERNAL_STORAGE` (maxSdk 28), `DownloadService` |

Полезные команды для проверки файла после новой сборки:
```bash
adb pull "/sdcard/Movies/MetroAnime/<файл>.mp4" /tmp/opencode/anime/new.mp4
ffprobe -hide_banner -v error -show_entries stream=codec_type,nb_frames,duration -of default=nw=1 new.mp4
ffmpeg -v error -i new.mp4 -f null -      # декодируется ли без ошибок
```

---

## 6. Замечания по процессам

- `adb shell pm clear dev.metro.anime` **стёр данные приложения** (историю просмотра) — больше так не делать,
  удалять только конкретную загрузку (кнопка «Удалить все серии» в «Скачано»).
- Сборка release: `./gradlew assembleRelease` + `adb install -r -d app/build/outputs/apk/release/app-release.apk`
  (debug-APK нельзя поставить поверх release — разные подписи).
- `adb logcat --pid` на MIUI не работает, фильтровать по тегу: `adb logcat -d -s PlayerScreen:D`.
