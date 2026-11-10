# Hy-MT2 Vulkan — самостоятельный тестовый APK

Это отдельное Android-приложение для проверки собранного Vulkan runtime **без Tap Translate**. Оно использует уже собранную AAR из `HyMT2-Vulkan-NativeRuntime-ARM64.zip` и позволяет выбрать GGUF, загрузить модель и сравнить CPU с Vulkan на одном устройстве.

## Что находится в проекте

- `app/libs/llama-android-vulkan.aar` — готовая AAR с Kotlin API и ARM64 native runtime.
- `native-runtime/BUILD_MANIFEST.json` — исходные ревизии, SHA-256 AAR и нативных библиотек, параметры CMake и ELF-зависимости.
- `app/src/main/.../MainActivity.kt` — тестовый интерфейс CPU/Vulkan, выбор GGUF через SAF, перевод и статистика генерации.
- `scripts/verify_vulkan_runtime.py` — проверка AAR и нативного runtime до сборки APK.
- `scripts/verify_test_apk.py` — проверка того, что те же ARM64-библиотеки попали в готовый APK без изменений.
- `.github/workflows/build-vulkan-test-apk.yml` — сборка APK через GitHub Actions.

## Проверка полученного runtime

Проверенный AAR имеет SHA-256:

```text
b2a15a3e297b4107575df52711a86e6a53e1a14fff497db5acb1837abe1f49bb
```

Внутри AAR находятся семь ARM64-библиотек:

- `libllama_jni.so`
- `libllama.so`
- `libggml.so`
- `libggml-base.so`
- `libggml-cpu.so`
- `libggml-vulkan.so`
- `libc++_shared.so`

`libggml-vulkan.so` имеет ELF `DT_NEEDED` на Android system `libvulkan.so`. `libggml.so` связан и с `libggml-vulkan.so`, и с `libggml-cpu.so`. Системный `libvulkan.so` в AAR не включается. Все `.so` имеют ELF64 AArch64; их SHA-256 сверяются с `BUILD_MANIFEST.json`.

## Сборка тестового APK

1. Создай отдельный GitHub-репозиторий или используй свой тестовый репозиторий.
2. Загрузи в корень репозитория **содержимое** этой папки, сохранив `.github`, `app`, `native-runtime` и `scripts`.
3. Сделай commit в `main`.
4. Открой **Actions → Build Hy-MT2 Vulkan standalone test APK → Run workflow**.
5. После успешной сборки скачай artifact:

   `HyMT2-Vulkan-Standalone-Test-debug`

Второй artifact `HyMT2-Vulkan-Runtime-Verification` содержит manifest использованного runtime.

Workflow использует JDK 17, Android SDK platform 35, Build Tools 35.0.0 и Gradle 8.9. Он сначала проверяет AAR, затем собирает debug APK и ещё раз проверяет, что все семь библиотек попали в APK с теми же SHA-256. APK подписывается стандартным debug keystore GitHub runner; это тестовая сборка, не production/release APK.

## Проверка на планшете

Планшет: Lenovo Xiaoxin Pad 2022 / Snapdragon 680 / Adreno 610 / Android 12.

1. Установи APK. Tap Translate для этого теста не нужен.
2. Скачай отдельно `Hy-MT2-1.8B-Q4_K_M.gguf` и сохрани его на SD-карте.
3. В приложении выбери **CPU only · 0 GPU layers**.
4. Нажми **Выбрать GGUF на SD-карте** и выбери файл через системный DocumentsUI.
5. Дождись проверки сигнатуры и нажми **Загрузить модель**.
6. Переведи тестовую реплику. Запиши скорость и результат.
7. Нажми **Выгрузить модель / сменить режим**.
8. Выбери **Vulkan · 2 layers**, заново выбери тот же GGUF, загрузи и повтори тот же перевод.
9. Только если 2 слоя работают стабильно, попробуй 4, затем 8. Профили 12–24 оставлены для экспериментов; они могут потребовать больше общей RAM и привести к OOM.

В тестовом приложении `gpuLayers=0` означает CPU. Значения больше нуля запрашивают offload указанного числа слоёв на доступный GPU backend. Это параметр llama.cpp; сам по себе он не доказывает, что все слои действительно исполнялись на Vulkan. Для подтверждения смотри native-строки `ggml_vulkan`, `Vulkan` и `offloaded` в Logcat и сравнивай результаты. Вывод `llama.cpp system info` тоже показывается на экране.

## Работа с файлом модели

GGUF **не копируется** в каталог приложения. Приложение открывает SAF `ParcelFileDescriptor`, проверяет, что это обычный seekable-файл, проверяет сигнатуру `GGUF` и передаёт в native loader путь `/proc/self/fd/N`. Дескриптор остаётся открытым до выгрузки модели. Поэтому DocumentsProvider должен предоставлять seekable file descriptor; потоковые/cloud providers или pipe будут отвергнуты с понятным сообщением. Для теста выбирай локальную SD-карту через системный DocumentsUI.

Если модель не загружается с `/proc/self/fd/N`, пришли лог ошибки. Это будет отдельная проверка поддержки proc-fd/mmap конкретным DocumentsProvider, а не ошибка Vulkan backend.

## Что прислать по итогам

Если CPU и Vulkan оба работают, пришли:

- выбранный режим и число слоёв;
- время загрузки модели;
- перевод одного и того же текста;
- tokens/sec и generate time;
- экран `llama.cpp system info`;
- Logcat с фильтром `HyMT2VKTest`, `ggml_vulkan`, `ggml` и `AndroidRuntime`.

Если CPU работает, а Vulkan падает, не удаляй APK: сначала попробуй 2 слоя и пришли Logcat от начала загрузки модели до ошибки. CPU режим остаётся встроенным и доступным.

## Важные ограничения

- Это **тестовый debug APK**, не модифицированный Tap Translate.
- В APK только `arm64-v8a`; для целевого планшета это ожидаемая ABI.
- Vulkan backend собран и связан с Android `libvulkan.so`, но реальная работоспособность драйвера Adreno 610 и ускорение ещё должны быть проверены на планшете.
- На мобильном GPU часть модели размещается в общей памяти устройства. Начинай с 2 слоёв и context 2048. Не начинай с 24 слоёв или большого контекста.
- AAR содержит upstream notice и отдельный файл лицензии в `native-runtime/UPSTREAM-LICENSE`.

## Исправление GitHub Actions v12

Для `android-actions/setup-android@v4` параметр `accept-android-sdk-licenses` задан YAML-boolean `true`, а не строкой `yes`. Action валидирует значение только как `true/false`; поэтому предыдущий workflow завершался ошибкой разбора input после скачивания command-line tools. Установку `platform-tools` и версию command-line tools оставили без изменений.
