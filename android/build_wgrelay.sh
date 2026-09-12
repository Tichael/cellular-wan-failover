#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WGRELAY_DIR="${SCRIPT_DIR}/wgrelay"
OUT_DIR="${SCRIPT_DIR}/app/src/main/jniLibs/arm64-v8a"

# Locate Android NDK
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    if [ -n "${ANDROID_NDK_ROOT:-}" ]; then
        ANDROID_NDK_HOME="${ANDROID_NDK_ROOT}"
    elif [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}/ndk" ]; then
        ANDROID_NDK_HOME="$(find "${ANDROID_HOME}/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1)"
    elif [ -d "/home/vscode/android-sdk/ndk" ]; then
        ANDROID_NDK_HOME="$(find "/home/vscode/android-sdk/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1)"
    fi
fi

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "${ANDROID_NDK_HOME}" ]; then
    echo "ERROR: Android NDK not found. Please set ANDROID_NDK_HOME or ANDROID_HOME." >&2
    exit 1
fi

echo "Using Android NDK: ${ANDROID_NDK_HOME}"

# Detect host toolchain bin directory
TOOLCHAIN_BIN="$(find "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 2 -name "bin" 2>/dev/null | head -n 1)"
if [ -z "${TOOLCHAIN_BIN}" ]; then
    echo "ERROR: Could not locate LLVM toolchain bin directory in ${ANDROID_NDK_HOME}" >&2
    exit 1
fi

CC="${TOOLCHAIN_BIN}/aarch64-linux-android28-clang"
if [ ! -x "${CC}" ]; then
    echo "ERROR: Compiler not executable at ${CC}" >&2
    exit 1
fi

mkdir -p "${OUT_DIR}"

echo "Building libwgrelay.so (arm64-v8a, 16KB page-size alignment)..."
cd "${WGRELAY_DIR}"

CGO_ENABLED=1 \
GOOS=android \
GOARCH=arm64 \
CC="${CC}" \
CGO_LDFLAGS="-Wl,-z,max-page-size=16384 -landroid -llog" \
go build -v -buildvcs=false -buildmode=c-shared -o "${OUT_DIR}/libwgrelay.so" .

echo "Build complete: ${OUT_DIR}/libwgrelay.so"

