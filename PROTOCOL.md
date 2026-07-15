# RD2Mac Wire Protocol

Single source of truth for the HTTP contract between the two peers. Both the
macOS side (`mac/`) and the Android side (`android/`) implement exactly this.

## Model

- Two roles: an Android phone and a Mac. Each device has a stable per-install
  **id** and pairs with exactly one counterpart, chosen by the user and remembered
  until they change it. On a network with several RD2Mac devices, each one only
  ever talks to the peer it is paired with.
- Each peer runs an **HTTP/1.1 server** and is also an HTTP **client**.
- Peers find each other via **Bonjour / mDNS**, service type `_rd2mac._tcp`.
- HTTP over the LAN, but **end-to-end encrypted**. Pairing is an ECDH (P-256)
  handshake: the two exchange public keys (`/pair`) and derive a shared secret that
  never travels the wire, confirming a 6-digit code (SAS) to rule out a
  man-in-the-middle. From the secret both derive an AES-256 key + auth token
  (HKDF-SHA256). Every transfer body is **AES-GCM sealed** and carries the token, so
  a sniffer on a hotel Wi-Fi sees only ciphertext and an unpaired server accepts
  nothing. The crypto is byte-for-byte identical on both platforms (fixed-vector test).

## Discovery

- Service type: `_rd2mac._tcp` (local domain).
- TXT record keys: `id` (stable per-install UUID — the pairing key), `name`
  (human device name), `platform` (`macos` | `android`), `v` (protocol version,
  currently `1`).
- Each side advertises its own server and browses for counterparts, keeping the
  **live list** of peers seen on the network (adding on resolve, dropping on
  `serviceLost`). The user picks one to pair with; that choice — matched by `id`,
  so it survives name/IP changes — is persisted, and from then on the device
  resolves and talks only to that peer. The last resolved IP:port is cached for a
  fast reconnect.

## Headers

| Header | Direction | Meaning |
|---|---|---|
| `X-RD2Mac-Token` | transfers | Auth token derived from the ECDH secret. **Required** on `/receive`, `/clipboard`, `/proximity`, `/screenshot`, `/media` (`401` on mismatch or when unpaired). Not on `/ping`/`/pair`. The **body** is AES-GCM sealed (`nonce‖ciphertext‖tag`) with the paired key. |
| `X-RD2Mac-Filename` | uploads & downloads | Original filename. **Paired (v2):** `base64(seal(utf8))` with the paired key — a sniffer who captured the token learns no content metadata. Unpaired/loopback: percent-encoded (ASCII-alnum kept, everything else `%XX` of its UTF-8 bytes). Receivers try the sealed form first and fall back to the percent decode. |
| `X-RD2Mac-Peer` | both | Sender's `name`, used for the pairing handshake / logging. |
| `Content-Type` | uploads & downloads | Best-effort MIME of the payload. |
| `Content-Length` | uploads & downloads | Always set. **No chunked transfer**; body is a single contiguous blob. |

## Endpoints

### `GET /ping`
Health + identity. Response `200 application/json`:
```json
{ "id": "6F1C2D8E-4B7A-4E39-9C5D-2A8F3B1E7C40", "name": "My-Mac", "platform": "macos", "v": 1 }
```
`id` is the stable per-install UUID (also in the TXT record). A peer from before
`id` existed simply omits it; the field decodes tolerantly to `""`.

### `POST /pair`
ECDH key exchange. Request body JSON `{ "id", "name", "pubKey" }` — the initiator's
id, display name, and ephemeral **P-256 public key** (X9.63 uncompressed, base64).
The responder replies `200` with the same shape (its id/name/pubKey); both derive
the shared secret and show a **6-digit code (SAS)** the user compares before either
side saves the pairing. A **different** peer trying to take over an established
pairing gets `403`. Open (no token) — this bootstraps the keys, and the secret
itself never travels the wire.

### `POST /receive`
Receive one file. Request body = raw file bytes. Headers: `X-RD2Mac-Filename`,
`Content-Type`, `Content-Length`.

The server writes the bytes to disk **before** responding. Success response is
returned **only after the file is fully persisted** — this is the guarantee that
kills LocalSend's phantom-send bug (no optimistic "sent" without a real write).

Response `200 application/octet-stream` (v2): body = **AES-GCM sealed** JSON
```json
{ "saved": "/Users/you/Downloads/Screenshot_2026-07-07.png", "bytes": 482113 }
```
Sealing the confirmation proves the responder holds the pairing key: a bare
plaintext `200` could be forged by an active MITM that never persisted anything.
The sender MUST open the body with the paired key and treat a failure as *not
delivered* (retry) — same as any non-200.

Errors: `401` (token mismatch), `413` (declared `Content-Length` beyond the
receiver's cap — 256 MiB on Android, 4 GiB on macOS; bodies are held fully in
memory), `400` (negative length / undecryptable body), `408` (silent client),
`500` (write failed — the sender MUST treat this as *not delivered* and retry).

Destination routing:
- **Mac server:** everything → `~/Downloads` (de-duplicated name on collision).
- **Android server:** `image/*` and `video/*` → MediaStore (shows up in the
  gallery, Pictures/DCIM); everything else → MediaStore `Downloads`.

### `GET /clipboard?since=` and `POST /clipboard`
Clipboard sync (token required, body sealed like any transfer).

- `GET /clipboard?since=<version>` — the Mac serves its pasteboard when it
  changed since `<version>` (its `changeCount`); `204` when unchanged. Response
  headers: `X-RD2Mac-Clip-Version`, `Content-Type` (`text/plain; charset=utf-8`
  or `image/png`); body sealed.
- `POST /clipboard` — the phone pushes its clipboard; the Mac pastes it only when
  its "receive" switch is on (`403` otherwise).
- **Echo suppression (both sides, local behavior):** the Mac never serves the
  version produced by the phone's own `POST` back to the phone; the phone
  fingerprints (SHA-256) the last content that crossed each direction and drops
  echoes. Without both cuts, bidirectional sync loops forever (paste → clip-changed
  → push → changeCount bump → poll → paste → …).
- The phone's push is **manual by design** (the "Send to Mac" Quick Settings
  tile → transparent focused activity): AOSP's ClipboardService only lets the
  FOCUSED app / default IME read the clipboard — there is no accessibility
  carve-out, so a normal app's automatic background capture (a11y direct read,
  copy-gesture-triggered launches) either reads null or steals window focus.
  Attempted and retired 2026-07-10 on cost/UX grounds. Known heavier routes if
  automatic send is ever wanted again: a11y node-tree text capture
  (`canRetrieveWindowContent`) or a Shizuku shell-uid bridge.

### `POST /proximity`
Phone → Mac only: the phone's proximity-gate status, so the Mac's menu can show
"phone nearby / away". The phone is the only side that measures (it scans the
Mac's BLE beacon; both sync directions are phone-driven) — the Mac just displays.
Token required; body sealed JSON:
```json
{ "enabled": true, "near": false }
```
Sent on every state change plus a ~60 s keepalive while enabled; `enabled: false`
is sent once when the gate turns off. The Mac treats a report older than 180 s
(3 missed keepalives) as stale and hides the status. Response `200`; `401`
(token), `400` (undecryptable/bad body). Additive since v2 — an older Mac
answers `404` and the phone just ignores it.

### `GET /screenshot/latest?skip=N`
Return the `skip`-th most recent screenshot from the peer's gallery (`skip`
defaults to `0` = newest, `1` = second-newest, …). Android implements this by
querying MediaStore for images under the Screenshots bucket
(`Pictures/Screenshots` or `DCIM/Screenshots`), newest `date_added` first, and
reading row `skip`. The Mac's multi-item pull asks `skip=0,1,2,…` to collect the N
most recent.

Response `200`: body = raw image bytes; headers `X-RD2Mac-Filename`,
`Content-Type`, `Content-Length`. `404` if none found — **including `skip` at or
beyond the item count**, which is how the Mac's pull loop knows to stop. `skip` is
additive (no version bump): an old phone ignores it and always serves the newest,
so ship the skip support on both sides together to avoid duplicate pulls.

### `GET /media/latest?type=image|video&skip=N`
Like `/screenshot/latest` but for the `skip`-th most recent gallery item of the
given type (`type` defaults to `image`, `skip` to `0`). Same response shape.

## Reliability contract (the phantom-send fix)

1. A sender considers a transfer successful **only** on an HTTP `200` whose
   sealed body opens with the pairing key (v2 — see `/receive`).
2. On any failure (timeout, connection refused, `5xx`, unopenable `200`,
   DNS/discovery miss) the sender **retries** with backoff (3 attempts, delays
   0s / 0.5s / 1.5s before each), re-resolving the peer between attempts.
3. The UI/notification reflects the *real* outcome: `Sent ✓` only after `200`,
   otherwise an explicit `Failed ✗`. Never a silent success.

## Versioning

`v: 2` — sealed `/receive` confirmation + sealed filename headers on paired
transfers (v1 sent both in the clear). Any breaking change bumps `v`; peers of
different `v` refuse and surface a clear "update the other app" message.
