# RD2Mac — project context

LocalSend replacement — **Android ⇄ macOS** file transfer + clipboard sync over the LAN,
with persistent chosen pairing, E2E encryption, and a `rd2mac` CLI built for AI agents
(every pull prints saved paths to stdout). Users install from GitHub Releases and the apps
update themselves. This file is the minimal grounding — deep docs:
[DEVELOPMENT.md](DEVELOPMENT.md) (build/usage/self-update) and [PROTOCOL.md](PROTOCOL.md)
(the wire contract). Machine-local notes, when present, live in the untracked
`CLAUDE.local.md`.

## Map

| Path | Role |
|---|---|
| `PROTOCOL.md` | HTTP wire contract — **source of truth for BOTH sides** |
| `README.md` | The users' install guide (bilingual PT/EN, non-technical) — what a release page links to |
| `DEVELOPMENT.md` | Build, test, CLI usage, self-update mechanics, troubleshooting |
| `BACKLOG.md` | Deferred improvements + the measured battery baseline (05/08/2026) |
| `mac/` | Swift SPM: `RD2MacCore` (protocol/server/client/Bonjour) + `RD2MacApp` (menu-bar, LSUIElement) + `rd2mac-cli` · two appex sources built by hand (`ShareExtension/` = Share menu, `FinderExtension/` = top-level Finder right-click) · `build-app.sh`, `make-dmg.sh`, `Tests/` |
| `android/` | Kotlin: foreground service (`specialUse`) + own `ServerSocket` HTTP server + NsdManager + OkHttp |
| `assets/` | Icon master (`icon-1024.png` = flat square master; regenerate everything from it) |

## Invariants (non-negotiable)

1. **PROTOCOL.md is the contract.** Breaking wire changes ship to BOTH sides together
   (wire v2: sealed `/receive` response + sealed filename header). Mirrored pieces
   (`HeaderCodec`, `PairingAuth`, `RD2Crypto`) are byte-identical Swift↔Kotlin, locked by
   fixed-vector tests on both sides.
2. **No phantom sends.** Success only after the receiver wrote the file and returned 200;
   failures retry (backoff) and surface explicitly.
3. **Everything sealed.** Transfer bodies are AES-GCM (key from ECDH pairing handshake,
   SAS code on first pairing); data endpoints require the pairing token (401 otherwise).
   The Mac's loopback control API refuses what a web page can send it (an Origin header
   or a non-loopback Host → 403); its only GET route, `/peer`, has no side effect.
4. **Clipboard Android→Mac is MANUAL by design** (Quick Settings tile). AOSP grants
   background clipboard read to the focused app / IME only — a11y has NO carve-out (source
   read 10/07/2026). Do NOT re-attempt background reading; auto-send was built and
   deliberately REMOVED (10/07/2026). Mac→Android stays automatic (polling).
5. **A release is a contract.** Both apps update themselves from the latest GitHub release:
   asset names `RD2Mac.dmg` / `RD2Mac.apk`, tag `vX.Y` = the apps' versions, a higher
   Android `versionCode` (DEVELOPMENT.md *Self-update*).

## Build / test

```bash
mac/build-app.sh                      # ad-hoc build → /Applications/RD2Mac.app (CLI embedded); machine paths in mac/local.env
mac/build-app.sh --release           # Developer ID + notarize (Keychain profile rd2mac-notary) — for distribution
swift test --package-path mac
android/gradlew -p android assembleRelease        # daemon on a JDK 17 (gradle/gradle-daemon-jvm.properties); JDK 26 breaks Kotlin 1.9
android/gradlew -p android testDebugUnitTest
adb install -r android/app/build/outputs/apk/release/app-release.apk   # our own app: adb direct, not rd2mac send
```

## Gotchas that cost real time (1 line each — the full stories are in the commit messages)

- CLI must live in `Contents/Helpers/`, **never** `Contents/MacOS/` — APFS is
  case-insensitive, `rd2mac` overwrites `RD2Mac` and the app "won't start" (exit 2).
- Sign inside-out (ShareExtension → FinderExtension → CLI → app); after Services/Share
  changes run `pbs -update` / `pluginkit -e use`.
- Finder extensions ship DISABLED and the Finder only loads one after it relaunches —
  the app does `pluginkit -e use` + `killall Finder` once on first launch (that's what
  makes the DMG one-drag for a non-technical user).
- An `NSImage`'s template flag does NOT survive the trip to the Finder: an SF Symbol in
  a FinderSync menu arrives as black art and vanishes in dark mode → tint it in
  `menu(for:)` (read `AppleInterfaceStyle` from the GLOBAL domain — inside an appex,
  `UserDefaults.standard` is the container's).
- Android: `usesCleartextTraffic` (only the app's HTTP CLIENT is policed — server/curl
  work, POST fails); `onStartCommand` is not idempotent (guard the port bind).
- Discovery does not stay up by itself (0.5): the Mac re-arms its browse every 60 s and
  **re-announces every 5 min** — on the dev network the phone never got our replies, only
  our announcements. Both halves fail 100% silently, so test them by killing the browse,
  never by an E2E on a healthy network.
- mDNS finds the phone; it does not prove it is gone. A dozing S23 left five Mac re-arms
  unanswered (~5 min) while answering ping — the menu said "searching" as the phone said
  "connected" (06/10). The Mac now drops a peer only if a unicast `GET /ping` fails too.
- The phone comes back on its own after "clear all", after an update
  (`MY_PACKAGE_REPLACED`) and after boot — but only while it is exempt from battery
  optimisation (a restricted app can't start an FGS from the background). ⚠️ Testing the
  update path with `am force-stop` first proves nothing: a stopped app gets no broadcasts.
- Running the `.app` binary from a terminal binds port 53319 to that shell's instance and
  misdirects the Bluetooth permission prompt — restart clean with `launchctl kickstart -k`.
- mDNS browse goes dormant by policy (`BrowsePolicy`); after `forget()` you must
  stop+restart browse or discovery hangs "searching" forever.
- Android FILE bodies stream (share, pull, receive) — never `RD2Crypto.seal/open` on a
  whole file: the app heap is 256 MB and Conscrypt's AES-GCM buffers the WHOLE message
  even through `update()`, so `GcmStream` builds the same wire bytes from AES-CTR +
  GHASH (a 147 MB video OOM'd the app, 29/09). Regression = a body bigger than the test heap.
- The share screen only OPENS the items and finishes (~0.1 s); `ShareTransfers` sends in
  the background with a progress notification. Open before finishing: the URI grant dies
  with the activity, an open descriptor does not.
- Self-update: the relaunched Mac copy must finish launching BEFORE it waits for the old
  pid — the old copy quits only once the system reports the new one up, so a wait before
  `NSApplication` deadlocked until the timeout (E2E 01/10).
- A NEW Mac binary's first connection to the phone is refused for ~50 ms by Local Network
  privacy while `nehelper` re-registers it (log: `Local network prohibited`; the CLI says
  "Internet connection appears to be offline"). Every call to the phone goes through
  `ControlService.retrying` — a path without retries fails once after each update (02/10).

## Launch at login

`build-app.sh` installs a LaunchAgent `net.simioni.rd2mac` on the build machine (KeepAlive
on crash only, so menu Quit still works). Installed copies (DMG) have no agent: the app
offers launch-at-login on first run and toggles it via `SMAppService` — which needs a real
signature, so it works on the notarized build and not on an ad-hoc one. The offer + the
menu toggle are hidden when the agent exists.
