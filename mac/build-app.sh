#!/usr/bin/env bash
# Build RD2Mac in release, assemble + ad-hoc sign the .app (with the `rd2mac` CLI
# embedded in the bundle), install to /Applications, and install a LaunchAgent that
# starts it at login (KeepAlive only on crash, so the menu's Quit still works).
# Re-run any time after changing the Mac source.
#
# Machine-specific settings live in mac/local.env (untracked, optional), sourced below:
#   RD2MAC_CLI_DEST  also copy the CLI to this path (unset = don't; the app's menu
#                    "Install command-line tool…" puts it on the PATH anyway)
#   RD2MAC_DIST_DIR  where --release writes RD2Mac.zip (default mac/.build/dist)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP_NAME="RD2Mac"
LABEL="net.simioni.rd2mac"
DEST_APP="/Applications/${APP_NAME}.app"
EXEC="${DEST_APP}/Contents/MacOS/${APP_NAME}"
[ -f "$HERE/local.env" ] && . "$HERE/local.env"
CLI_DEST="${RD2MAC_CLI_DEST:-}"
AGENT="$HOME/Library/LaunchAgents/${LABEL}.plist"
DOMAIN="gui/$(id -u)"

# Distribution mode: `build-app.sh --release` signs with a Developer ID +
# hardened runtime and notarizes, producing a clean-opening app for other Macs.
# Without the flag the build stays ad-hoc signed (fine for local use, no network
# or Apple credentials needed).
RELEASE=0
[ "${1:-}" = "--release" ] && RELEASE=1
NOTARY_PROFILE="rd2mac-notary"       # created once via `notarytool store-credentials`
DIST_ZIP="${RD2MAC_DIST_DIR:-$HERE/.build/dist}/RD2Mac.zip"

echo "▶ Building release…"
swift build -c release --package-path "$HERE"
BIN_DIR="$HERE/.build/release"

echo "▶ Assembling ${APP_NAME}.app…"
STAGE="$HERE/.build/${APP_NAME}.app"
rm -rf "$STAGE"
mkdir -p "$STAGE/Contents/MacOS"
cp "$BIN_DIR/${APP_NAME}App" "$STAGE/Contents/MacOS/${APP_NAME}"
cp "$HERE/Resources/Info.plist" "$STAGE/Contents/Info.plist"
mkdir -p "$STAGE/Contents/Resources"
cp "$HERE/Resources/AppIcon.icns" "$STAGE/Contents/Resources/AppIcon.icns"
cp -R "$HERE/Resources/en.lproj" "$STAGE/Contents/Resources/"
cp -R "$HERE/Resources/pt-BR.lproj" "$STAGE/Contents/Resources/"

echo "▶ Embedding the rd2mac CLI in the bundle…"
# Ships inside the .app so a plain DMG install carries it; the app's
# "Install command-line tool" menu symlinks it onto the user's PATH. Signed
# inside-out below, so it inherits Developer ID + notarization.
# MUST live in Contents/Helpers, NOT Contents/MacOS: the volume is case-insensitive,
# so a MacOS/rd2mac collides with the app's own MacOS/RD2Mac and overwrites it —
# launchd would then run the CLI (exit 2) instead of the menu-bar app.
mkdir -p "$STAGE/Contents/Helpers"
cp "$BIN_DIR/rd2mac" "$STAGE/Contents/Helpers/rd2mac"

echo "▶ Building + embedding Share Extension…"
SE_SRC="$HERE/ShareExtension"
APPEX="$STAGE/Contents/PlugIns/ShareExtension.appex"
mkdir -p "$APPEX/Contents/MacOS"
SDK="$(xcrun --sdk macosx --show-sdk-path)"
swiftc -sdk "$SDK" -target arm64-apple-macos13.0 -module-name ShareExtension \
    -O -emit-executable -o "$APPEX/Contents/MacOS/ShareExtension" \
    -Xlinker -e -Xlinker _NSExtensionMain \
    "$SE_SRC/ShareController.swift"
cp "$SE_SRC/Info.plist" "$APPEX/Contents/Info.plist"

echo "▶ Building + embedding Finder Sync Extension (top-level right-click item)…"
# FIFinderSync is the only Apple API that reaches the ROOT of the Finder
# contextual menu (Services/Share/Quick Actions are always a submenu). It posts
# the selected PATHS to the app's loopback /send?path=, so it needs no file access.
FE_SRC="$HERE/FinderExtension"
FE_APPEX="$STAGE/Contents/PlugIns/FinderExtension.appex"
mkdir -p "$FE_APPEX/Contents/MacOS" "$FE_APPEX/Contents/Resources"
swiftc -sdk "$SDK" -target arm64-apple-macos13.0 -module-name FinderExtension \
    -O -emit-executable -o "$FE_APPEX/Contents/MacOS/FinderExtension" \
    -framework FinderSync \
    -Xlinker -e -Xlinker _NSExtensionMain \
    "$FE_SRC/FinderController.swift"
cp "$FE_SRC/Info.plist" "$FE_APPEX/Contents/Info.plist"
# The menu title is localized from the app's own strings (same file, copied in).
cp -R "$HERE/Resources/en.lproj" "$HERE/Resources/pt-BR.lproj" "$FE_APPEX/Contents/Resources/"

if [ "$RELEASE" -eq 1 ]; then
    echo "▶ Signing for distribution (Developer ID + hardened runtime)…"
    SIGN_ID="$(security find-identity -v -p codesigning \
        | awk -F'"' '/Developer ID Application/ {print $2; exit}')"
    if [ -z "${SIGN_ID:-}" ]; then
        echo "✗ No 'Developer ID Application' certificate found in the keychain." >&2
        echo "  Create it: Xcode → Settings → Accounts → Manage Certificates →" >&2
        echo "  + → Developer ID Application. Then re-run with --release." >&2
        exit 1
    fi
    echo "  identity: $SIGN_ID"
    # Sign inside-out: nested Mach-Os (sandboxed extension keeps its entitlements;
    # embedded CLI) first, then the app.
    codesign --force --options runtime --timestamp \
        --entitlements "$SE_SRC/ShareExtension.entitlements" --sign "$SIGN_ID" "$APPEX"
    codesign --force --options runtime --timestamp \
        --entitlements "$FE_SRC/FinderExtension.entitlements" --sign "$SIGN_ID" "$FE_APPEX"
    codesign --force --options runtime --timestamp --sign "$SIGN_ID" "$STAGE/Contents/Helpers/rd2mac"
    codesign --force --options runtime --timestamp --sign "$SIGN_ID" "$STAGE"

    echo "▶ Notarizing (uploading to Apple, waits for the verdict)…"
    NOTARIZE_ZIP="$HERE/.build/RD2Mac-notarize.zip"
    ditto -c -k --keepParent "$STAGE" "$NOTARIZE_ZIP"
    xcrun notarytool submit "$NOTARIZE_ZIP" --keychain-profile "$NOTARY_PROFILE" --wait

    echo "▶ Stapling the notarization ticket to the app…"
    xcrun stapler staple "$STAGE"

    echo "▶ Writing distributable → ${DIST_ZIP}…"
    mkdir -p "$(dirname "$DIST_ZIP")"
    rm -f "$DIST_ZIP"
    ditto -c -k --keepParent "$STAGE" "$DIST_ZIP"
else
    echo "▶ Ad-hoc signing (nested Mach-Os first, then app)…"
    codesign --force --sign - --entitlements "$SE_SRC/ShareExtension.entitlements" "$APPEX"
    codesign --force --sign - --entitlements "$FE_SRC/FinderExtension.entitlements" "$FE_APPEX"
    codesign --force --sign - "$STAGE/Contents/Helpers/rd2mac"
    codesign --force --sign - "$STAGE"
fi

echo "▶ Stopping any running instance…"
launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
osascript -e "quit app \"$APP_NAME\"" 2>/dev/null || true
pkill -x "$APP_NAME" 2>/dev/null || true
sleep 1

echo "▶ Installing to ${DEST_APP}…"
rm -rf "$DEST_APP"
cp -R "$STAGE" "$DEST_APP"

echo "▶ Registering with Launch Services (so the Share Extension is discovered)…"
LSREGISTER=/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister
"$LSREGISTER" -f "$DEST_APP" 2>/dev/null || true

echo "▶ Enabling the Finder extension (and restarting Finder so it reloads)…"
pluginkit -a "$DEST_APP/Contents/PlugIns/FinderExtension.appex" 2>/dev/null || true
pluginkit -e use -i net.simioni.rd2mac.FinderExtension 2>/dev/null || true
killall Finder 2>/dev/null || true

if [ -n "$CLI_DEST" ]; then
    echo "▶ Copying the CLI → ${CLI_DEST}…"
    mkdir -p "$(dirname "$CLI_DEST")"
    cp "$BIN_DIR/rd2mac" "$CLI_DEST"
fi

echo "▶ Installing login LaunchAgent…"
mkdir -p "$(dirname "$AGENT")"
cat > "$AGENT" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>${LABEL}</string>
    <key>ProgramArguments</key>
    <array>
        <string>${EXEC}</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <dict>
        <key>SuccessfulExit</key>
        <false/>
    </dict>
    <key>ProcessType</key>
    <string>Interactive</string>
</dict>
</plist>
PLIST

echo "▶ Loading LaunchAgent (starts the app now + at login)…"
launchctl bootstrap "$DOMAIN" "$AGENT" 2>/dev/null || launchctl load "$AGENT" 2>/dev/null || true

echo "✓ Done. RD2Mac is running and will start at login."
if [ "$RELEASE" -eq 1 ]; then echo "  Distributable (notarized + stapled): $DIST_ZIP"; fi
if [ -n "$CLI_DEST" ]; then echo "  CLI copied to $CLI_DEST"; fi
echo "  To put the CLI on your PATH: menu-bar icon → Install command-line tool…"
echo "  First run may prompt for Local Network — allow it, or enable RD2Mac"
echo "  under System Settings → Privacy & Security → Local Network."
echo "  Share menu: if RD2Mac doesn't appear, enable it under"
echo "  System Settings → General → Login Items & Extensions → Sharing."
echo "  Right-click menu: 'Send to Phone' comes from the Finder extension — if it's"
echo "  missing, enable RD2Mac under Login Items & Extensions → File Providers/Finder."
