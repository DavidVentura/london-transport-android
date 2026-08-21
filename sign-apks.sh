#!/usr/bin/env bash
# Signs whatever build.sh left in unsigned/. Passwords can come from the arguments, from
# $STORE_PASSWORD/$KEY_PASSWORD, or from the prompt — the prompt keeps them out of shell history.
set -euo pipefail

if [ "$#" -lt 2 ]; then
    echo "Usage: $0 <keystore_path> <key_alias> [store_password] [key_password]" >&2
    exit 1
fi

KEYSTORE_PATH="$1"
KEY_ALIAS="$2"
STORE_PASSWORD="${3:-${STORE_PASSWORD:-}}"
KEY_PASSWORD="${4:-${KEY_PASSWORD:-}}"

cd "$(dirname "$0")"

BUILD_TOOLS="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?ANDROID_SDK_ROOT or ANDROID_HOME must be set}}/build-tools/36.0.0"
if [ ! -d "$BUILD_TOOLS" ]; then
    echo "No build-tools 36.0.0 at $BUILD_TOOLS" >&2
    exit 1
fi

unsigned_apk="unsigned/app-release-unsigned.apk"
if [ ! -f "$unsigned_apk" ]; then
    echo "No $unsigned_apk — run ./build.sh first" >&2
    exit 1
fi

if [ -z "$STORE_PASSWORD" ]; then
    read -rsp "keystore password: " STORE_PASSWORD
    echo
fi
if [ -z "$KEY_PASSWORD" ]; then
    read -rsp "key password (empty if same as the keystore): " KEY_PASSWORD
    echo
    KEY_PASSWORD="${KEY_PASSWORD:-$STORE_PASSWORD}"
fi

VERSION_NAME=$("$BUILD_TOOLS/aapt2" dump badging "$unsigned_apk" | grep -oP "versionName='\K[^']*")
signed_apk="signed/london-transport-${VERSION_NAME}.apk"
aligned_apk="unsigned/app-release-aligned.apk"

mkdir -p signed
rm -f "$aligned_apk" "$signed_apk"
"$BUILD_TOOLS/zipalign" -p 4 "$unsigned_apk" "$aligned_apk"
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE_PATH" \
    --ks-pass pass:"$STORE_PASSWORD" \
    --ks-key-alias "$KEY_ALIAS" \
    --key-pass pass:"$KEY_PASSWORD" \
    --out "$signed_apk" \
    "$aligned_apk"
rm -f "$aligned_apk"

"$BUILD_TOOLS/apksigner" verify --print-certs "$signed_apk" | grep -i "signer #1 certificate SHA-256"
echo "Signed: $signed_apk"
