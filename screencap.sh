#!/usr/bin/env bash
# Создание скриншота экрана через Shizuku

OUT="/sdcard/Download/screen.png"
if [ -n "$1" ]; then
    OUT="$1"
fi

if ! command -v rish >/dev/null 2>&1; then
    echo "❌ rish не найден."
    exit 1
fi

echo "📸 Снимок экрана в $OUT..."
rish -c "screencap -p '$OUT'"
echo "✅ Скриншот сохранен: $OUT"
