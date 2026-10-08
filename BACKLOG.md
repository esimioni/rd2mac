# Backlog — deferred improvements

Accepted but not scheduled. Nothing here is urgent: they are hygiene, not fixes for
a user-visible problem.

## ⏭️ Next release (after 0.6)

- **It is the first real self-update test** (decided 06/10/2026): the next release goes
  straight to GitHub and BOTH installed 0.6 apps must update themselves — so far it only
  ran against a local feed (01/10). So nothing new is installed by hand first, no
  `adb install` on the test phone, and ⚠️ **the Mac pipeline installs by itself** —
  `build-app.sh --release` ends by replacing `/Applications/RD2Mac.app`, and `make-dmg.sh`
  packs the DMG FROM `/Applications`. Before building, make the package come from the
  staged bundle (`mac/.build/RD2Mac.app`) without installing, or the Mac half of the test
  is gone before it starts.
- **In `main`, not released yet:**
  - a CLI pull retries a transport blip — the first pull after each update failed (sends
    already retried), so it waits for the next release rather than a 0.6.1;
  - the Mac no longer drops a dozing phone: a peer the browse stops confirming gets a
    unicast `GET /ping` first. Before it, the 0.6 menu said "searching" — and
    `rd2mac pull/send` answered "phone not found" — 3 times in ~4 h on 06/10/2026
    (1.3 + 11.1 + 4.7 min) while the phone was in Doze;
  - the Mac's control API refuses requests a web page makes (Origin header or a
    non-loopback Host → 403).
- **First self-update on a real Samsung not yet seen.** The test phone (S23) runs 0.6 with
  "Install unknown apps" NOT granted to RD2Mac, on purpose: the next release shows
  whether One UI adds a prompt beyond the system's (only an AOSP emulator ran it).
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
