# JVM verification harness

`run-model-tests.sh` compiles `core:model` (pure Kotlin, no Android imports) together
with its test sources and runs them on a plain JVM. It exists because the Android
toolchain is not always reachable from the development sandbox:

* no Android SDK / emulator, so no Gradle build, lint or instrumented tests
* Maven Central and Google Maven may be blocked, so no dependency resolution

What it proves: the model layer (typed errors, transfer state machine, retry/backoff,
offline policy, PDF rules and outline parser, throughput simulations) compiles and its
assertions hold. What it does **not** prove: that the Android modules compile, or that
anything works on a device. Those are reported separately in the implementation notes.

## Running

```bash
# one-time: Kotlin compiler distribution (built on any JRE 17+)
mkdir -p /tmp/kit && cd /tmp/kit
curl -sO https://registry.npmjs.org/kotlin-compiler/-/kotlin-compiler-2.4.21.tgz
tar xzf kotlin-compiler-2.4.21.tgz          # -> /tmp/kit/package/bin/kotlinc

# from the repository root
JAVA_HOME=/path/to/jdk KC=/tmp/kit/package bash tools/jvm-verify/run-model-tests.sh
```

`JAVA_HOME` must point at a JVM with `bin/java`; `KC` at the unpacked compiler
distribution (which contains `bin/kotlinc` and `lib/kotlin-*.jar`).

Excluded on purpose:

* `MetadataModels.kt` and `TransferAndMetadataTest.kt` — require kotlinx-serialization
* `core:domain`, `core:database`, `core:telegram` tests — require kotlinx-coroutines,
  Room and the TDLib jars

The shim (`JunitShim.kt`, `Runner.kt`) implements only the JUnit4 surface these tests
use; it is not shipped in the app and is not on any Gradle source set.
