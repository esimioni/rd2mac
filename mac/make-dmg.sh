#!/usr/bin/env bash
# Build a distributable DMG from the installed, notarized RD2Mac.app: a
# drag-to-Applications volume, signed + notarized + stapled so it opens clean on
# other Macs (no "unidentified developer"). Run AFTER `build-app.sh --release`,
# which notarizes and installs the .app to /Applications.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
[ -f "$HERE/local.env" ] && . "$HERE/local.env"   # optional machine settings (see build-app.sh)
APP_NAME="RD2Mac"
SRC_APP="/Applications/${APP_NAME}.app"
OUT_DMG="${RD2MAC_DIST_DIR:-$HERE/.build/dist}/${APP_NAME}.dmg"
NOTARY_PROFILE="rd2mac-notary"

[ -d "$SRC_APP" ] || { echo "✗ $SRC_APP not found — run build-app.sh --release first." >&2; exit 1; }
SIGN_ID="$(security find-identity -v -p codesigning \
    | awk -F'"' '/Developer ID Application/ {print $2; exit}')"
[ -n "${SIGN_ID:-}" ] || { echo "✗ No 'Developer ID Application' certificate found." >&2; exit 1; }

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
echo "▶ Staging the volume (app + Applications symlink)…"
cp -R "$SRC_APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"

echo "▶ Creating compressed DMG…"
mkdir -p "$(dirname "$OUT_DMG")"
rm -f "$OUT_DMG"
hdiutil create -volname "$APP_NAME" -srcfolder "$STAGE" -ov -format UDZO "$OUT_DMG"

echo "▶ Signing the DMG (Developer ID)…"
codesign --force --timestamp --sign "$SIGN_ID" "$OUT_DMG"

echo "▶ Notarizing the DMG (uploading to Apple, waits for the verdict)…"
xcrun notarytool submit "$OUT_DMG" --keychain-profile "$NOTARY_PROFILE" --wait
xcrun stapler staple "$OUT_DMG"

echo "✓ DMG ready: $OUT_DMG"
spctl --assess --type open --context context:primary-signature -v "$OUT_DMG" 2>&1 || true
