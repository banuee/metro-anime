#!/usr/bin/env bash
# Остановка процесса Metro Anime через Shizuku

PKG="${1:-dev.metro.anime.debug}"

if ! command -v rish >/dev/null 2>&1; then
    echo "❌ rish не найден."
    exit 1
fi

echo "🛑 Остановка $PKG..."
rish -c "am force-stop $PKG"
echo "✅ Приложение остановлено."
