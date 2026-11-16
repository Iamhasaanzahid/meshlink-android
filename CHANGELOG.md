# Changelog

## 1.4.0 — 2026-11-16

### Added
- **SMS reach across the country**: the app can now share an optional phone
  number with people you chat with. If a message cannot travel over Wi-Fi
  Direct or Bluetooth LE, it falls back to SMS to that number, so two users
  can keep messaging anywhere Pakistan has mobile signal.
- "My phone number" field in Settings; leave it empty and nothing is shared.
- The receiving phone saves the number, so replies also work over SMS.

### Changed
- The chat database is now version 2, adding a `phone` column. The upgrade is
  automatic and keeps existing chats.

## 1.3.0 — 2026-11-16

### Added
- **Relay hops setting** (1–20 phones) so users can trade delivery speed for
  reach. The default is now 8 hops instead of 5.
- **Coverage card** in Settings that shows the real per-radio range
  (Bluetooth LE, Wi-Fi Direct, SMS) and explains why long distances need a
  chain of phones or the SMS radio.
- **Store-and-forward retries**: messages that could not be delivered are
  retried automatically every 45 seconds over Wi-Fi Direct and Bluetooth LE,
  so a message keeps travelling as the phone moves between people. Automatic
  retries never use SMS, so they cannot cost money.

### Changed
- Release builds can be signed locally with `KEYSTORE_FILE` and friends; the
  published APK is now signed, not a debug build.

## 1.2.0 — 2026-11-15

### Added
- **WhatsApp-style interface**: chat list with avatars, last message, time and
  unread badges; per-conversation screens with bubbles, date separators and
  read-style status; Calls, Contacts and Settings tabs.
- **Persistent storage**: chats, messages, call history and contacts are kept
  in a local SQLite database and survive app restarts.
- **Real in-app calls**: ring, accept, decline, mute, speaker and hang up with
  a live timer. Audio is streamed phone-to-phone over Wi-Fi Direct at 8 kHz.
- **Call log** with incoming / outgoing / missed entries and duration.
- **Delivery receipts**: the receiving phone acknowledges each message, so the
  sender sees single or double ticks. Failed messages can be retried by tap.
- **Long messages** are split into radio-sized frames and reassembled.
- **Contacts** with one-tap chat and call.
- **Background notifications** for new messages and incoming calls.
- **Fully offline identity**: the ID is generated on the device, no server.

### Changed
- The app is now **offline-only by design**. The internet relay was removed;
  messages use Wi-Fi Direct, then Bluetooth LE, then SMS.
- Signup no longer needs email or a network connection.
- New home with four tabs (Chats / Calls / Contacts / Settings).

### Fixed
- Messages longer than one frame are no longer dropped.
- Duplicate frames are ignored, so relays do not create duplicate chats.

## 1.1.0 — 2026-11-14

### Added
- Full in-app **Chat screen** with message history, delivery state and a
  per-message transport picker (Auto / SIM / Bluetooth / Wi-Fi Direct)
- **Call screen**: call over the SIM (normal cellular voice) and free local
  **push-to-talk voice over Wi-Fi Direct** (8 kHz PCM, hold-to-talk)
- **Radios screen**: live status of all three radios, nearby Wi-Fi Direct
  peers, one-tap connect and the current mesh node id
- Incoming messages now appear in the UI (SMS radio text + BLE/Wi-Fi frames)
- Wi-Fi Direct transport rewritten with persistent sockets, automatic group
  formation and peer auto-connect
- Offline signup: if the backend is unreachable, a local `OFF-XXXXXX` UIN is
  created so radio messaging still works with no internet
- Session persistence: UIN is remembered, service reconnects on launch and
  after reboot; explicit Logout
- Runtime permissions now include Phone and Microphone
- BLE MTU negotiation for larger mesh frames

### Fixed
- AUTO routing no longer tries to SMS a UIN (only real phone numbers)
- Large payloads/frames are delivered to the UI instead of being dropped
- Signup network call moved off the main thread
- Logout no longer stops the mesh engine for the next session

## 1.0.0 — 2026-11-13

First public release.

### Added
- Email-only signup with auto-assigned UIN (`+92-MZ-NNNNNN`)
- End-to-end encryption: X25519 ECDH + HKDF-SHA256 + AES-256-GCM
- **Radio transport (cellular/SMS)** — real phone radio, works with no internet
- **Bluetooth LE transport** — local 2.4 GHz mesh with multi-hop TTL
- **Wi-Fi Direct transport** — higher-throughput local mesh
- Mesh relay with deduplication and 5-hop TTL forwarding
- Encrypted media upload (images, voice notes)
- OTP verification flow
- Optional internet relay (FastAPI + SQLite backend)
- Purple Material 3 UI
- Foreground mesh service + boot restart
- Free hosting (Render/Railway/Fly/Oracle) and free publishing (F-Droid/APK) docs

### Security
- Server stores only ciphertext
- Message payloads chunked and encrypted before leaving the device
- No ads, no trackers, no analytics
