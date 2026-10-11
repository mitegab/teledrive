#!/usr/bin/env bash
set -euo pipefail

# Builds the official TDLib source at the commit recorded in TDLIB_VERSION.
# The generated native output is intentionally ignored by Git. This avoids
# committing a large, environment-specific binary and makes the provenance
# explicit for every local/live build.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION_FILE="$ROOT_DIR/tools/TDLIB_VERSION"
# shellcheck disable=SC1090
source "$VERSION_FILE"

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" ]]; then
  echo "ANDROID_SDK_ROOT (or ANDROID_HOME) is required; install an Android SDK first." >&2
  exit 2
fi

for command_name in git cmake ninja php perl gperf java jar; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Missing required command: $command_name" >&2
    exit 2
  fi
done

SOURCE_DIR="$ROOT_DIR/tools/.tdlib-source"
OPENSSL_DIR="$SOURCE_DIR/third-party/openssl"
ANDROID_EXAMPLE="$SOURCE_DIR/example/android"
OUTPUT_DIR="$ROOT_DIR/core/telegram/src/main/tdlib"
NDK_VERSION="${ANDROID_NDK_VERSION:-23.2.8568313}"

if [[ ! -d "$SOURCE_DIR/.git" ]]; then
  rm -rf "$SOURCE_DIR"
  git clone --filter=blob:none https://github.com/tdlib/td.git "$SOURCE_DIR"
fi

git -C "$SOURCE_DIR" fetch --depth 1 origin "$TDLIB_COMMIT"
git -C "$SOURCE_DIR" checkout --detach "$TDLIB_COMMIT"

if [[ ! -d "$OPENSSL_DIR" ]]; then
  (
    cd "$ANDROID_EXAMPLE"
    ./build-openssl.sh "$SDK_ROOT" "$NDK_VERSION" "$OPENSSL_DIR"
  )
fi

(
  cd "$ANDROID_EXAMPLE"
  # The official script also generates the matching JsonClient.java source.
  # JSONJava exposes TDLib's asynchronous JSON API through JNI and is the
  # smallest official interface to consume from Kotlin.
  ./build-tdlib.sh "$SDK_ROOT" "$NDK_VERSION" "$OPENSSL_DIR" c++_shared JSONJava 21 "$TDLIB_ABIS"
)

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"
cp -R "$ANDROID_EXAMPLE/tdlib/libs" "$OUTPUT_DIR/jniLibs"
cp -R "$ANDROID_EXAMPLE/tdlib/java" "$OUTPUT_DIR/generated-java"
printf 'TDLib %s (%s)\ninterface=%s\n' "$TDLIB_VERSION" "$TDLIB_COMMIT" "$TDLIB_INTERFACE" > "$OUTPUT_DIR/VERSION.txt"

cat <<EOF
TDLib native output is ready:
  $OUTPUT_DIR/jniLibs
Generated binding source is ready:
  $OUTPUT_DIR/generated-java

The output is ignored by Git. Review the version and ABI list before a live build.
EOF
