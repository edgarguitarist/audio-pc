# Audio PC — API contract (v1)

This document is the single source of truth shared by the Windows server (`server/`), the
Android app (`android/`) and the web panel (`server/AudioPc.Server/wwwroot/`). If something
here is ambiguous, fix this document first, then the code.

- Transport: plain HTTP on the LAN, JSON bodies (`application/json; charset=utf-8`), UTF-8.
- Default port: **7799** (configurable). UDP discovery port: **7798**.
- All JSON property names are **camelCase**.
- Errors: non-2xx status with body `{ "error": "<code>", "message": "<human text>" }`.
  Error codes used: `unauthorized`, `forbidden`, `not_found`, `bad_request`,
  `pairing_expired`, `pairing_bad_code`, `pairing_locked`, `audio_error`.

## 1. Discovery

A client finds servers on the LAN with three methods, tried in this order (the Android app
runs 1 and 2 at the same time, then 3 if nothing answered within ~2 s):

1. **mDNS / DNS-SD**: the server registers service type `_audiopc._tcp` (domain `local.`),
   instance name = the PC name (e.g. `PC-EDGAR`), on the HTTP port.
   TXT records: `id=<serverId>`, `name=<display name>`, `api=1`, `ver=<server version>`.
2. **UDP broadcast**: the client sends the ASCII datagram `AUDIOPC_DISCOVER 1` to
   `255.255.255.255:7798` (and to the subnet broadcast address). Every server answers
   **unicast to the sender's address/port** with the same JSON as `GET /api/info`, plus the
   field `"port": 7799`.
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
  "version": "1.0.0"
}
```

`id` is a random 128-bit hex string generated on first run and persisted.

## 2. Pairing and auth

Every request below `/api/` except `/api/info`, `/api/pair/*` and `/api/images/*` needs
`Authorization: Bearer <token>`. WebSocket clients that cannot send headers (browsers) may
pass `?token=<token>` instead.

Pairing (trust on first use, like Bluetooth):

1. `POST /api/pair/request` — body `{ "deviceName": "Poco F7", "deviceId": "<client-generated stable id>" }`
   → `200 { "requestId": "<opaque>", "expiresIn": 120 }`.
   The server generates a random **6-digit code** and shows it on the PC (tray balloon /
   notification + the tray window), together with `deviceName`. One pending request per
   `deviceId`; a new request replaces the old one.
2. The user types the code on the phone.
3. `POST /api/pair/confirm` — body `{ "requestId": "...", "code": "123456" }`
   → `200 { "token": "<secret>", "serverId": "...", "serverName": "PC-EDGAR" }`.
   - Wrong code → `403 pairing_bad_code` (5 wrong attempts per request → `423 pairing_locked`,
     the request is discarded).
   - Unknown/expired request → `410 pairing_expired`.

Tokens: 32 random bytes, base64url without padding. The server stores only their SHA-256 hash,
with `deviceId`, `deviceName`, `createdAt`, `lastSeen`. Pairing again from the same
`deviceId` replaces the previous token. The user can revoke devices from the tray menu.

A request with a missing/invalid token gets `401 unauthorized`; the app then goes back to
pairing for that server.

**Legacy token**: the config may define `legacyToken` (the token Home Assistant already uses).
It is accepted anywhere a paired token is, so existing integrations keep working.

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
→ `200 { "volume": 45, "muted": false }`.

### `POST /api/mute`

Body: `{ "state": "on" | "off" | "toggle" }` → `200 { "volume": 45, "muted": true }`.

### `GET /api/images/{file}` — no auth

Serves `<dataDir>/images/{file}`; `file` must match `^[a-z0-9-]+\.(webp|png|jpg)$`.
`Cache-Control: public, max-age=31536000, immutable` (the `?v=` query busts the cache).

## 4. Live events — WebSocket `GET /api/events`

Auth: `Authorization` header or `?token=`. After the upgrade the server sends, as text frames:

- immediately: `{ "type": "state", "state": { ...same as GET /api/state... } }`
- on any change (default device changed, device added/removed/state changed, volume or mute
  changed — from the app or from Windows itself): the same `state` message. Volume changes are
  coalesced to at most one message every 100 ms.
- every 30 s: `{ "type": "ping" }` (clients ignore it).

Clients send nothing (they use the HTTP endpoints for commands). If the socket drops, clients
reconnect with backoff (1 s, 2 s, 5 s, then every 10 s) and fall back to polling
`GET /api/state` every 15 s while disconnected.

## 5. Legacy endpoints (compatibility with the old Node server / Home Assistant)

Same auth as above (legacy token or paired token; `?token=` also accepted).

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

- `config.json` — `{ "serverId", "port": 7799, "name": null, "legacyToken": null, "presets": {} }`
  (`name: null` → use the PC name).
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
  `tv` (\btv\b|hdmi|high definition audio|display audio|tcl|bravia|samsung|lg tv|monitor),
  `headset` (headset|micrófono|microfono|hs\d|arctis|cloud|blackshark),
  `earbuds` (buds|airpods|tour pro|wf-|earbuds|tws|live pro|jbl),
  `headphones` (auricular|audífono|audifono|headphone|cascos|wh-), else `speaker`.
- Kind labels: Spanish `Altavoces, Audífonos, Auriculares, Audífonos, Televisor, Virtual`
  (speaker, headphones, headset, earbuds, tv, virtual) — the server answers in the language of
  `Accept-Language` (`es*` → Spanish, otherwise English: `Speakers, Headphones, Headset, Earbuds, TV, Virtual`).

## 7. Web panel

The server serves the web panel from `wwwroot/` at `/`. The panel has no token embedded: on
first use it runs the same pairing flow (`deviceName` = "Navegador"/"Browser" + user agent
summary) and keeps the token in `localStorage`.
