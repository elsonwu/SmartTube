#!/usr/bin/env bash
#
# Set up a build environment for this fork on macOS.
#
#   ./scripts/setup-build-env.sh
#
# What it does:
#   1. Installs mise + pins Temurin 17 (see .mise.toml).
#   2. Installs the Android SDK command-line tools + platform 34.
#   3. Works around the x86_64 protoc bug on arm64 macOS.
#
# The protoc workaround matters: the protobuf-gradle-plugin downloads
# `protoc-3.17.3-osx-aarch_64.exe` from Maven Central, but that file is
# actually an x86_64 binary despite the name. On Apple Silicon it fails
# with "Bad CPU type in executable" and the build dies in
# :exoplayer-library-sabr:generateStstableReleaseProto.
#
# The fix is to wrap the cached binary in a shell script that runs it
# under `arch -x86_64` (Rosetta 2).

set -euo pipefail

cd "$(dirname "$0")/.."

echo "══════════════════════════════════════════════════════════════"
echo "  SmartTube parental-limit fork - build environment setup"
echo "══════════════════════════════════════════════════════════════"

# ── 1. mise + JDK ─────────────────────────────────────────────────────────
if ! command -v mise >/dev/null 2>&1; then
    echo "[*] installing mise"
    brew install mise
else
    echo "[+] mise already installed: $(mise --version)"
fi

echo "[*] installing the pinned JDK (temurin-17.0.21+9)"
mise install java@temurin-17.0.21+9
mise use java@temurin-17.0.21+9

echo
echo "[*] To use it in this shell:"
echo "    eval \"\$(mise activate bash)\"      # or zsh"
echo "    (add that line to your ~/.zshrc to make it permanent)"
echo

# ── 2. Android SDK ────────────────────────────────────────────────────────
if [ -z "${ANDROID_HOME:-}" ] && [ ! -d /opt/homebrew/share/android-commandlinetools ]; then
    echo "[*] installing the Android command-line tools"
    brew install --cask android-commandlinetools
fi

export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "[*] installing SDK platform 34 + build-tools"
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools" >/dev/null 2>&1 || true

# ── 3. protoc / Rosetta workaround ────────────────────────────────────────
echo
echo "[*] checking the protoc architecture workaround"

PROTOC_VERSION="3.17.3"
PROTOC_DIR="$HOME/.gradle/caches/modules-2/files-2.1/com.google.protobuf/protoc/$PROTOC_VERSION"
PROTOC_BIN="$PROTOC_DIR/protoc-$PROTOC_VERSION-osx-aarch_64.exe"

# Only needed on arm64 macOS.
if [ "$(uname -m)" = "arm64" ]; then
    if ! /usr/bin/arch -x86_64 /bin/echo x >/dev/null 2>&1; then
        echo "[*] installing Rosetta 2 (needed to run the x86_64 protoc)"
        softwareupdate --install-rosetta --agree-to-license || true
    fi

    # Trigger the download by letting Gradle resolve the dependency.
    echo "[*] resolving the protoc dependency (downloads it if missing)"
    ./gradlew :exoplayer-library-sabr:dependencies --configuration ststableReleaseCompileClasspath \
        >/dev/null 2>&1 || true

    if [ -f "$PROTOC_BIN" ]; then
        if file "$PROTOC_BIN" | grep -q "x86_64"; then
            echo "[*] the cached protoc is x86_64 - installing the Rosetta wrapper"
            REAL="$PROTOC_DIR/protoc.real.bin"
            [ -f "$REAL" ] || mv "$PROTOC_BIN" "$REAL"
            cat > "$PROTOC_BIN" <<'WRAPPER'
#!/bin/bash
# Runs the x86_64 protoc under Rosetta on arm64 hosts.
exec arch -x86_64 "$(dirname "$0")/protoc.real.bin" "$@"
WRAPPER
            chmod +x "$PROTOC_BIN"
            "$PROTOC_BIN" --version && echo "[+] protoc wrapper OK"
        else
            echo "[+] the cached protoc is already arm64 - no wrapper needed"
        fi
    else
        echo "[!] could not find $PROTOC_BIN - run a build first, then re-run this script"
    fi
else
    echo "[+] not an arm64 host - no protoc workaround needed"
fi

echo
echo "══════════════════════════════════════════════════════════════"
echo "  Done. To build:"
echo
echo "    cd $(pwd)"
echo "    eval \"\$(mise activate bash)\""
echo "    export ANDROID_HOME=$ANDROID_HOME"
echo "    ./gradlew :smarttubetv:assembleStstableRelease"
echo
echo "  Output:"
echo "    smarttubetv/build/outputs/apk/ststable/release/SmallTube_parental-limit_stable_<ver>_universal.apk"
echo "══════════════════════════════════════════════════════════════"
