#!/usr/bin/env bash
#
# Build Antique Atlas (MC 1.7.10) with RetroFuturaGradle using only Guix-provided JDKs.
#
#   * Gradle 8.8 runs on OpenJDK 21.
#   * RFG decompiles/recompiles Minecraft and compiles the mod with the Java 8
#     toolchain (icedtea = OpenJDK 8).
#
# Gradle's toolchain auto-download is disabled (a downloaded generic-Linux JDK won't
# run on Guix System), so we resolve the icedtea JDK from the store and pass its path
# to Gradle explicitly.
#
# Usage:  ./rfg-build.sh [gradle args...]      # defaults to: build
#   e.g.  ./rfg-build.sh build                 # full build (runs tests)
#         ./rfg-build.sh assemble              # build the jars, skip tests
#         ./rfg-build.sh build -x test         # full build without tests
#         ./rfg-build.sh runClient             # launch a dev client
#
set -euo pipefail
cd "$(dirname "$0")"

echo ">> Resolving JDKs from Guix (first run downloads/builds them)..."
# RFG needs three JDKs: Java 8 (compile MC + the mod), Java 17 (run the FernFlower
# decompiler), and a JDK to run Gradle on (21). All come from Guix.
JDK21="$(guix build openjdk@21.0.2 | grep -E -- '-jdk$' | head -n1)"
JDK17="$(guix build openjdk@17.0.10 | grep -E -- '-jdk$' | head -n1)"
JDK8="$(guix build icedtea@3.19.0 | grep -E -- '-jdk$' | head -n1)"
[ -x "$JDK21/bin/java" ]  || { echo "!! Could not resolve OpenJDK 21 jdk output" >&2; exit 1; }
[ -x "$JDK17/bin/java" ]  || { echo "!! Could not resolve OpenJDK 17 jdk output" >&2; exit 1; }
[ -x "$JDK8/bin/javac" ]  || { echo "!! Could not resolve icedtea (JDK 8) jdk output" >&2; exit 1; }
echo ">> Gradle JDK (21): $JDK21"
echo ">> Decompiler (17): $JDK17"
echo ">> Java 8 toolchain: $JDK8"

# JDK8/JDK21 are exported so Gradle's `installations.fromEnv` (see gradle.properties)
# can locate the toolchains; env vars are inherited by Gradle's forked build JVM.
exec guix shell -m manifest.scm -- \
  env JAVA_HOME="$JDK21" JDK21="$JDK21" JDK17="$JDK17" JDK8="$JDK8" \
  ./gradlew \
    --no-daemon \
    -Dorg.gradle.java.installations.auto-download=false \
    "${@:-build}"
