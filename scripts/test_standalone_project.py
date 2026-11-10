#!/usr/bin/env python3
"""Lightweight guardrails for the standalone Vulkan test host."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
activity = (root / "app/src/main/java/com/example/hymt2vulkantest/MainActivity.kt").read_text(encoding="utf-8")
gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")

checks = {
    "local Vulkan AAR dependency": 'implementation(files("libs/llama-android-vulkan.aar"))' in gradle,
    "old CPU-only Maven AAR not present": "dev.ffmpegkit-maintained:llama-android:0.1.1" not in gradle,
    "GPU layer control passed to LlamaConfig": re.search(r"gpuLayers\s*=\s*layers", activity) is not None,
    "CPU profile present": "GpuChoice(\"CPU only" in activity and "GpuChoice(\"Vulkan · 2 layers\", 2)" in activity,
    "direct SAF file descriptor path": '"/proc/self/fd/${modelDescriptor!!.fd}"' in activity,
    "GGUF magic validation": "0x47.toByte()" in activity and "0x46.toByte()" in activity,
    "descriptor stays open until unload": "modelDescriptor?.close()" in activity and "Llama.releaseModel(it)" in activity,
    "no broad storage permission": "READ_EXTERNAL_STORAGE" not in manifest and "MANAGE_EXTERNAL_STORAGE" not in manifest,
    "arm64 ABI filter": 'abiFilters += "arm64-v8a"' in gradle,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f"{'PASS' if ok else 'FAIL'}: {name}")
if failed:
    raise SystemExit(f"Standalone project guardrails failed: {', '.join(failed)}")
