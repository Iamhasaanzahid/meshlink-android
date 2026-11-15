# MeshLink (Android)

Free, secure, offline-first messenger. Sign up with **email only** — no phone
number required. Every device gets an auto-assigned **UIN** (e.g. `+92-MZ-123456`).

Messages are **end-to-end encrypted** (X25519 ECDH + HKDF-SHA256 + AES-256-GCM)
and can travel over three real radios with no internet:

| Transport | Radio | Needs | Typical range |
|-----------|-------|-------|---------------|
| **Radio (SMS)** | Cellular modem (GSM/CDMA/LTE/5G) | SIM + operator signal | city / nationwide |
| **Bluetooth LE** | 2.4 GHz BLE | nothing | 10–50 m per hop |
| **Wi-Fi Direct** | 2.4/5 GHz Wi-Fi | nothing | 50–200 m per hop |

Multi-hop TTL forwarding (default 5 hops) extends the local mesh.

## What the app does (v1.1)

- **Signup** — email only, or a fully offline `OFF-XXXXXX` ID when the server
  is unreachable. The UIN is remembered on the device.
- **Chat** — message any UIN (Bluetooth/Wi-Fi mesh) or phone number (SMS radio).
  Pick `Auto`, `SIM`, `Bluetooth` or `Wi-Fi` per message; Auto picks the right
  radio for the destination. Incoming messages appear instantly.
- **Call** — call a phone number over the cellular voice channel, or join a
  free **push-to-talk voice group over Wi-Fi Direct** (hold to talk).
- **Radios** — live status for all three radios, nearby Wi-Fi Direct peers with
  one-tap connect, current mesh node id.

Every radio runs at the same time in a foreground service, so messaging keeps
working while the app is in the background and after a reboot.

## Build without Android Studio

```bash
# one-time SDK install (command line tools)
export ANDROID_HOME=$HOME/Android/Sdk
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

cd meshlink_android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

> A plain FM radio receiver cannot transmit data, so the app uses the cellular
> modem — the phone radio that can both send and receive — for long-range radio
> messaging. This works even when mobile data and Wi-Fi are off.

### Addressing over the cellular radio

Your **UIN is your permanent identity**. To message someone over the cellular
radio, the two of you exchange your **radio number** (the SIM number) once, just
like sharing a contact. After that, MeshLink can deliver encrypted messages over
SMS with no internet. Destinations that look like a phone number
(`+923001234567`) automatically go over the cellular radio; UIN destinations use
Bluetooth LE / Wi-Fi Direct / the internet relay.

## Build (free, no Android SDK needed locally)

```bash
# Debug APK
./gradlew assembleDebug

# Release APK/AAB pointing at your own server
./gradlew assembleRelease -PmeshlinkApiUrl="https://your-host.onrender.com"
```

Or push to GitHub and use the included
`.github/workflows/android-build.yml` (free for public repos).

## Backend

The optional internet relay lives in `../meshlink_app` (FastAPI + SQLite).
Offline radio/BLE/Wi-Fi Direct messaging works without it.

## License

GPL-3.0-or-later. See `LICENSE`.

## F-Droid

See `../meshlink_android/FREE_PUBLISH.md` and `metadata/com.meshlink.app.yml`.
