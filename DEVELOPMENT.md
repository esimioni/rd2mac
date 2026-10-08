# RD2Mac — development

How RD2Mac is built, tested and shipped. To install the apps, see the
[README](README.md).

RD2Mac is a LocalSend replacement — **Android phone ⇄ macOS** file transfer over the
LAN. You pick which phone and which Mac to pair, once, in each app; from then on
each device talks only to its chosen counterpart (no picker on every send), and
the pairing is remembered until you change it.

Two things it fixes vs LocalSend:
1. **No phantom sends.** A transfer reports success only after the receiver has
   written the file to disk (HTTP 200); failures retry and surface explicitly.
2. **The Mac can pull.** From Claude Code (or a terminal) you can grab the phone's
   latest screenshot without touching the phone: `rd2mac pull-screenshot`.

## Layout

```
rd2mac/
├── README.md            # install guide for users (PT/EN)
├── PROTOCOL.md          # the HTTP wire contract (source of truth, both sides)
├── mac/                 # Swift: RD2MacCore lib + menu-bar app + `rd2mac` CLI
│   ├── build-app.sh     # build release → /Applications/RD2Mac.app (CLI embedded)
│   └── make-dmg.sh      # notarized DMG from the installed app (after --release)
└── android/             # Kotlin: foreground service + HTTP server + share target
```

Each peer runs an HTTP server + client, discovers others via Bonjour/mDNS
(`_rd2mac._tcp`), and pairs with the one you choose (matched by a stable
per-install id). See [PROTOCOL.md](PROTOCOL.md) for the wire contract.

## Build & install — macOS

```bash
mac/build-app.sh
```
Needs macOS 13+ on Apple Silicon and Xcode 15+ (or its Command Line Tools: Swift 5.9).
Builds release and installs `RD2Mac.app` — with the `rd2mac` CLI **embedded in the
bundle** — to `/Applications`, plus a LaunchAgent that starts it at login (an
installed DMG copy offers launch-at-login on first run instead). The app is a
menu-bar agent (no Dock icon). To put the CLI on your PATH, use the app's **Install
command-line tool…** menu item (see below).

Machine-specific paths go in an optional, untracked `mac/local.env`, sourced by both
scripts — e.g. to also copy the CLI somewhere and keep distributables elsewhere
(default `mac/.build/dist`):
```bash
: "${RD2MAC_CLI_DEST:=$HOME/bin/rd2mac}"
: "${RD2MAC_DIST_DIR:=$HOME/Desktop}"
```

**Distribution build:** `mac/build-app.sh --release` signs the bundle staged in
`mac/.build/RD2Mac.app` with the *Developer ID Application* certificate in your keychain
(hardened runtime), notarizes it through the notarytool Keychain profile `rd2mac-notary`
(create it once with `xcrun notarytool store-credentials rd2mac-notary`) and staples —
and installs nothing: the build Mac takes the release through its own self-update, like
any user's. Then `mac/make-dmg.sh` packs that staged bundle (it refuses one that isn't
notarized), signs and notarizes `RD2Mac.dmg`.

First launch shows a **Local Network** permission prompt (macOS 15+) — allow it,
or enable RD2Mac under *System Settings → Privacy & Security → Local Network*.
Otherwise the Mac can't discover the phone.

Tests: `swift test --package-path mac`

## Build & install — Android

```bash
android/gradlew -p android assembleRelease   # Gradle runs on any installed JDK 17
adb install -r android/app/build/outputs/apk/release/app-release.apk   # or sideload the APK
```
Needs the Android SDK (`ANDROID_HOME`, or `sdk.dir` in `android/local.properties`) and a
JDK 17 anywhere Gradle can find it (`gradle/gradle-daemon-jvm.properties` picks it; JDK 26
breaks Kotlin 1.9's version parser). Release signing reads `~/.config/rd2mac/keystore.properties`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); without it the release APK
comes out unsigned — sign it with your own key, or use `assembleDebug`.

Open the app once to grant media + notification permissions and start the
foreground service. **Developer mode is only needed to `adb install`** during
development — normal use (and manual APK sideload) needs none, so banking/gov
apps that refuse developer mode keep working.

Tests: `android/gradlew -p android testDebugUnitTest`

## Usage

**Phone → Mac:** share photos/files from any app → **RD2Mac** → you are back in that
app at once; the transfer runs in the background (progress in a notification) and the
files land in `~/Downloads` on the Mac, with a "sent ✓ / failed ✗" toast.

**Mac → phone / Mac pulls** (CLI, needs the app running):
```bash
rd2mac pull-screenshot     # newest screenshot → ~/Downloads, prints the path
rd2mac pull [image|video]  # newest gallery item
rd2mac send <file>...      # send local files (images/videos → gallery, else Downloads)
rd2mac peer                # show the phone discovered on the network
```

**Mac → phone (Finder right-click, top level):** right-click a file → **Send to Phone**,
no submenu. Provided by a Finder Sync extension (`FIFinderSync`) embedded in the app —
the only Apple API that reaches the root of the contextual menu. It posts the selected
paths to the loopback control endpoint (`POST /send?path=`), so it needs no file access
of its own. macOS ships Finder extensions disabled; the app enables it once on first
launch (`pluginkit -e use` + a Finder relaunch), or do it under *System Settings →
General → Login Items & Extensions*. Folders are filtered out of the selection (the menu
item hides when only folders are selected); a multi-file selection sends every file, one
transfer each, with a single sound at the end.

**Mac → phone (Share menu):** right-click a file in Finder / QSpace → **Share → RD2Mac**
→ sent to the phone (graphical equivalent of `rd2mac send`). Provided by a Share
Extension embedded in the app; enable it under *System Settings → General → Login
Items & Extensions → Sharing* if it doesn't show up.

**Mac → phone (Services menu):** right-click → **Services → Send to Phone with
RD2Mac**. Same delivery path (loopback control → retry → phone); the menu-bar icon
flashes `✓n` / `✗n` with the result. Declared via `NSServices` in Info.plist —
file managers that let you promote Services items to direct actions (e.g. QSpace)
can surface it as a one-click button. If it doesn't appear after a rebuild, run
`/System/Library/CoreServices/pbs -update`.

**Clipboard sync:**
- **Mac → phone** is automatic: opt-in switch on the phone ("Receive clipboard
  from the Mac"), which polls `GET /clipboard` and pastes. The "Receive from Mac"
  Quick Settings tile pauses/resumes it in one tap.
- **Phone → Mac** is manual **by design** — the "Send to Mac" Quick Settings tile
  (a transparent focused activity reads the clipboard legally). See PROTOCOL.md
  for why no automatic capture exists: AOSP only lets the focused app / IME read
  the clipboard.
- **Proximity gate (optional):** "Only when the Mac is nearby" pauses automatic
  sync when the phone stops hearing the Mac's BLE beacon. Three ranges (dBm
  thresholds in `ProximityLevels`, calibrated against real measurements); the
  phone reports its state to the Mac (`POST /proximity`), shown in the menu as
  "Phone nearby / away". Both apps show a green padlock while the paired link is
  end-to-end encrypted.

## Driving it from an AI agent (Claude Code, etc.)

The CLI is built for agents with shell access: every pull **prints the saved file's
path(s) to stdout**, so the agent runs one `rd2mac` command and reads the files back.
Setting it up, with the block to paste into the agent's instructions: the README's 🤖
section ([English](README.md#-english)). The CLI only talks to the app over loopback
(port 53319); the app holds the encrypted link to the phone.

## Self-update (both apps, since 0.6)

Once a day each app reads the public repo's latest release
(`api.github.com/repos/esimioni/rd2mac/releases/latest`) and installs a newer version on
its own — the Mac app from `RD2Mac.dmg`, the phone from `RD2Mac.apk`.

- **Checks.** Download size and GitHub's SHA-256 digest must match the release. The Mac
  accepts the app inside the DMG only when it satisfies the *running* app's designated
  requirement (same identifier, same Developer ID team) — so an ad-hoc dev build never
  takes an update. Android lets only a build signed with our key replace us.
- **Never mid-transfer.** The swap waits until no transfer has moved for 30 s
  (`TransferActivity`, fed by both servers, the CLI's pulls/sends and the phone's shares).
- **Mac.** The bundle is renamed aside, the new one moved into its place, relaunched with
  `--rd2mac-wait-pid <old pid>`; the old copy quits once the system reports the new one
  up, and the new one starts its servers once the old one is gone. Needs a writable
  parent folder (an admin user in `/Applications`); otherwise it logs and stays.
- **Android.** Silent (`USER_ACTION_NOT_REQUIRED`) once the user allowed RD2Mac to
  "Install unknown apps"; before that the system asks through a notification. APKs are
  downloaded on unmetered networks only. The install kills the app; `MY_PACKAGE_REPLACED`
  brings the service back.
- **A release is now a contract.** Asset names `RD2Mac.dmg` / `RD2Mac.apk`; tag `vX.Y`
  equal to the apps' dotted versions; a higher Android `versionCode`. A release whose
  asset is not newer is remembered as rejected and not downloaded again.
- **Forks.** The feed is this repository's releases (`Updates.latestURL` on the Mac, the
  `rd2macUpdateFeed` property on Android). Point both at your own releases: a build
  signed with another key can't install these updates anyway.
- **Testing against a local feed.** Mac: `defaults write net.simioni.rd2mac updateFeedURL
  http://127.0.0.1:8766/latest.json` (delete it afterwards). Android: build with
  `-Prd2macUpdateFeed=http://10.0.2.2:8765/latest.json` (emulator → host).
  Logs: `adb logcat -s RD2Mac.Update` · `/usr/bin/log show --predicate 'process == "RD2Mac"'`.

## Notes / troubleshooting

- **Same Wi-Fi.** Both devices must share the LAN. Public/guest networks with
  AP/client isolation block mDNS and peer traffic — pair (and use) on a network
  without isolation; on isolated networks even clipboard transfer fails.
- **Encrypted end-to-end.** The transport is cleartext HTTP on the LAN
  (`usesCleartextTraffic`), but every transfer body is AES-GCM sealed with a key
  from the ECDH pairing handshake (SAS code guards the first pairing against
  MITM); transfers also require the pairing token (401 otherwise).
- **Ports:** 53318 (transfers, both peers), 53319 (Mac loopback control for the CLI).
- The foreground service uses `specialUse` (not `dataSync`) to avoid the Android
  15+ FGS runtime timeout, so it stays reachable indefinitely.
- The phone's mDNS browse goes **dormant** once the paired Mac is resolved and
  the app UI is closed (transfers use the last-known address; a connectivity
  failure or network change re-resolves). Advertising stays on — the Mac finds
  the phone through it.
- Diagnostic logs stay in release builds (local `logcat` only):
  `adb logcat -s RD2Mac.Clip:V RD2Mac.Discovery:V RD2Mac.Proximity:V` — the
  proximity tag prints raw beacon RSSI for threshold calibration.

## License

MIT — see [LICENSE](LICENSE). The app icon is not covered (see the README).
