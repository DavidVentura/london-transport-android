#!/usr/bin/env bash
# Builds the release APK in the F-Droid buildserver image, so the same commit gives the same
# bytes here and on their builder. Pass a revision to build something other than HEAD.
set -euo pipefail

cd "$(dirname "$0")"

IMAGE=london-transport-build
APP_ID=dev.davidv.londontransport
REVISION="${1:-HEAD}"
GRADLE_CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/london-transport-gradle"

docker build -t "$IMAGE" .

# Built from a committed tree rather than the working copy: uncommitted edits, a stale build/ and
# the host's local.properties are all things nobody rebuilding from the tag would have.
export_dir="$(mktemp -d)"
trap 'rm -rf "$export_dir"' EXIT
git archive --format=tar "$REVISION" | tar -x -C "$export_dir"

mkdir -p "$GRADLE_CACHE" unsigned
docker run --rm \
  -v "$export_dir":/home/vagrant/build/$APP_ID \
  -v "$GRADLE_CACHE":/home/vagrant/.gradle \
  -e GRADLE_USER_HOME=/home/vagrant/.gradle \
  --user "$(id -u):$(id -g)" \
  "$IMAGE"

cp "$export_dir/app/build/outputs/apk/release/app-release-unsigned.apk" unsigned/
echo "Built $(git rev-parse --short "$REVISION"):"
sha256sum unsigned/app-release-unsigned.apk
