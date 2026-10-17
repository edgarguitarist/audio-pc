# Audio PC — API contract (v1)

This document is the single source of truth shared by the Windows server (`server/`), the
Android app (`android/`) and the web panel (`server/AudioPc.Server/wwwroot/`). If something
here is ambiguous, fix this document first, then the code.

- Transport: plain HTTP on the LAN, JSON bodies (`application/json; charset=utf-8`), UTF-8.
- Default port: **7799** (configurable). UDP discovery port: **7798**.
- All JSON property names are **camelCase**.
- Errors: non-2xx status with body `{ "error": "<code>", "message": "<human text>" }`.
  Error codes used: `unauthorized`, `forbidden`, `not_found`, `bad_request`,
  `pairing_expired`, `pairing_bad_code`, `pairing_locked`, `rate_limited`,
  `unsupported_media_type`, `misdirected_request`, `audio_error`.

| Status | Code | When |
|---|---|---|
| 400 | `bad_request` | malformed body or parameters |
| 401 | `unauthorized` | missing/invalid token |
| 403 | `forbidden` | WebSocket with a foreign `Origin` (§4) |
| 403 | `pairing_bad_code` | wrong pairing code |
| 404 | `not_found` | unknown output / endpoint / file |
| 410 | `pairing_expired` | unknown or expired pairing request |
| 415 | `unsupported_media_type` | a `POST /api/*` without `Content-Type: application/json` |
| 421 | `misdirected_request` | `Host` header that is not a name of this PC (see below) |
| 423 | `pairing_locked` | too many wrong codes (per request, or the global lock of §2) |
| 429 | `rate_limited` | a rate, connection or paired-device limit was hit (§2, §4); retry later |
| 500 | `audio_error` | Windows audio call failed |

- **Request rules** (they protect the API from web pages open in a browser on the LAN):
  - Every `POST /api/*` must send `Content-Type: application/json` (parameters such as
    `charset` are ignored), otherwise `415`. A cross-origin page cannot send that header without a
    CORS preflight, and the server never answers preflights. The legacy `/audio/*` POSTs (§5)
    take query parameters and need no `Content-Type`.
  - The `Host` header must be an IP literal (IPv4/IPv6), `localhost`, the PC name, `<PC name>.local`,
    `<PC name>.<DNS suffix of a network adapter>`, the configured display name,
    `host.docker.internal`, or a name listed in `allowedHosts` (§6). Anything else gets
    `421 misdirected_request` (DNS-rebinding defence). This applies to every path and port.
  - Responses of `/api/*`, `/audio/*` and `/health` carry `Cache-Control: no-store` (except
    `/api/images/*`, see §3).

## 1. Discovery

A client finds servers on the LAN with three methods, tried in this order (the Android app
runs 1 and 2 at the same time, then 3 if nothing answered within ~2 s):

1. **mDNS / DNS-SD**: the server registers service type `_audiopc._tcp` (domain `local.`),
   instance name = the PC name (e.g. `PC-EDGAR`), on the HTTP port.
   TXT records: `id=<serverId>`, `name=<display name>`, `api=1`, `ver=<server version>`.
2. **UDP broadcast**: the client sends the ASCII datagram `AUDIOPC_DISCOVER 1` to
   `255.255.255.255:7798` (and to the subnet broadcast address). Every server answers
   **unicast to the sender's address/port** with the same JSON as `GET /api/info`, plus the
   field `"port": 7799`. The datagram must be exactly those 18 bytes (anything else is ignored),
   and replies are rate limited (5/s per source address, 50/s in total) so the server cannot be
   used as a UDP reflector.
3. **Subnet sweep**: the client calls `GET http://<ip>:7799/api/info` for every host of its
   /24 (Wi-Fi) subnet, ~64 at a time, 600 ms timeout. A host is a server if the response has
   `"service": "audio-pc"`.

The client remembers servers by **`serverId`**, never by IP. On start it tries the last known
`host:port`; if that fails or answers a different `serverId`, it rediscovers and updates the
host of the server with the same `serverId`. Users can also add a server by hand
(`host[:port]`).

### `GET /api/info` — no auth

```json
{
  "service": "audio-pc",
  "apiVersion": 1,
  "id": "6f1c2a3e8b4d4c559a103e2f7d5b1a01",
  "name": "PC-EDGAR",
  "version": "1.0.0",
  "pairing": "open"
}
```

`id` is a random 128-bit hex string generated on first run and persisted. `pairing` is `"open"`
or `"code"` (§2); it is only a hint for the UI — clients must act on the response of
`POST /api/pair/request`, since the owner can switch the mode at any time. Older servers omit it.

## 2. Pairing and auth

Every request below `/api/` except `/api/info`, `/api/pair/*` and `/api/images/*` needs
`Authorization: Bearer <token>`. WebSocket clients that cannot send headers (browsers) may
pass `?token=<token>` instead. For compatibility with the old Node server, an `Authorization`
header without the `Bearer ` scheme is taken as the bare token (`Authorization: <token>`).

**Failed-auth throttling**: after 20 requests answered `401` from the same IP address within a
minute, that address gets `429 rate_limited` for one minute (even with a valid token).

Pairing has two modes, chosen by the owner on the PC with the tray item **"Require a code to
pair"** (config `requirePairingCode`, §6). Both start with the same request, and **the response
tells the client which mode is active**:

`POST /api/pair/request` — body `{ "deviceName": "Poco F7", "deviceId": "<client-generated stable id>" }`.
`deviceName` (max 64) and `deviceId` (max 128) are required; control and format characters
(including bidi overrides) are stripped and the result trimmed before the limits apply, and an
empty result is `400 bad_request`. The request rules at the top (Content-Type, `Host`) apply.

- Response has `token` → paired (open mode). Response has `requestId` → show the code screen
  (code mode). Clients must accept both (and ignore unknown fields).

### 2.1 Open mode (default: `requirePairingCode: false`)

Finding the server is enough (trust on first use, like a Bluetooth speaker without a PIN):

- `POST /api/pair/request` → `200 { "token": "<secret>", "serverId": "...", "serverName": "PC-EDGAR" }`,
  the same shape as `/api/pair/confirm`. The token is stored exactly like a confirmed pairing
  (pairing again from the same `deviceId` replaces its previous token).
- The PC shows **one tray balloon** per pairing, "<deviceName> connected to Audio PC" (sanitized
  name), so the owner notices new devices, and logs it. Revoking from the tray still works, but
  in open mode a revoked device can simply pair again; to keep devices out, turn the code on.
- Limits (all `429 rate_limited`), so a script on the LAN cannot mint unbounded tokens or
  `paired.json` entries: 10 requests per minute per IP address; 30 requests per minute in
  total, across all addresses; at most **50 paired devices** — a new `deviceId` beyond that is refused with a
  message telling the owner to revoke devices from the tray (the oldest device is **not**
  evicted: that would let anyone on the LAN kick the owner's devices out). Re-pairing a
  `deviceId` that is already paired is always allowed.
- `/api/pair/confirm` is not used; any `requestId` is unknown → `410 pairing_expired`.

### 2.2 Code mode (`requirePairingCode: true`) — an optional second factor

On top of finding the server, the user must type a code that only the PC shows:

1. `POST /api/pair/request` → `200 { "requestId": "<opaque>", "expiresIn": 120 }`.
   The server generates a random **6-digit code** and shows it on the PC (tray balloon /
   notification + the tray menu), together with `deviceName`. One pending request per
   `deviceId`: a new request from the same IP address replaces the old one.
   - Limits (all `429 rate_limited`): 5 requests per minute per IP address; at most 2 pending
     requests per IP address; at most 10 pending requests in total. A new request never evicts
     someone else's pending request.
2. The user types the code on the phone (the clients explain that the code is in the PC
   notification or behind a right-click on the tray icon).
3. `POST /api/pair/confirm` — body `{ "requestId": "...", "code": "123456" }`
   → `200 { "token": "<secret>", "serverId": "...", "serverName": "PC-EDGAR" }`.
   - Wrong code → `403 pairing_bad_code` (5 wrong attempts per request → `423 pairing_locked`,
     the request is discarded).
   - Unknown/expired request → `410 pairing_expired`.
   - **Global lock**: after 10 wrong codes within 10 minutes, counted across all requests and
     all IP addresses, pairing is disabled: every pending request is discarded and both
     `/api/pair/request` and `/api/pair/confirm` answer `423 pairing_locked` (with a message
     telling the user what to do) until the user chooses "Allow pairing again" in the tray menu.
     The tray shows one balloon when this happens. Already paired devices are not affected.
     The lock only exists in code mode: switching the tray item to open mode implies unlocking
     (and discards pending codes, whose clients get `410` and simply request again).

Switching modes never affects already paired devices; it only changes how new pairings work.

Tokens: 32 random bytes, base64url without padding. The server stores only their SHA-256 hash,
with `deviceId`, `deviceName`, `createdAt`, `lastSeen`. Pairing again from the same
`deviceId` replaces the previous token. The user can revoke devices from the tray menu.

A request with a missing/invalid token gets `401 unauthorized`; the app then goes back to
pairing for that server.

**Legacy token**: the config may define `legacyToken` (the token Home Assistant already uses).
It is accepted anywhere a paired token is, so existing integrations keep working. A blank
legacy token, or one shorter than 16 characters, is ignored (with a warning in the log).

## 3. State

### `GET /api/state`

```json
{
  "currentId": "{0.0.0.00000000}.{c524959f-c91d-4671-8569-c2d316264d2d}",
  "volume": 45,
  "muted": false,
  "outputs": [
    {
      "id": "{0.0.0.00000000}.{c524959f-c91d-4671-8569-c2d316264d2d}",
      "rawName": "Auriculares (2- JBL Tour Pro 3)",
      "name": "JBL Tour Pro 3",
      "detail": "Audífonos inalámbricos",
      "kind": "earbuds",
      "image": "/api/images/jbl-tour-pro-3.webp?v=1791979936977",
      "isDefault": true,
      "hidden": false
    }
  ]
}
```

- `outputs`: active playback endpoints, in Windows order. `hidden` outputs are included (so a
  hidden output that is the current one can still be shown) — clients must not list them.
- `volume` (0–100, integer) and `muted` are those of the current default output.
- `kind`: one of `speaker`, `headphones`, `headset`, `earbuds`, `tv`, `virtual`.
- `name`, `detail`, `kind`, `image`, `hidden` come from the server's display config (§6); when
  no entry matches, the server derives them from `rawName` (§6.2). `image` is `null` when
  there is none. Image URLs are relative to the server base URL.

### `POST /api/output`

Body: `{ "id": "<output id>" }` **or** `{ "match": "JBL" }` (case-insensitive substring of
`rawName`, first match). Sets it as the default device for all three roles (console,
multimedia, communications). → `200` with the full state (same shape as `GET /api/state`).
Unknown id / no match → `404 not_found`.

### `POST /api/volume`

Body: `{ "level": 45 }` (0–100) **or** `{ "delta": -10 }`. Values are clamped to 0–100.
Setting a level, or a positive delta, also unmutes (like the Windows volume keys).
The level is applied exactly and the response returns at once; the PC then shows the native
Windows volume flyout (see `showVolumeOsd`, §6), at most one every 250 ms while a slider is dragged.
→ `200 { "volume": 45, "muted": false }`.

### `POST /api/mute`

Body: `{ "state": "on" | "off" | "toggle" }` → `200 { "volume": 45, "muted": true }`.
A real change also shows the Windows flyout (`showVolumeOsd`); asking for the state it already has
does nothing.

### `GET /api/images/{file}` — no auth

Serves `<dataDir>/images/{file}`; `file` must match `^[a-z0-9-]+\.(webp|png|jpg)$`.
`Cache-Control: public, max-age=31536000, immutable` (the `?v=` query busts the cache).

## 4. Live events — WebSocket `GET /api/events`

Auth: `Authorization` header or `?token=`. If the upgrade request has an `Origin` header, its
host and port must equal the request's `Host`, otherwise `403 forbidden` (a page of another
site cannot open the socket). Limits: 8 open sockets per paired device (or legacy token) and 64
in total; beyond that the upgrade is refused with `429 rate_limited`. A send that does not
complete within 5 s (the client stopped reading) closes that socket.

After the upgrade the server sends, as text frames:

- immediately: `{ "type": "state", "state": { ...same as GET /api/state... } }`
- on any change (default device changed, device added/removed/state changed, volume or mute
  changed — from the app or from Windows itself): the same `state` message. Volume changes are
  coalesced to at most one message every 100 ms.
- every 30 s: `{ "type": "ping" }` (clients ignore it).

Clients send nothing (they use the HTTP endpoints for commands). If the socket drops, clients
reconnect with backoff (1 s, 2 s, 5 s, then every 10 s) and fall back to polling
`GET /api/state` every 15 s while disconnected.

## 5. Legacy endpoints (compatibility with the old Node server / Home Assistant)

Same auth as above (legacy token or paired token; `?token=` also accepted; bare
`Authorization: <token>` too). These POSTs take query parameters and need no body or
`Content-Type`.

| Method | Path | Behaviour |
|---|---|---|
| GET | `/health` | `{ "ok": true }`, no auth |
| GET | `/audio/list` | `{ "devices": [{ "Index", "Default", "Name", "ID" }], "volume", "muted" }` |
| POST | `/audio/set?name=X` / `?id=X` | set default by substring / id → `{ "ok": true, "device": "<rawName>" }`, not found → `500 { "error": "ERR:notfound" }` |
| POST | `/audio/{preset}` | presets from config (`presets: { "tv": "TCL QLED C655", "headphones": "JBL" }`), same response |
| GET | `/audio/volume` | `{ "ok": true, "volume", "muted" }` |
| POST | `/audio/volume?level=N` / `?delta=N` | `{ "ok": true, "volume", "muted" }` |
| POST | `/audio/mute?state=on\|off\|toggle` | `{ "ok": true, "volume", "muted" }` |

## 6. Server data directory and display config

`<dataDir>` = `%APPDATA%\AudioPC\`:

- `config.json` — `{ "serverId", "port": 7799, "name": null, "legacyToken": null, "presets": {}, "allowedHosts": null, "requirePairingCode": false, "showVolumeOsd": true }`
  (`name: null` → use the PC name).
  - `requirePairingCode`: `false` (default, also when the field is missing) = open pairing,
    `true` = code mode (§2). The tray item "Require a code to pair" toggles it and saves it at
    once, rewriting only that field (every other field, known or not, is kept). Read at startup
    and changed live only through the tray.
  - `showVolumeOsd`: `true` (default, also when missing) = volume/mute changes made through the API
    (`/api/volume`, `/api/mute` and the legacy `/audio/volume`, `/audio/mute`) also show the native
    Windows volume flyout, as if a keyboard volume key were pressed. Windows has no API for it, so the
    server sends a net-zero pair of volume keys (or the mute key) and then checks the level/mute and
    restores the exact value through Core Audio if a key was dropped or blocked (e.g. an elevated
    foreground app). Changes made by Windows itself never trigger it. The tray item "Show the Windows
    volume indicator" toggles it and saves it at once, like `requirePairingCode`.
  - `allowedHosts`: optional array of extra host names accepted in the `Host` header (request
    rules at the top), e.g. `["audio.home.arpa", "pc-edgar.mydomain"]`, for clients that reach
    the PC by a DNS name not covered by the defaults. Case-insensitive, without port.
  - A hand-written file without `serverId` (e.g. `{ "legacyToken": "...", "presets": {...} }`)
    is kept: the server only adds `serverId` and preserves every other field. Only a file that
    cannot be parsed is moved to `config.json.bad` and recreated.
- `paired.json` — paired devices (token hashes).
- `devices.json` — display config (below). Re-read when it changes (file watcher).
- `images/` — device photos referenced by `devices.json`.

### 6.1 `devices.json`

```json
{
  "hide": ["NVIDIA Broadcast"],
  "devices": [
    { "match": "JBL Tour Pro 3", "name": "JBL Tour Pro 3", "detail": "Audífonos inalámbricos",
      "kind": "earbuds", "image": "jbl-tour-pro-3.webp" }
  ]
}
```

`match` / `hide` are case-insensitive substrings of `rawName`; the first matching entry wins.

### 6.2 Automatic description (no entry matches)

Windows names outputs `"<connector> (<device>)"`, e.g. `"Altavoces (JBL Tour Pro 3)"`.

- If the connector is generic (starts with altavoces, altavoz, speaker(s), auriculares,
  audífono(s)/audifono(s), headphone(s), cascos, salida, output, digital, realtek — case-insensitive),
  `name` = device part with a trailing ` Gaming Headset` / ` Headset` removed, `detail` = the
  kind label; otherwise `name` = connector part and `detail` = device part.
- `kind` from the whole raw name (first rule that matches):
  `virtual` (broadcast|virtual|vb-audio|voicemeeter|cable input/output|steam streaming|sonar|wave link),
  `tv` (\btv\b|hdmi|(nvidia|amd|ati|intel).*high definition audio|display audio|tcl|bravia|samsung|lg tv|monitor),
  `headset` (headset|micrófono|microfono|hs\d|arctis|cloud|blackshark),
  `earbuds` (buds|airpods|tour pro|wf-|earbuds|tws|live pro|jbl),
  `headphones` (auricular|audífono|audifono|headphone|cascos|wh-), else `speaker`.
- Kind labels: Spanish `Altavoces, Audífonos, Auriculares, Audífonos, Televisor, Virtual`
  (speaker, headphones, headset, earbuds, tv, virtual) — the server answers in the language of
  `Accept-Language` (`es*` → Spanish, otherwise English: `Speakers, Headphones, Headset, Earbuds, TV, Virtual`).

## 7. Web panel

The server serves the web panel from `wwwroot/` at `/`. The panel has no token embedded: on
first use it runs the same pairing flow (`deviceName` = "Navegador"/"Browser" + user agent
summary) and keeps the token in `localStorage`. In open mode the request already returns the
token, so no code UI is shown; after "Unpair this browser" the panel waits for the user to click
"Pair" instead of pairing again on its own.

Panel responses carry `Content-Security-Policy: default-src 'self'; img-src 'self' data:;
style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self' ws: wss:;
frame-ancestors 'none'; base-uri 'none'; form-action 'none'`, plus `X-Frame-Options: DENY`,
`X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`. The panel must therefore
load nothing from other origins, and every JSON `fetch` must set `Content-Type: application/json`
explicitly (a string body defaults to `text/plain`).
