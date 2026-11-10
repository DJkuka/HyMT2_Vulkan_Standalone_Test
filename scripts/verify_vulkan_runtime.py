#!/usr/bin/env python3
"""Verify the supplied Vulkan-enabled llama-android AAR before Gradle build."""
from __future__ import annotations
import argparse
import hashlib
import json
import re
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

EXPECTED_LIBS = {
    "libc++_shared.so",
    "libggml-base.so",
    "libggml-cpu.so",
    "libggml-vulkan.so",
    "libggml.so",
    "libllama.so",
    "libllama_jni.so",
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def elf_machine(data: bytes) -> int:
    if len(data) < 20 or data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    if data[4] != 2 or data[5] != 1:
        raise ValueError("expected ELF64 little-endian")
    return struct.unpack_from("<H", data, 18)[0]


def needed_libraries(path: Path) -> set[str]:
    proc = subprocess.run(["readelf", "-d", str(path)], text=True, capture_output=True)
    if proc.returncode:
        raise RuntimeError(f"readelf failed for {path}: {proc.stderr.strip()}")
    return set(re.findall(r"Shared library: \[(.*?)\]", proc.stdout))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--aar", type=Path, required=True)
    ap.add_argument("--manifest", type=Path, required=True)
    args = ap.parse_args()

    if not args.aar.is_file():
        raise SystemExit(f"AAR not found: {args.aar}")
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    expected_aar_hash = manifest.get("aar", {}).get("sha256")
    actual_aar_hash = sha256(args.aar.read_bytes())
    if expected_aar_hash != actual_aar_hash:
        raise SystemExit(f"AAR SHA-256 mismatch: expected {expected_aar_hash}, got {actual_aar_hash}")

    with zipfile.ZipFile(args.aar) as zf:
        entries = zf.namelist()
        native_entries = [name for name in entries if name.startswith("jni/") and name.endswith(".so")]
        abi_prefixes = {"/".join(name.split("/")[:2]) for name in native_entries}
        if abi_prefixes != {"jni/arm64-v8a"}:
            raise SystemExit(f"Unexpected AAR native ABI directories: {sorted(abi_prefixes)}")
        native = {Path(name).name: name for name in native_entries}
        if set(native) != EXPECTED_LIBS:
            raise SystemExit(f"Unexpected native library set: {sorted(native)}")
        if any("libvulkan.so" in name for name in entries):
            raise SystemExit("AAR must not bundle Android system libvulkan.so")
        classes_data = zf.read("classes.jar")
        import io
        with zipfile.ZipFile(io.BytesIO(classes_data)) as jar:
            class_names = set(jar.namelist())
        for required in (
            "dev/ffmpegkit/llama/Llama.class",
            "dev/ffmpegkit/llama/LlamaConfig.class",
            "dev/ffmpegkit/llama/LlamaModel.class",
            "dev/ffmpegkit/llama/LlamaResult.class",
        ):
            if required not in class_names:
                raise SystemExit(f"AAR Kotlin API missing: {required}")

        with tempfile.TemporaryDirectory(prefix="verify-vulkan-aar-") as temp_name:
            tmp_dir = Path(temp_name)
            extracted: dict[str, Path] = {}
            for name, entry in native.items():
                data = zf.read(entry)
                expected = manifest.get("native_libraries", {}).get(name, {}).get("sha256")
                if not expected or sha256(data) != expected:
                    raise SystemExit(f"Native SHA-256 mismatch: {name}")
                machine = elf_machine(data)
                if machine != 183:
                    raise SystemExit(f"{name} is not AArch64 ELF (e_machine={machine})")
                output = tmp_dir / name
                output.write_bytes(data)
                extracted[name] = output

            vulkan_needed = needed_libraries(extracted["libggml-vulkan.so"])
            if "libvulkan.so" not in vulkan_needed:
                raise SystemExit("libggml-vulkan.so does not depend on system libvulkan.so")
            ggml_needed = needed_libraries(extracted["libggml.so"])
            for required in ("libggml-cpu.so", "libggml-vulkan.so", "libggml-base.so"):
                if required not in ggml_needed:
                    raise SystemExit(f"libggml.so is missing DT_NEEDED {required}")

    print("Vulkan runtime AAR verified")
    print(f"AAR SHA-256: {actual_aar_hash}")
    print("ABI: arm64-v8a (all native ELF objects AArch64)")
    print("Vulkan backend: libggml-vulkan.so -> libvulkan.so")
    print("Backend registration: libggml.so -> CPU + Vulkan + base")
    print("Kotlin API: Llama / LlamaConfig / LlamaModel / LlamaResult")
    print("System libvulkan.so is not bundled")
    for name in sorted(native):
        print(f"  {name}: {manifest['native_libraries'][name]['size_bytes']:,} bytes")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, json.JSONDecodeError, RuntimeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(2)
