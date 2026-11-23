#!/usr/bin/env sh
# Cross-compile adm_core for Android and copy the .so files into app/src/main/jniLibs.
#
# Prerequisites (desktop machine / CI, NOT the on-device editor):
#   rustup target add aarch64-linux-android armv7-linux-androideabi
#   cargo install cargo-ndk
#   export ANDROID_NDK_HOME=/path/to/ndk
#
# Usage:  ./build-android.sh [--release|--debug]
set -eu

PROFILE="release"
for arg in "$@"; do
  case "$arg" in
    --debug) PROFILE="debug" ;;
    --release) PROFILE="release" ;;
  esac
done

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/../app/src/main/jniLibs"

# Android ABI names -> Rust target triples are handled by cargo-ndk itself.
if ! command -v cargo-ndk >/dev/null 2>&1; then
  echo "error: cargo-ndk not found. Install it with: cargo install cargo-ndk" >&2
  exit 1
fi

echo "Building adm_core ($PROFILE) for arm64-v8a + armeabi-v7a ..."
FLAGS=""
[ "$PROFILE" = "release" ] && FLAGS="--release"

# shellcheck disable=SC2086
cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -o "$OUT" \
  build $FLAGS \
  --manifest-path "$ROOT/Cargo.toml" \
  -p adm_core

echo "Native libraries written to:"
find "$OUT" -name 'libadm_core.so' -print
