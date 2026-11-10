#!/usr/bin/env python3
"""Verify native payload in the standalone test APK."""
from __future__ import annotations
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path

EXPECTED = {
    "libc++_shared.so", "libggml-base.so", "libggml-cpu.so",
    "libggml-vulkan.so", "libggml.so", "libllama.so", "libllama_jni.so",
}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", type=Path, required=True)
    ap.add_argument("--manifest", type=Path, required=True)
    args = ap.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    with zipfile.ZipFile(args.apk) as zf:
        names = zf.namelist()
        native_entries = [n for n in names if n.startswith("lib/") and n.endswith(".so")]
        abi_prefixes = {"/".join(n.split("/")[:2]) for n in native_entries}
        if abi_prefixes != {"lib/arm64-v8a"}:
            raise SystemExit(f"APK native ABI mismatch: {sorted(abi_prefixes)}")
        native = {Path(n).name: n for n in native_entries}
        if set(native) != EXPECTED:
            raise SystemExit(f"APK native library set mismatch: {sorted(native)}")
        if any(Path(n).name == "libvulkan.so" for n in names):
            raise SystemExit("APK must use Android system libvulkan.so, not package one")
        for name, entry in native.items():
            data = zf.read(entry)
            if data[:4] != b"\x7fELF" or data[4] != 2 or struct.unpack_from("<H", data, 18)[0] != 183:
                raise SystemExit(f"Invalid ARM64 ELF in APK: {name}")
            expected = manifest["native_libraries"][name]["sha256"]
            actual = hashlib.sha256(data).hexdigest()
            if actual != expected:
                raise SystemExit(f"APK changed native runtime payload: {name}")
        if "classes.dex" not in names:
            raise SystemExit("APK has no classes.dex")
    print("Standalone APK verified: all Vulkan/CPU ARM64 libraries are packaged intact.")
    print("No system libvulkan.so is bundled; Android provides it at runtime.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
