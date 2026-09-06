#!/usr/bin/env bash
# Download the Vosk speech models into app/src/main/assets/.
# These are large binaries (~134MB total) and are NOT committed to git.
set -euo pipefail

cd "$(dirname "$0")/.."
ASSETS="app/src/main/assets"
mkdir -p "$ASSETS"

fetch() {
    local url="$1" dir="$2" uuid="$3"
    local tmp
    tmp="$(mktemp -d)"
    echo "Downloading $url ..."
    curl -sL -o "$tmp/model.zip" "$url"
    unzip -q "$tmp/model.zip" -d "$tmp"
    # The zip contains a single top-level dir; copy its contents into the asset dir.
    local inner
    inner="$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -1)"
    cp -r "$inner"/* "$ASSETS/$dir/"
    echo "$uuid" > "$ASSETS/$dir/uuid"
    rm -rf "$tmp"
}

fetch "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip" "model-en-us" "en-us-0.15"
fetch "https://alphacephei.com/vosk/models/vosk-model-small-nl-0.22.zip" "model-nl" "nl-0.22"

echo "Done. Models in $ASSETS/"
