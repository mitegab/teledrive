#!/usr/bin/env bash
# JVM verification harness for core:model tests.
#
# Why this exists: the Android toolchain (SDK, Gradle distribution, Maven) is not
# always reachable from the sandbox used to develop this change. The model layer and
# its tests have no Android dependencies, so they can still be compiled and executed
# with a standalone Kotlin compiler.
#
# Requirements:
#   * a JDK runtime (any 17+; JRE is enough to run, a JDK is not needed for kotlinc)
#   * the Kotlin compiler distribution, e.g.
#       mkdir -p /tmp/kit && cd /tmp/kit
#       curl -sO https://registry.npmjs.org/kotlin-compiler/-/kotlin-compiler-2.4.21.tgz
#       tar xzf kotlin-compiler-2.4.21.tgz            # -> /tmp/kit/package/bin/kotlinc
#
# Usage:
#   JAVA_HOME=/path/to/jdk KC=/path/to/kotlin/compiler bash tools/jvm-verify/run-model-tests.sh
#
# This is a diagnostic aid, not a replacement for `./gradlew test`.
set -o pipefail
KC="${KC:-/tmp/kit/package}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${OUT:-/tmp/jvm-verify-out}"
rm -rf "$OUT" && mkdir -p "$OUT"

# MetadataModels.kt needs kotlinx-serialization and core:domain/core:telegram tests need
# kotlinx-coroutines / TDLib jars; neither ships with the compiler distribution, so only
# core:model sources and tests are compiled here. Those layers stay covered by Gradle.
MAIN=$(find "$REPO/core/model/src/main/kotlin" -name '*.kt' ! -name 'MetadataModels.kt' ! -name 'PackageMarker.kt')
TESTS=$(find "$REPO/core/model/src/test/kotlin" -name '*.kt' ! -name 'TransferAndMetadataTest.kt' 2>/dev/null | tr '\n' ' ')

"$KC/bin/kotlinc" -nowarn $MAIN \
  "$REPO/tools/jvm-verify/JunitShim.kt" "$REPO/tools/jvm-verify/Runner.kt" $TESTS \
  -classpath "$KC/lib/kotlin-test.jar:$KC/lib/kotlin-test-junit.jar" \
  -d "$OUT/verify.jar" 2>&1 | grep -v "^warning:" | head -40

if [ ! -f "$OUT/verify.jar" ]; then echo "COMPILE FAILED"; exit 1; fi

CLASSES=$(grep -rl "@Test" "$REPO/core/model/src/test/kotlin" 2>/dev/null \
  | grep -v TransferAndMetadataTest \
  | sed "s|^$REPO/core/model/src/test/kotlin/||; s|\.kt$||; s|/|.|g")

echo "Running: $CLASSES"
"$JAVA_HOME/bin/java" -cp "$OUT/verify.jar:$KC/lib/kotlin-test.jar:$KC/lib/kotlin-test-junit.jar:$KC/lib/kotlin-stdlib.jar" RunnerKt $CLASSES
