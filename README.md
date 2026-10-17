# Audio PC

**English** · [Español](README.es.md)

Control your Windows PC's audio from your phone over the local network. Switch the default
output device (TV, headphones, speakers...), change the volume and mute, all from an Android
app, home-screen widgets, Quick Settings tiles or a web panel that works in any browser
(iPhone included). The PC runs a single portable `AudioPC.exe` with a tray icon; the app finds
it by itself and pairs with one tap; a 6-digit code can be switched on as a second factor.

## Features

- Switch the default output device, set volume and mute.
- Device names and photos you configure yourself (`devices.json`), with automatic names for the rest.
- Automatic discovery on the LAN (mDNS, UDP broadcast, subnet sweep). The phone remembers the PC
  by its id, so IP changes don't matter.
- Pairing without typing anything: finding the PC is enough, and the PC shows a notification for
  each new device. Optional 6-digit code (tray: **Require a code to pair**); per-device tokens,
  revocable from the tray.
- Live updates over WebSocket (changes made in Windows show up on the phone).
- Android home-screen widgets and Quick Settings tiles.
- Web panel for any browser, served by the PC itself.
- Home Assistant compatible legacy endpoints.
- Single exe, no installer, optional start with Windows.

## Screenshots

| App | Widgets | Web panel |
|---|---|---|
| ![App home](docs/screenshots/app-home.png) | ![Widgets](docs/screenshots/widgets.png) | ![Web panel](docs/screenshots/web-panel.png) |

## Requirements

- PC: Windows 10 1809+ or Windows 11, x64.
- Phone: Android 9+ (or any browser for the web panel).
- Phone and PC on the same local network.

## Quick start

1. Download `AudioPC.exe` from the [Releases](https://github.com/edgarguitarist/audio-pc/releases) page and run it.
2. On first run, Windows Firewall asks for access: allow **Private networks**.
3. Install the APK from the same release on your phone.
4. Open the app. It finds the PC automatically.
5. Tap it. That's it (if you turned on **Require a code to pair**, type the code the PC shows).

To use the web panel instead, open `http://<pc-ip>:7799/` in a browser and pair the same way.

## Customizing devices

Edit `%APPDATA%\AudioPC\devices.json`. Changes apply live, no restart. `match` and `hide` are
case-insensitive substrings of the Windows device name; the first matching entry wins.
Put photos in `%APPDATA%\AudioPC\images\` (file names: lowercase letters, digits and `-`; `.webp`,
`.png` or `.jpg`).

```json
{
  "hide": ["NVIDIA Broadcast"],
  "devices": [
    {
      "match": "JBL Tour Pro 3",
      "name": "JBL Tour Pro 3",
      "detail": "Wireless earbuds",
      "kind": "earbuds",
      "image": "jbl-tour-pro-3.webp"
    }
  ]
}
```

`kind` is one of `speaker`, `headphones`, `headset`, `earbuds`, `tv`, `virtual`. Devices without an
entry get a name and kind derived from the Windows name. Details in
[docs/API.md](docs/API.md#6-server-data-directory-and-display-config).

## Home Assistant

Set `legacyToken` in `%APPDATA%\AudioPC\config.json` (and optional `presets`), then use the legacy
endpoints:

```json
{ "serverId": "...", "port": 7799, "name": null, "legacyToken": "my-secret-token",
  "presets": { "tv": "TCL QLED C655", "headphones": "JBL" } }
```

```yaml
rest_command:
  audio_tv:
    url: "http://192.168.1.50:7799/audio/tv"
    method: POST
    headers:
      Authorization: "Bearer my-secret-token"
  audio_set_output:
    url: "http://192.168.1.50:7799/audio/set?name={{ name }}"
    method: POST
    headers:
      Authorization: "Bearer my-secret-token"
  audio_volume:
    url: "http://192.168.1.50:7799/audio/volume?level={{ level }}"
    method: POST
    headers:
      Authorization: "Bearer my-secret-token"
  audio_mute:
    url: "http://192.168.1.50:7799/audio/mute?state=toggle"
    method: POST
    headers:
      Authorization: "Bearer my-secret-token"
```

## Tray menu

- **Start with Windows** toggle.
- **Paired devices**: list and revoke.
- **Require a code to pair** (off by default): new devices must type a 6-digit code shown on the
  PC. Turn it on if you don't trust everyone on your network.
- **Show the Windows volume indicator** (on by default): volume and mute changes from the phone also
  pop up the usual Windows volume flyout on the PC.
- **Open data folder** (`%APPDATA%\AudioPC`).

## Troubleshooting

- The phone can't find the PC: both must be on the same network. Guest Wi-Fi and routers with
  AP/client isolation block device-to-device traffic.
- Check the Windows Firewall allowed `AudioPC.exe` on **Private networks** (and that your
  network is marked Private).
- Add the PC by hand in the app as `host:port` (default port `7799`).
- Android 17 and later require the "local network" permission: grant it to the app.
- Discovery uses UDP port 7798; the API uses TCP port 7799.

## Security

- Audio PC is meant for your home LAN only. It speaks plain HTTP, protected by per-device tokens.
- Tokens are stored hashed (SHA-256) on the PC; a device can be revoked from the tray at any time.
- **Never port-forward it to the internet.**

## Building from source

Server (needs the .NET 10 SDK, Windows):

```powershell
dotnet publish server/AudioPc.Server -c Release -r win-x64 -o out
# -> out/AudioPC.exe
```

Android (needs JDK 17):

```bash
cd android
./gradlew assembleRelease
# -> app/build/outputs/apk/release/
```

Without a signing key configured, the release APK is signed with the debug key. To sign it,
set `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and
`ANDROID_KEY_PASSWORD` (environment variables or Gradle properties).

## Project structure

```
server/   Windows exe (.NET 10): API, web panel, discovery, tray
android/  Android app (Kotlin, Jetpack Compose, Glance widgets)
docs/     API.md (contract), ARCHITECTURE.md, icon, screenshots
```

## API

The HTTP/WebSocket contract is in [docs/API.md](docs/API.md); an overview is in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## License

[MIT](LICENSE)
