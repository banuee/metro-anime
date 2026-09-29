#!/usr/bin/env bash
# Дамп иерархии UI через uiautomator и Shizuku для анализа агентом

OUT="/sdcard/Download/window_dump.xml"

if ! command -v rish >/dev/null 2>&1; then
    echo "❌ rish не найден."
    exit 1
fi

echo "🔍 Снятие дампа интерфейса..."
rish -c "uiautomator dump '$OUT'" >/dev/null 2>&1

if [ -f "$OUT" ] || rish -c "[ -f '$OUT' ]"; then
    echo "✅ Дамп сохранен в $OUT"
    echo "--- Найденные интерактивные элементы и текст ---"
    rish -c "cat '$OUT'" | grep -oE 'text="[^"]+"' | grep -v 'text=""' | head -n 30
else
    echo "⚠️ Не удалось получить дамп."
fi
