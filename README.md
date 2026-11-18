# MeshLink

**Free messaging and calling that works without internet.**

MeshLink is an Android messenger that talks to other phones directly over the
phone's own radios — no servers, no accounts, no data plan and no Wi-Fi router.
An optional **online mode** adds worldwide messaging over ordinary Wi-Fi or
mobile data, for when the other person is not nearby.

| Radio | Needs | Typical range |
|-------|-------|---------------|
| **Wi-Fi Direct** | nothing (phone-to-phone Wi-Fi) | 50–200 m per hop |
| **Bluetooth LE** | nothing | 10–50 m per hop |
| **Cellular radio (SMS)** | a SIM with signal | city / nationwide |
| **Online mode (www)** | any internet connection | worldwide |

Messages hop phone → phone → phone (5 hops by default), so a chat can cross a
crowd, a building or a village where the network is down.

## What's inside

- **WhatsApp-style interface** — chat list, per-chat conversations, calls tab,
  contacts and settings.
- **Chats** — bubbles, timestamps, date separators, unread badges, message
  status (queued / sent / delivered) and tap-to-retry when a radio can't reach
  the other phone.
- **Calls** — ring, accept, mute, speaker and hang up, with a live timer.
  The call button stays inside MeshLink: with online mode on it rings the other
  phone over the internet and WebRTC carries encrypted audio; otherwise it
  uses the shared Wi-Fi/hotspot or Wi-Fi Direct path. It never jumps to the
  phone's SIM dialer.
- **Online mode (www)** — switch it on in Settings to send messages anywhere in
  the world through an MQTT relay. Off by default; see *Online mode* below.
- **Photos and documents** — send a picture from the gallery, take a new one,
  or attach any document. Photos are auto-rotated and compressed; tap to view
  full screen, tap a document to open it. Transfers use Wi-Fi LAN → Wi-Fi
  Direct → Bluetooth LE (never SMS) and show live progress.
- **Call log** — incoming, outgoing and missed calls with duration.
- **Contacts** — saved on the device, one tap to chat or call.
- **Radio status** — live state of all three radios and one-tap pairing with
  nearby Wi-Fi Direct phones.
- **Relay hops** — choose how many phones a message may pass through
  (1–20, default 8) to trade speed for reach.
- **Store-and-forward** — undelivered messages keep retrying over Wi-Fi Direct
  and Bluetooth LE as the phone moves between people.
- **SMS reach** — add your phone number in Settings and messages fall back to
  SMS when no mesh peer is in range, so two users can keep chatting anywhere
  there is mobile signal. Empty by default: nothing is shared until you set it.
- **Fully offline identity** — the ID is generated on the phone. Nothing is
  uploaded anywhere.

Everything is stored in a local SQLite database on the phone. There is no
backend, no analytics and no internet permission requirement for messaging or
calling.

## Screens

`Chats` · `Calls` · `Contacts` · `Settings` — four tabs, Material 3, purple
theme, dark-mode aware.

## Build

Requirements: JDK 17+, Android SDK 34.

```bash
export ANDROID_HOME=$HOME/Android/Sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # unsigned release APK (F-Droid style)
```

Release signing is opt-in and controlled by environment variables
(`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Without
them the release APK is unsigned, exactly what F-Droid expects.

## Permissions

| Permission | Why |
|------------|-----|
| `SEND_SMS` / `RECEIVE_SMS` | messaging over the cellular radio |
| `CALL_PHONE` | optional SIM call fallback |
| `RECORD_AUDIO` / `MODIFY_AUDIO_SETTINGS` / `VIBRATE` | in-app voice calls and ringtone |
| `BLUETOOTH_*` | Bluetooth LE mesh |
| `ACCESS_FINE_LOCATION`, `NEARBY_WIFI_DEVICES` | Android requires these to scan for Wi-Fi Direct peers |
| `FOREGROUND_SERVICE*`, `POST_NOTIFICATIONS` | keeps the radios alive in the background |

`INTERNET` is declared only because Wi-Fi Direct sockets are TCP sockets, which
Android gates behind that permission. The app does not talk to any server.

## Status

- `1.5.0` — first-run privacy screen, one-shot permission request, privacy
  card in Settings, and a documented [privacy policy](PRIVACY.md).
- `1.4.0` — optional phone number with SMS fallback, so messages reach across
  the country when the radio mesh cannot.
- `1.3.0` — relay hops setting (1–20 phones), store-and-forward retries,
  coverage card, signed release builds.
- `1.2.0` — WhatsApp-style UI, persistent chats, working Wi-Fi Direct calls,
  delivery receipts, call log, contacts, fully offline signup.
- `1.1.0` — first public build with chat, push-to-talk voice and radio status.

## Security

MeshLink 1.2.0 does **not** encrypt message or call payloads in transit yet.
Messages can travel over SMS, Bluetooth LE, Wi-Fi Direct, Wi-Fi LAN and the
optional online relay, and anyone with the right radio equipment in range (or
the relay's logs) could see the metadata. Online-mode payloads are AES-GCM
encrypted; local-radio payloads are not yet. The identity key pair is
generated in the Android Keystore on the device and is never uploaded.

Transport encryption is planned; until then, treat MeshLink like a radio:
handy when networks are down, not a replacement for a secure messenger.

MeshLink collects no data at all. See [PRIVACY.md](PRIVACY.md) for the full
permission and storage breakdown.

## Photos and documents

Tap the paperclip in a chat. Photos are scaled to 1024 px and re-compressed
(usually 150–350 KB), documents are copied into the app's private storage, and
both are capped at 3 MB. The transfer is split into 90-byte mesh chunks and
reassembled on the other phone, so a dropped chunk only costs that chunk.

Wi-Fi Direct or the same Wi-Fi network is the right path for photos; Bluetooth
LE works but is slow (a photo can take about a minute). SMS is never used for
files. With online mode on, photos and documents also travel over the internet
in 48 KB pieces, so a contact abroad receives them in seconds. Local file
payloads are not end-to-end encrypted yet; online ones are AES-GCM encrypted.

## Online mode (worldwide messages)

Settings → **Online mode (www)**. When it is on, every text message is also
published to a relay so it can reach a phone on another network or another
continent. The default relay is a free public MQTT broker
(`ssl://broker.hivemq.com:8883`); the field in Settings accepts your own
broker address.

Each message frame is encrypted with AES-GCM. The key is derived from the two
UINs, so the relay and other subscribers see only ciphertext. This is honest
about its limits: it is not a verified end-to-end key exchange, the relay
operator can see who is talking to whom, and anyone who knows both UINs could
derive the key. Turn it off when you do not need it; local radios stay free,
private and instant.

Latency over the relay is usually a few hundred milliseconds. QoS 0 is used on
purpose — the mesh layer already retries and deduplicates.

## Online calls (internet)

Calls → **Online call (worldwide)**. There is no call server: one person
creates a code, the other pastes it and sends a reply code back, and the two
phones connect directly. Audio is encrypted with DTLS-SRTP, part of WebRTC,
so the media never passes through any MeshLink machine. Public STUN servers are
used only to discover the public address.

If a network is too strict for direct peer-to-peer audio, the app says so;
switching one phone to Wi-Fi or a hotspot usually fixes it. There is no TURN
relay because running one is not free at scale.

SMS fallback is off by default. Bluetooth, Wi-Fi Direct and Wi-Fi LAN messages
never include your SIM number; they carry only your `OFF-XXXXXX` ID.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
