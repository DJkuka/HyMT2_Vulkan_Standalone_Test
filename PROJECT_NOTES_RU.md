# Заметки о сборке

Создано на основе прежнего `HyMT2_Android_ReadyAAR_Test_fix_sdk` standalone app. Главные изменения:

- Maven dependency `dev.ffmpegkit-maintained:llama-android:0.1.1` удалена.
- Вместо неё используется локальная `app/libs/llama-android-vulkan.aar` из предоставленного пользователем build artifact.
- Сохранён тот же package API `dev.ffmpegkit.llama.*`.
- Прежний импорт GGUF с копированием заменён на открытый `ParcelFileDescriptor` + `/proc/self/fd/N`, чтобы не создавать дополнительную копию около 1.13 GB.
- Добавлен выбор `gpuLayers` (0 CPU; 2/4/8/12/16/24 GPU offload), context и threads.
- Добавлена явная выгрузка модели перед сменой backend.
- Добавлена проверка AAR и итогового APK по ELF machine, SHA-256 и DT_NEEDED.

Runtime artifact details: upstream llama-android commit `9f5469fd9fa7ea7bc7b01801923af95dc3b41d52`; llama.cpp submodule `b9878`; `GGML_VULKAN=ON`, `GGML_CPU=ON`, `GGML_BACKEND_DL=OFF`, `BUILD_SHARED_LIBS=ON`.

Ни APK-сборка, ни запуск на Android-устройстве в этой среде не выполнялись. Для получения APK требуется GitHub Actions. Это нужно явно сохранять в любых заметках до фактического успешного Actions run.

## Исправление GitHub Actions v12

Для `android-actions/setup-android@v4` параметр `accept-android-sdk-licenses` задан YAML-boolean `true`, а не строкой `yes`. Action валидирует значение только как `true/false`; поэтому предыдущий workflow завершался ошибкой разбора input после скачивания command-line tools. Установку `platform-tools` и версию command-line tools оставили без изменений.
