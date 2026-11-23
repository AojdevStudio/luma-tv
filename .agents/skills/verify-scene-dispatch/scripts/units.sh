#!/usr/bin/env bash
# Compile the scene classes with plain javac and run their checks. No Android SDK or device.
set -euo pipefail
cd "$(dirname "$0")/../../../.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
javac -d "$out" src/dev/luma/tv/Commands.java src/dev/luma/tv/Capabilities.java src/dev/luma/tv/Profiles.java \
  src/dev/luma/tv/HouseScenes.java tools/CapabilitiesTest.java tools/HouseScenesTest.java
java -cp "$out" dev.luma.tv.HouseScenesTest
java -cp "$out" dev.luma.tv.CapabilitiesTest
