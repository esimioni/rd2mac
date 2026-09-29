# RD2Mac

Personal LocalSend replacement — **Android phone ⇄ macOS** file transfer over the
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
├── PROTOCOL.md          # the HTTP wire contract (source of truth, both sides)
├── mac/                 # Swift: RD2MacCore lib + menu-bar app + `rd2mac` CLI
│   └── build-app.sh     # build release → /Applications/RD2Mac.app + CLI in bin/
└── android/             # Kotlin: foreground service + HTTP server + share target
```

Each peer runs an HTTP server + client, discovers others via Bonjour/mDNS
(`_rd2mac._tcp`), and pairs with the one you choose (matched by a stable
per-install id). See [PROTOCOL.md](PROTOCOL.md) for the wire contract.

## Build & install — macOS

```bash
mac/build-app.sh
```
Builds release and installs `RD2Mac.app` — with the `rd2mac` CLI **embedded in the
bundle** — to `/Applications`. The app is a menu-bar agent (no Dock icon) and
registers itself as a **login item** on first launch. To put the CLI on your PATH,
use the app's **Install command-line tool…** menu item (see below).

First launch shows a **Local Network** permission prompt (macOS 15+) — allow it,
or enable RD2Mac under *System Settings → Privacy & Security → Local Network*.
Otherwise the Mac can't discover the phone.

Tests: `swift test --package-path mac`

## Build & install — Android

```bash
android/gradlew -p android assembleRelease   # JDK pinned via org.gradle.java.home in gradle.properties
adb install -r android/app/build/outputs/apk/release/app-release.apk   # or sideload the APK
```
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

RD2Mac ships a small `rd2mac` CLI **inside the app bundle**, built for exactly this:
every pull **prints the saved file's path(s) to stdout**, so an AI agent with shell
access can fetch things off the phone and immediately read them back — no touching the
phone, no GUI. The loop is always *natural language → one `rd2mac` command → read the
printed path(s)*.

**1. Put the CLI on your PATH (once).** Click the RD2Mac menu-bar icon → **Install
command-line tool…** (symlinks `rd2mac` into `/usr/local/bin`; it may ask for your
password once). Verify with `rd2mac version`.

**2. Teach your agent the tool.** An AI doesn't know `rd2mac` exists until you tell it
— paste this into the agent's instructions (`CLAUDE.md`, a system prompt, a rule):

> This Mac has `rd2mac`, a CLI that moves files between the Mac and my phone over the
> LAN (the RD2Mac app must be running, the phone paired, both on the same Wi-Fi). Use
> it whenever I ask to pull from or send to my phone:
> - `rd2mac pull-screenshot [n]` — save my *n* latest screenshots to `~/Downloads` and
>   print each path (one per line); read those files to see them. Default n = 1.
> - `rd2mac pull [image|video] [n]` — same, for the *n* latest gallery photos/videos.
> - `rd2mac send <file>…` — send local file(s) to my phone (photos/videos land in the
>   gallery, anything else in Downloads).
>
> After a pull, open each printed path to view the image(s).

**How it plays out.** You say *"pull my last two screenshots and tell me what's in
them"*; the agent runs one command, reads the printed paths, and describes them:

```
$ rd2mac pull-screenshot 2
/Users/you/Downloads/Screenshot_2026-07-10.png
/Users/you/Downloads/Screenshot_2026-07-10 (2).png
```

**Requirements:** the RD2Mac menu-bar app running, both devices paired and on the same
Wi-Fi (see *Usage*). The CLI only talks to the app over loopback (port 53319); the app
holds the encrypted link to the phone.

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
