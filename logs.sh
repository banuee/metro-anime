#!/usr/bin/env bash
# Просмотр логов приложения Metro Anime через Shizuku

FILTER="${1:-default}"

if ! command -v rish >/dev/null 2>&1; then
    echo "❌ rish не найден. Убедитесь, что Shizuku запущен и rish настроен."
    exit 1
fi

if [ "$FILTER" = "all" ]; then
    echo "📋 Вывод всех последних логов metro.anime..."
    rish -c "logcat -d | grep -i 'dev.metro.anime' | tail -n 80"
elif [ "$FILTER" = "crash" ]; then
    echo "💥 Поиск фатальных ошибок и крашей..."
    rish -c "logcat -d -s AndroidRuntime:E *:F | tail -n 60"
else
    echo "📺 Логи плеера и сессии (PlayerScreen, ExoPlayer, MediaSession):"
    rish -c "logcat -d -s PlayerScreen:D ExoPlayer:D MediaSessionService:D *:E | tail -n 50"
fi
