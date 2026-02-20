# Lottery Analyzer

Приложение для Android для анализа лотерейных билетов с использованием распознавания текста (OCR).

## Описание

Lottery Analyzer использует камеру устройства для сканирования лотерейных билетов и автоматического распознавания чисел с помощью **Google ML Kit Text Recognition**. Приложение определяет верхний и нижний блоки билета, сравнивает распознанные числа с выбранными пользователем и показывает результат:

- ✅ **Полное совпадение** (15 из 15) — зелёная рамка
- ⚠️ **Частичное совпадение** (13-14 из 15) — жёлтая рамка
- ❌ **Несовпадение** (< 13 из 15) — красная рамка

## Возможности

- Распознавание текста в реальном времени
- Автоматическое разделение на верхний/нижний блоки
- Визуальная индикация результатов
- Поддержка Android 10+ (API 29+)

## Технологии

| Компонент | Версия |
|-----------|--------|
| Android Gradle Plugin | 7.4.0 |
| Kotlin | 1.8.0 |
| Gradle | 8.5 |
| Android SDK | 33 (target), 29 (min) |
| Java | 11+ (требуется для сборки) |

## Зависимости

### AndroidX
- `androidx.core:core-ktx:1.9.0`
- `androidx.appcompat:appcompat:1.5.1`
- `androidx.constraintlayout:constraintlayout:2.1.4`
- `androidx.lifecycle:lifecycle-runtime-ktx:2.5.1`

### ML Kit
- `com.google.mlkit:text-recognition:16.0.0`

### Тестирование
- `junit:junit:4.13.2`
- `androidx.test.ext:junit:1.1.4`
- `androidx.test.espresso:espresso-core:3.5.0`

## Переменные окружения

Для сборки проекта необходимы следующие переменные окружения:

```bash
# Java Development Kit (требуется JDK 11 или выше)
JAVA_HOME=C:\Users\Art\.jdks\ms-21.0.7  # Путь к вашей JDK

# Android SDK (автоматически определяется через local.properties)
ANDROID_HOME=C:\Users\Art\AppData\Local\Android\Sdk
```

### Настройка переменных окружения

**Windows (PowerShell):**
```powershell
$env:JAVA_HOME="C:\Путь\К\JDK"
$env:ANDROID_HOME="C:\Пользователи\Имя\AppData\Local\Android\Sdk"
```

**Windows (cmd):**
```cmd
set JAVA_HOME=C:\Путь\К\JDK
set ANDROID_HOME=C:\Пользователи\Имя\AppData\Local\Android\Sdk
```

**Linux/macOS:**
```bash
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=$HOME/Android/Sdk
```

## Требования для сборки

1. **JDK 11 или выше** (рекомендуется JDK 17 или 21)
2. **Android SDK** с компонентами:
   - Platform Android 13 (API 33)
   - Build-Tools 33.0.0
   - Platform-Tools
3. **Gradle 8.5** (включён через Gradle Wrapper)

## Сборка проекта

### 1. Клонирование репозитория

```bash
git clone <repository-url>
cd lotoscan
```

### 2. Настройка Android SDK

Создайте файл `local.properties` в корне проекта:

```properties
sdk.dir=C\:\\Users\\Art\\AppData\\Local\\Android\\Sdk
```

Или укажите переменную окружения `ANDROID_HOME`.

### 3. Сборка

**Debug версия (для отладки):**
```bash
# Windows (PowerShell)
$env:JAVA_HOME="C:\Путь\К\JDK"
.\gradlew.bat assembleDebug

# Windows (cmd)
set JAVA_HOME=C:\Путь\К\JDK
gradlew.bat assembleDebug

# Linux/macOS
export JAVA_HOME=/path/to/jdk
./gradlew assembleDebug
```

**Release версия (подписанная debug-ключом):**
```bash
# Windows (PowerShell)
$env:JAVA_HOME="C:\Путь\К\JDK"
.\gradlew.bat assembleRelease

# Linux/macOS
export JAVA_HOME=/path/to/jdk
./gradlew assembleRelease
```

**Полная сборка со всеми проверками:**
```bash
$env:JAVA_HOME="C:\Путь\К\JDK"
.\gradlew.bat build
```

### 4. Очистка проекта

```bash
.\gradlew.bat clean
```

### 5. Запуск на устройстве

```bash
# Установка debug версии на подключенное устройство
.\gradlew.bat installDebug

# Или вручную через adb
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Структура проекта

```
lotoscan/
├── app/
│   ├── src/main/
│   │   ├── java/com/lottery/analyzer/
│   │   │   ├── MainActivity.kt      # Главный экран
│   │   │   └── CameraActivity.kt    # Экран камеры с OCR
│   │   ├── res/
│   │   │   ├── layout/              # XML макеты
│   │   │   ├── values/              # Строки, стили
│   │   │   └── mipmap-*/            # Иконки приложения
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── build.gradle
├── settings.gradle
├── gradle.properties
├── local.properties (не входит в git)
└── README.md
```

## Результаты сборки

После успешной сборки APK файлы находятся в:

- **Debug**: `app/build/outputs/apk/debug/app-debug.apk`
- **Release**: `app/build/outputs/apk/release/app-release.apk`

Отчёт lint: `app/build/reports/lint-results-debug.html`

## Лицензия

Проект создан в образовательных целях.
