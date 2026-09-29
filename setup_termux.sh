#!/usr/bin/env bash
# Metro Anime — Скрипт развертывания рабочего окружения для Termux и proot-distro
set -e

echo "=================================================="
echo "🚀 Metro Anime — Развертывание окружения (Termux)"
echo "=================================================="

CURRENT_DIR="$(pwd)"

# 1. Проверка наличия архива и распаковка, если запущен рядом с metro-anime.zip
ZIP_FILE=""
if [ -f "$1" ]; then
    ZIP_FILE="$1"
elif [ -f "metro-anime.zip" ]; then
    ZIP_FILE="metro-anime.zip"
fi

if [ -n "$ZIP_FILE" ]; then
    echo "📦 Обнаружен архив: $ZIP_FILE"
    echo "Распаковка в текущую директорию..."
    if command -v unzip >/dev/null 2>&1; then
        unzip -q -o "$ZIP_FILE"
    else
        echo "⚠️ Утилита unzip не найдена, пытаемся установить..."
        if command -v pkg >/dev/null 2>&1; then
            pkg update -y && pkg install -y unzip
            unzip -q -o "$ZIP_FILE"
        elif command -v apt-get >/dev/null 2>&1; then
            apt-get update && apt-get install -y unzip
            unzip -q -o "$ZIP_FILE"
        else
            echo "❌ Не удалось найти unzip. Распакуйте архив вручную."
            exit 1
        fi
    fi
    echo "✅ Распаковка завершена."
fi

# 2. Определение окружения: Native Termux vs proot-distro vs Linux
IS_TERMUX=false
IS_PROOT=false

if [ -d "/data/data/com.termux" ] && [ -n "$TERMUX_VERSION" ]; then
    IS_TERMUX=true
    echo "📱 Окружение: Нативный Termux ($TERMUX_VERSION)"
elif [ -f "/etc/os-release" ] && grep -qiE "ubuntu|debian" /etc/os-release; then
    IS_PROOT=true
    echo "🐧 Окружение: Linux / proot-distro ($(grep -i PRETTY_NAME /etc/os-release | cut -d= -f2 | tr -d '\"'))"
else
    echo "💻 Окружение: Стандартный Linux shell"
fi

# 3. Проверка и установка базовых утилит
echo "🔍 Проверка необходимых пакетов..."

if [ "$IS_TERMUX" = true ]; then
    MISSING_PKGS=""
    command -v git >/dev/null 2>&1 || MISSING_PKGS="$MISSING_PKGS git"
    command -v java >/dev/null 2>&1 || MISSING_PKGS="$MISSING_PKGS openjdk-17"
    command -v curl >/dev/null 2>&1 || MISSING_PKGS="$MISSING_PKGS curl"
    command -v aapt2 >/dev/null 2>&1 || MISSING_PKGS="$MISSING_PKGS aapt android-tools"

    if [ -n "$MISSING_PKGS" ]; then
        echo "📥 Установка недостающих пакетов Termux:$MISSING_PKGS..."
        pkg update -y
        pkg install -y $MISSING_PKGS
    fi

    # Настройка aapt2 оверрайда для Termux на ARM64
    TERMUX_AAPT2="/data/data/com.termux/files/usr/bin/aapt2"
    if [ -f "$TERMUX_AAPT2" ]; then
        echo "🔧 Настройка aapt2 ARM64 для Gradle..."
        if ! grep -q "android.aapt2FromMavenOverride" gradle.properties 2>/dev/null; then
            echo "" >> gradle.properties
            echo "# Termux ARM64 native aapt2 override" >> gradle.properties
            echo "android.aapt2FromMavenOverride=$TERMUX_AAPT2" >> gradle.properties
        fi
    fi

    # Оптимизация памяти JVM под ресурсы телефона
    if ! grep -q "org.gradle.jvmargs" gradle.properties 2>/dev/null; then
        echo "org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=384m" >> gradle.properties
    fi

elif [ "$IS_PROOT" = true ]; then
    MISSING_PKGS=""
    command -v git >/dev/null 2>&1 || MISSING_PKGS="$MISSING_PKGS git"
    command -v java >/dev/null 2>&1 || MISSING_PKGS="$MISSING_PKGS openjdk-17-jdk"
    command -v curl >/dev/null 2>&1 || MISSING_PKGS="$MISSING_PKGS curl"
    command -v unzip >/dev/null 2>&1 || MISSING_PKGS="$MISSING_PKGS unzip"

    if [ -n "$MISSING_PKGS" ]; then
        echo "📥 Установка пакетов в proot-distro:$MISSING_PKGS..."
        apt-get update -y
        apt-get install -y $MISSING_PKGS
    fi
fi

# 4. Проверка и настройка Android SDK
echo "📱 Настройка Android SDK..."
if [ ! -f "local.properties" ]; then
    POSSIBLE_SDK_PATHS=(
        "$ANDROID_HOME"
        "$ANDROID_SDK_ROOT"
        "$HOME/android-sdk"
        "/sdcard/Android/sdk"
        "/data/data/com.termux/files/home/android-sdk"
        "/usr/lib/android-sdk"
    )

    FOUND_SDK=""
    for sdk_path in "${POSSIBLE_SDK_PATHS[@]}"; do
        if [ -n "$sdk_path" ] && [ -d "$sdk_path" ]; then
            FOUND_SDK="$sdk_path"
            break
        fi
    done

    if [ -n "$FOUND_SDK" ]; then
        echo "sdk.dir=$FOUND_SDK" > local.properties
        echo "✅ Найден Android SDK: $FOUND_SDK (записан в local.properties)"
    else
        echo "sdk.dir=$HOME/android-sdk" > local.properties
        echo "ℹ️ Создан local.properties с sdk.dir=$HOME/android-sdk."
        echo "   (Если SDK находится в другом месте, отредактируйте local.properties)"
    fi
else
    echo "✅ local.properties уже существует."
fi

# 5. Выдача прав на исполнение скриптам
echo "🔑 Выдача прав на исполнение скриптам..."
chmod +x gradlew *.sh 2>/dev/null || true
if [ -d "tools" ]; then
    chmod +x tools/*.sh 2>/dev/null || true
fi

# 6. Проверка Shizuku / rish
echo "🛡️ Проверка Shizuku (rish)..."
if command -v rish >/dev/null 2>&1; then
    if rish -c "id" >/dev/null 2>&1; then
        RISH_ID=$(rish -c "id")
        echo "✅ Shizuku (rish) активен и готов к работе: $RISH_ID"
    else
        echo "⚠️ Утилита rish установлена, но служба Shizuku сейчас не отвечает."
        echo "   Откройте приложение Shizuku и запустите службу (через Wireless Debugging или Root)."
    fi
else
    echo "ℹ️ rish не найден в PATH."
    echo "   Для интеграции с Shizuku:"
    echo "   1. Откройте приложение Shizuku на телефоне;"
    echo "   2. Выберите 'Использовать Shizuku в терминальных приложениях';"
    echo "   3. Нажмите 'Экспорт файлов' и экспортируйте rish в папку bin Termux:"
    echo "      cp /sdcard/Android/data/moe.shizuku.privileged.api/files/rish* \$PREFIX/bin/"
    echo "      chmod +x \$PREFIX/bin/rish"
fi

echo "=================================================="
echo "🎉 Проект готов к разработке на телефоне!"
echo "=================================================="
echo "Полезные команды для агента и разработчика:"
echo "  • ./run_debug.sh   — Собрать debug APK, установить через Shizuku и запустить"
echo "  • ./logs.sh        — Просмотреть свежие логи плеера и ExoPlayer"
echo "  • ./stop.sh        — Остановить приложение"
echo "  • ./screencap.sh   — Сделать снимок экрана"
echo "  • ./dump_ui.sh     — Снять дамп дерева виджетов интерфейса"
echo "  • AGENTS.md        — Полное руководство по архитектуре для нейросети"
echo "=================================================="
