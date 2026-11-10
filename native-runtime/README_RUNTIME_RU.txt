Hy-MT2 Vulkan Native Runtime
ABI: arm64-v8a
Kotlin API: dev.ffmpegkit.llama (upstream-compatible)
Vulkan: GGML_VULKAN=ON
CPU fallback: GGML_CPU=ON
Link mode: BUILD_SHARED_LIBS=ON, GGML_BACKEND_DL=OFF
libggml.so links ordinary shared libggml-vulkan.so and libggml-cpu*.so dependencies
libggml-vulkan.so links Android system libvulkan.so (not bundled)
For build identity and SHA-256 values, see BUILD_MANIFEST.json.
