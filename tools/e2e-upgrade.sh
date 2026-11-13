#!/bin/sh
# SPDX-FileCopyrightText: 2026 Re.L contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Upgrade check (v0.3.1), run by e2e.yml on the emulator: the previous release with the appname,
# then the public APK installed over it, as the founder will do. Usage:
#   tools/e2e-upgrade.sh <apk before> <apk after> <output dir>
set -u
before="$1"; after="$2"; out="$3"
mkdir -p "$out"
adb uninstall org.relreader.app >/dev/null 2>&1 || true
adb install "$before" || exit 1
maestro test .maestro/upgrade/u1-before.yaml --format junit --output "$out/u1.xml" --test-output-dir "$out" || exit 1
adb install -r "$after" || exit 1
maestro test .maestro/upgrade/u2-after.yaml --format junit --output "$out/u2.xml" --test-output-dir "$out" || exit 1
adb uninstall org.relreader.app >/dev/null 2>&1 || true
echo "Upgrade check passed."
