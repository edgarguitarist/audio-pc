# Audio PC

[English](README.md) · **Español**

Controla el audio de tu PC con Windows desde el celular, por la red local. Cambia el dispositivo
de salida predeterminado (TV, audífonos, parlantes...), el volumen y el silencio desde una app
Android, widgets en la pantalla de inicio, mosaicos de Ajustes rápidos o un panel web que funciona
en cualquier navegador (también en iPhone). El PC ejecuta un único `AudioPC.exe` portátil con
ícono en la bandeja; la app lo encuentra sola y se vincula con un código de 6 dígitos.

## Características

- Cambiar el dispositivo de salida predeterminado, el volumen y el silencio.
- Nombres y fotos de dispositivos que tú configuras (`devices.json`), con nombres automáticos para el resto.
- Descubrimiento automático en la red (mDNS, broadcast UDP, barrido de la subred). El celular
  recuerda la PC por su id, así que no importa si cambia la IP.
- Vinculación con un código de 6 dígitos que se muestra en la PC; tokens por dispositivo, revocables desde la bandeja.
- Actualizaciones en vivo por WebSocket (los cambios hechos en Windows aparecen en el celular).
- Widgets de pantalla de inicio y mosaicos de Ajustes rápidos en Android.
- Panel web para cualquier navegador, servido por el propio PC.
- Endpoints heredados compatibles con Home Assistant.
- Un solo exe, sin instalador, con inicio opcional con Windows.

## Capturas

| App | Widgets | Panel web |
|---|---|---|
| ![Inicio de la app](docs/screenshots/app-home.png) | ![Widgets](docs/screenshots/widgets.png) | ![Panel web](docs/screenshots/web-panel.png) |

## Requisitos

- PC: Windows 10 1809+ o Windows 11, x64.
- Celular: Android 9+ (o cualquier navegador para el panel web).
- Celular y PC en la misma red local.

## Inicio rápido

1. Descarga `AudioPC.exe` desde la página de [Releases](https://github.com/edgarguitarist/audio-pc/releases) y ejecútalo.
2. La primera vez, el Firewall de Windows pide acceso: permite **Redes privadas**.
3. Instala el APK de la misma release en tu celular.
4. Abre la app: encuentra la PC automáticamente.
5. Escribe el código de 6 dígitos que aparece en la PC.

Para usar el panel web, abre `http://<ip-del-pc>:7799/` en un navegador y vincula igual.

## Personalizar dispositivos

Edita `%APPDATA%\AudioPC\devices.json`. Los cambios se aplican al instante, sin reiniciar.
`match` y `hide` son subcadenas (sin distinguir mayúsculas) del nombre del dispositivo en Windows;
gana la primera entrada que coincida. Las fotos van en `%APPDATA%\AudioPC\images\` (nombres con
minúsculas, dígitos y `-`; `.webp`, `.png` o `.jpg`).

```json
{
  "hide": ["NVIDIA Broadcast"],
  "devices": [
    {
      "match": "JBL Tour Pro 3",
      "name": "JBL Tour Pro 3",
      "detail": "Audífonos inalámbricos",
      "kind": "earbuds",
      "image": "jbl-tour-pro-3.webp"
    }
  ]
}
```

`kind` es uno de `speaker`, `headphones`, `headset`, `earbuds`, `tv`, `virtual`. Los dispositivos sin
entrada reciben un nombre y tipo derivados del nombre de Windows. Detalles en
[docs/API.md](docs/API.md#6-server-data-directory-and-display-config).

## Home Assistant

Define `legacyToken` en `%APPDATA%\AudioPC\config.json` (y opcionalmente `presets`) y usa los
endpoints heredados:

```json
{ "serverId": "...", "port": 7799, "name": null, "legacyToken": "mi-token-secreto",
  "presets": { "tv": "TCL QLED C655", "headphones": "JBL" } }
```

```yaml
rest_command:
  audio_tv:
    url: "http://192.168.1.50:7799/audio/tv"
    method: POST
    headers:
      Authorization: "Bearer mi-token-secreto"
  audio_set_output:
    url: "http://192.168.1.50:7799/audio/set?name={{ name }}"
    method: POST
    headers:
      Authorization: "Bearer mi-token-secreto"
  audio_volume:
    url: "http://192.168.1.50:7799/audio/volume?level={{ level }}"
    method: POST
    headers:
      Authorization: "Bearer mi-token-secreto"
  audio_mute:
    url: "http://192.168.1.50:7799/audio/mute?state=toggle"
    method: POST
    headers:
      Authorization: "Bearer mi-token-secreto"
```

## Menú de la bandeja

- Opción **Iniciar con Windows**.
- **Dispositivos vinculados**: lista y revocación.
- **Abrir carpeta de datos** (`%APPDATA%\AudioPC`).

## Solución de problemas

- El celular no encuentra la PC: ambos deben estar en la misma red. El Wi-Fi de invitados y los
  routers con aislamiento de clientes (AP isolation) bloquean el tráfico entre dispositivos.
- Comprueba que el Firewall de Windows permitió `AudioPC.exe` en **Redes privadas** (y que tu red
  esté marcada como Privada).
- Añade la PC a mano en la app como `host:puerto` (puerto por defecto `7799`).
- Android 17 y posteriores exigen el permiso de "red local": concédeselo a la app.
- El descubrimiento usa el puerto UDP 7798; la API, el TCP 7799.

## Seguridad

- Audio PC está pensado solo para tu red doméstica. Usa HTTP sin cifrar, protegido con tokens por dispositivo.
- Los tokens se guardan con hash (SHA-256) en la PC; cualquier dispositivo se puede revocar desde la bandeja.
- **Nunca abras el puerto hacia internet.**

## Compilar desde el código

Servidor (requiere el SDK de .NET 10, Windows):

```powershell
dotnet publish server/AudioPc.Server -c Release -r win-x64 -o out
# -> out/AudioPC.exe
```

Android (requiere JDK 17):

```bash
cd android
./gradlew assembleRelease
# -> app/build/outputs/apk/release/
```

Sin llave de firma configurada, el APK de release se firma con la llave de debug. Para firmarlo,
define `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` y
`ANDROID_KEY_PASSWORD` (variables de entorno o propiedades de Gradle).

## Estructura del proyecto

```
server/   Exe de Windows (.NET 10): API, panel web, descubrimiento, bandeja
android/  App Android (Kotlin, Jetpack Compose, widgets Glance)
docs/     API.md (contrato), ARCHITECTURE.md, ícono, capturas
```

## API

El contrato HTTP/WebSocket está en [docs/API.md](docs/API.md); una visión general, en
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Licencia

[MIT](LICENSE)
