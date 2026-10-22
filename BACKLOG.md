# Backlog — deferred improvements

Accepted but not scheduled. Nothing here is urgent: they are hygiene, not fixes for
a user-visible problem.

## ⏭️ 0.8 — one beacon per pair; Device care stops flagging RD2Mac (published 23/10/2026)

- Android: the proximity gate no longer piles up the screen-off scan results that made
  Samsung's Device care call RD2Mac a battery hog — see *2026-10-22 — Samsung's own
  detector* below.
- Mac + Android: the beacon is the pair's own and changes daily (§5, PROTOCOL.md
  *BLE proximity beacon*). Mixed 0.7/0.8 devices don't hear each other: the gate reads
  far until both update.
- **Update test, as users get it:** before publishing, the Mac and the S23 went back to
  the official 0.7 (the Mac from a byte copy of its installed bundle, the phone from
  the v0.7 APK by `adb install -r`). Results below as they come in.

## ⏭️ 0.7 — the first real self-update (published 08/10/2026)

- Both installed 0.6 apps must update themselves from this release — the first time
  outside a local feed (01/10). Nothing was installed by hand: `build-app.sh --release`
  now only stages and notarizes the bundle and `make-dmg.sh` packs that stage, so the
  build Mac stays on 0.6 until its own updater runs.
- **Mac: ✅ updated itself, 08/10/2026 15:12.** Check forced (`updateLastCheck` deleted +
  restart through launchd): tick at +60 s, new copy up 6 s later, 0.7 in `/Applications`
  at +66 s, one process left, both extensions still enabled, phone found right after.
- **Phone (S23, One UI): ✅ updated, 08/10/2026.** Its daily check found 0.7 at 18:38
  (~3.5 h after the release) and posted "RD2Mac update ready". Without "Install unknown
  apps" granted, the first update went: notification → system "Permission required" →
  Settings toggle → "Update this app?" → **Google Play Protect "App scan recommended"**
  (*Scan app* / *Don't install app*, equally prominent) → "This app looks safe" →
  *Install*. The Play Protect pair is what the AOSP emulator never showed; the README's
  update steps now name it.
- **Next release: is the phone silent now?** The permission is granted, so 0.8 should
  install with no prompt (`USER_ACTION_NOT_REQUIRED`) — unless Play Protect asks to scan
  every APK it hasn't seen. Watch it then.
- **`hdiutil` is deprecated on macOS 27** (prints "use `diskutil image …`", still works
  01/10/2026). The updater's DMG mount and `make-dmg.sh` depend on it; the day it goes,
  the Mac self-update fails with only a log line → move to `diskutil image attach/eject`.

## 0. ✅ FIXED 2026-08-11 — the Mac's Bonjour browse never restarted once it died

Shipped the same day it was found: `PeerBrowser` now re-arms the search every 60 s on
a timer, plus immediately on `didNotSearch`, on `NSWorkspace.didWakeNotification` and on
any `NWPathMonitor` change (debounced to 5 s). A peer unseen for 150 s (~2 missed re-arms)
is dropped, so a dead browse can't leave a stale address behind either *(06/10/2026: only
if it also fails a unicast `GET /ping` — a dozing phone goes quiet on multicast alone)*. The rule lives in
`BrowseKeepAlive` — pure, and unit-tested by driving the clock, because the failure reports
nothing and an E2E on a healthy network would have passed before the fix too.

The original report, kept because it explains why the fix is shaped like this:

**Symptom:** `rd2mac peer` / `pull` answer `phone not found on the network` forever, while
the phone is on the LAN and reachable (`ping` fine, ~150 ms = normal Android Wi-Fi doze) and
the menu-bar app is running. **Quitting and reopening RD2Mac.app fixes it instantly** — the
peer resolves in ~6 s.

**Cause (read in the source, not inferred):** `PeerBrowser` in
`mac/Sources/RD2MacCore/Discovery.swift` calls `browser.searchForServices(...)` exactly once,
from `start()` at launch. It implements `didFind`/`didRemove`/`didResolve`, but **not**
`netServiceBrowser(_:didNotSearch:)`, and nothing ever re-arms the search. If the browse stops
— sleep/wake, Wi-Fi change, an mDNSResponder hiccup — the object stays alive, `peers` stays
empty, and every answer is a truthful-but-useless "not found". There is no symptom anywhere:
the app is still in the menu bar and the CLI still replies.

**What shipped:** option (a) plus a periodic re-arm, because the callback-driven half is the
part we can't trust — the browse that bit us died without calling anything. Migrating to
`NWBrowser` (deprecation aside) stays optional: it would replace the timer with a real state
machine, but the timer covers the same failures and costs one mDNS query a minute.

Cost when it hits: the failure looks like the PHONE's fault, so the reflex is to blame the
phone's Wi-Fi or app — two wasted rounds on 11/08 before pinging the phone.

## Measured baseline (2026-08-05, S23 / SM-S911B, app 0.3)

`adb shell dumpsys batterystats` over an 8h14m discharge window (2464 mAh total):

| RD2Mac cost | value |
|---|---|
| Total | **6.53 mAh — 0.27% of the drain** (23rd app in the list) |
| Bluetooth (BLE proximity gate) | 5.91 mAh — and that is the device's BT bill split evenly across the 6 UIDs that scanned, not a per-app measurement |
| CPU (the 1.5 s clipboard poll) | 0.62 mAh in 8 h |
| Partial wakelock | 14 ms |
| Wi-Fi | 13 MB (~19k requests × ~680 B) |

That session's real drain was another app holding a partial wakelock for ~3 h with the
screen off. **Do not re-open the "is RD2Mac eating the battery" question without new
measurements** — this one is answered.

### 2026-10-22 — Samsung's own detector flagged the BLE gate (fixed)

Device care listed RD2Mac under "High battery usage" and offered to switch it to
Optimized — which would undo the exemption the service needs to stay up. The energy
answer above still held (`batterystats`: 2.13 mAh of 775, 0.27%), but Samsung's
Bluetooth stack judges something else: the scan RESULTS an app receives with the screen
OFF, per 1/4/8 h window (`dumpsys bluetooth_manager` → `Current Consumption Severities`).
Each 6 s LOW_LATENCY burst heard the Mac's beacon ~25 times, so the night averaged
737 results/h → HIGH in the 8 h window → `dumpsys sdhms` logged
`EXCESSIVE_BLUETOOTH_SCAN` nearly every night since 2026-09-17 (51 entries).

What shipped: a burst stops at the first reading that renews the gate (1 result to
keep "near", 3 to arm it), and a far gate doesn't scan while the screen is off — no
new burst, and a running one stops when the screen goes dark; the screen coming on
scans at once (`BurstPolicy` in `Proximity.kt`).

Seen on the S23 the same day (local build): bursts that keep "near" took 1 result in
0.04–0.3 s, screen off included (it had been 26–28 results in 6 s); arming took 3; the
screen-on scan fired the instant the screen lit with the gate far; a far gate left
alone in the dark started no burst for 90 s; a burst stopped 0.25 s after the screen went
dark (it used to run its 6 s: 8–12 dark results each when a beacon was in earshot —
notification "glimpse"/edge lighting lights the screen and fires `ACTION_SCREEN_ON`
too). Screen-off results: 2 in the 2 h after the update; the 8 h window fell from HIGH
to MEDIUM within the hour and to LOW in two. Device care kept showing the 09:36
detection all afternoon — it doesn't re-judge the app when its behaviour changes.

## 5. ✅ FIXED 2026-10-22 (0.8) — the beacon had no identity: any RD2Mac Mac nearby counted as "my Mac"

Up to 0.7 every Mac running RD2Mac advertised the same service UUID and nothing else,
so the phone couldn't tell its paired Mac from another one. Seen 2026-10-22: with the
paired Mac's app quit, the S23 kept hearing the beacon at −74 dBm from 14:23 to at
least 14:26 (gone by 14:32) — a second Mac in the house running RD2Mac. Cost: weak readings
that never renew the gate kept a burst listening its full 6 s. Risk: near enough to
SOMEONE ELSE's RD2Mac Mac, the gate read "near" and kept the paired Mac's clipboard
flowing to the phone.

What shipped: the pair's own UUID, derived from the session key and the UTC day
(PROTOCOL.md *BLE proximity beacon*; macOS advertises only service UUIDs and a local
name). The old UUID was NOT kept alongside: two 128-bit UUIDs don't fit one legacy
advertisement, and a mixed pair only loses the automatic clipboard (gate reads far)
until the daily self-update brings the other side.

## 1. Long-poll on `GET /clipboard` — no usability impact

Today: the phone polls every 1.5 s (`CLIP_POLL_MS`, `RD2MacService.kt`); the Mac answers
204 when the pasteboard changeCount is unchanged.

Change: accept `?wait=<seconds>` (cap ~25 s). The Mac holds the response until the
changeCount moves or the timeout expires, then 204. ~2400 req/h → ~150 req/h, and one
mostly-idle connection instead of 2400 handshakes.

- Latency gets *better*, not worse: a copy on the Mac lands on the phone immediately
  instead of up to 1.5 s later.
- Backward compatible in both directions: an old Mac ignores the param and answers at
  once (today's behaviour); an old phone never sends it. No PROTOCOL version bump needed
  for the wire format itself — document the param in PROTOCOL.md.
- Watch out: the phone's OkHttp `readTimeout` is 30 s (`RD2Http`), so a 25 s hold fits;
  keep `wait` strictly under it. The Mac's `HTTPServer` has a connection idle watchdog —
  a held request must not be reaped by it.

## 2. Keep-alive on the Mac's HTTP server — no usability impact

`HTTPServer.swift` sets `Connection: close` on every response, so each poll pays a full
TCP handshake + teardown and OkHttp's connection pool can never reuse anything. Allowing
keep-alive on small GETs (`/ping`, `/clipboard`) removes that. Largely subsumed by (1) if
long-poll ships first, but it is independent and smaller.

## 3. Browse backoff when the Mac is not found — HAS a trade-off, decide before doing

`onPeerUnreachable` drops the cached address and restarts the mDNS browse, which then runs
continuously with no backoff until the Mac answers — i.e. forever while the Mac is asleep
or the phone is away. A staged backoff (e.g. browse 30 s, sleep 1 → 5 min) would stop that,
and skipping the browse entirely off Wi-Fi would help too.

Trade-off: it costs reconnection latency when arriving home (the phone would take up to
one backoff interval to see the Mac again). **Not a free change — needs a decision on how
much delay is acceptable.**

## 4. Pause the clipboard poll while the screen is off — probably unnecessary

Was the obvious "big win" before the measurement showed the poll costs 0.62 mAh in 8 h.
If (1) ships, the steady-state cost drops again and this stops being worth the extra state
machine. Keep it here only as a fallback if a future measurement contradicts the baseline.
