#!/usr/bin/env bash
set -e

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

echo "⚙️ Сборка debug APK..."
./gradlew assembleDebug

if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK не найден: $APK_PATH"
    exit 1
fi

echo "📲 Установка через Shizuku (rish)..."
if command -v rish >/dev/null 2>&1; then
    rish -c "pm install -r -d $(realpath "$APK_PATH")"
    echo "🚀 Запуск приложения..."
    rish -c "am start -n dev.metro.anime.debug/dev.metro.anime.MainActivity"
    echo "✅ Приложение запущено!"
else
    echo "⚠️ rish не найден в PATH. Попробуйте установить APK вручную:"
    echo "   pm install -r -d $APK_PATH"
fi
