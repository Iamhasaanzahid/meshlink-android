# MeshLink

**Free messaging and calling that works without internet.**

MeshLink is an Android messenger that talks to other phones directly over the
phone's own radios — no servers, no accounts, no data plan and no Wi-Fi router.

| Radio | Needs | Typical range |
|-------|-------|---------------|
| **Wi-Fi Direct** | nothing (phone-to-phone Wi-Fi) | 50–200 m per hop |
| **Bluetooth LE** | nothing | 10–50 m per hop |
| **Cellular radio (SMS)** | a SIM with signal | city / nationwide |

Messages hop phone → phone → phone (5 hops by default), so a chat can cross a
crowd, a building or a village where the network is down.

## What's inside

- **WhatsApp-style interface** — chat list, per-chat conversations, calls tab,
  contacts and settings.
- **Chats** — bubbles, timestamps, date separators, unread badges, message
  status (queued / sent / delivered) and tap-to-retry when a radio can't reach
  the other phone.
- **Calls** — ring, accept, mute, speaker and hang up, with a live timer.
  Voice is streamed directly between the two phones over Wi-Fi Direct.
  If the two phones are not in a Wi-Fi Direct group, the app offers a normal
  SIM call instead.
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
Messages can travel over SMS, Bluetooth LE and Wi-Fi Direct, and anyone with
the right radio equipment in range could read them. The identity key pair is
generated in the Android Keystore on the device and is never uploaded.

Transport encryption is planned; until then, treat MeshLink like a radio:
handy when networks are down, not a replacement for a secure messenger.

MeshLink collects no data at all. See [PRIVACY.md](PRIVACY.md) for the full
permission and storage breakdown.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
