#!/usr/bin/env bash
# Idempotent Cloud Agent bootstrap for the Family Bank Android app.
# Installs the Android SDK command-line tools + required packages, points the
# Gradle build at them via local.properties, and warms the Gradle cache.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_TOOLS_VERSION="11076708"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "==> Using ANDROID_HOME=$ANDROID_HOME"
mkdir -p "$ANDROID_HOME"

sdkmanager_bin="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$sdkmanager_bin" ]; then
  echo "==> Installing Android command-line tools"
  tmp_zip="$(mktemp --suffix=.zip)"
  curl -fsSL -o "$tmp_zip" "$CMDLINE_TOOLS_URL"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest" "$ANDROID_HOME/cmdline-tools/tmp"
  mkdir -p "$ANDROID_HOME/cmdline-tools/tmp"
  unzip -q "$tmp_zip" -d "$ANDROID_HOME/cmdline-tools/tmp"
  mv "$ANDROID_HOME/cmdline-tools/tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rmdir "$ANDROID_HOME/cmdline-tools/tmp"
  rm -f "$tmp_zip"
else
  echo "==> Android command-line tools already present"
fi

echo "==> Accepting SDK licenses"
yes | "$sdkmanager_bin" --licenses >/dev/null 2>&1 || true

echo "==> Installing SDK packages (platform-tools, android-35, build-tools 35.0.0 & 34.0.0)"
"$sdkmanager_bin" \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0" \
  "build-tools;34.0.0" >/dev/null

echo "==> Writing local.properties"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$repo_root/local.properties"

echo "==> Warming Gradle dependency cache"
cd "$repo_root"
ANDROID_HOME="$ANDROID_HOME" ./gradlew --no-daemon help >/dev/null 2>&1 || true

echo "==> Android environment ready"
