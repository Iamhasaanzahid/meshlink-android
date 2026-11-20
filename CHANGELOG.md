# Changelog

## 1.27.0 — 2026-11-19 — worldwide web bridge + premium UI

### Added
- **Android ↔ web messaging bridge.** Text messages now travel between the
  Android app and meshlink web through the free MQTT relay, so the app works
  worldwide over the internet, not only nearby.
- Worldwide online mode is on by default; local Bluetooth / Wi-Fi / SMS radios
  still work automatically when there is no internet.

### Changed
- Android header now carries the MeshLink “HYBRID MESSENGER + CALLS” identity.
- AI setup is branded only as MeshLink AI; no third-party engine name is shown.
- Live email OTP now sends real 6-digit codes through Resend.

## 1.26.2 — 2026-11-19 — security hardening

### Changed
- Disabled app backup and cleartext network traffic outside explicit local dev hosts.
- Restricted the boot receiver to this app only.
- Hardened web and Worker responses: unknown URLs now return a real 404,
  additional security headers, constant-time pairing token checks, and rate
  limits on pairing endpoints.

## 1.26.1 — 2026-11-19 — manual web pairing

### Added
- **Pairing code for web login.** If scanning a QR is not convenient, open
  **Settings → Devices → Show pairing code instead**. The phone displays an
  8-digit code; type it into the web app and tap **Approve web login** on the
  phone. The code expires in 90 seconds and is single-use, exactly like the QR.

## 1.26.0 — 2026-11-19 — web-matched UI, MeshLink AI, email verification

### Added
- **Web-matched design.** The whole app now uses the exact MeshLink purple
  palette from the web app (dark/light), gradient header and all eight web
  sections: Chats, Calls, Nearby, MeshLink AI, Status, Starred, Archived and
  Settings.
- **MeshLink AI.** New AI tab with assistant chat bubbles, model picker
  (llama3.2 / phi3 / qwen2.5), editable Ollama server URL and a built-in
  offline responder, matching the web AI experience.
- **Email security verification.** Your app-generated 10-digit MeshLink ID is
  still the real identity. Email is now verified with a 6-digit OTP (hashed,
  5-minute expiry, 5 attempts) and shown as a Verified badge in Settings.

## 1.25.0 — 2026-11-19 — QR web linking

### Added
- **Link web (WhatsApp-style QR).** Settings → Devices → Scan QR to link web.
  The web app shows a one-time QR that rotates every 30 seconds; scanning it
  with MeshLink approves the session and signs the browser in as this phone.
  Only the phone's MeshLink ID and display name are sent — no phone number.

## 1.24.0 — 2026-11-19 — hybrid transport hardening

### Fixed
- **Mobile-data relay fallback.** Online mode no longer gets stuck on one
  broker. If `ssl://broker.hivemq.com:8883` is blocked by a carrier, MeshLink
  rotates through WebSocket (`wss://…:8884/mqtt`), plain WebSocket
  (`ws://…:8000/mqtt`) and TCP (`tcp://…:1883`). On cellular links it starts
  with the WebSocket/TLS route. Settings now shows the last relay error and
  has one-tap broker presets.
- **SMS fallback routing.** Phone numbers are normalized, so formatted values
  such as `+92 300 1234567` or `0300-1234567` now work. Saved contacts that
  use a phone number as their address are used as an SMS route too.
- **SMS readiness and dual SIM.** The status check now verifies SMS permission
  and the default SMS subscription instead of only checking that a phone type
  exists, and sends use the SMS-capable subscription.
- **SMS chunking.** Smaller protocol chunks prevent a MeshLink frame from
  being split again by `SmsManager`, which previously broke reassembly.

## 1.23.0 — 2026-11-19

### Added
- **Backup export.** Settings → Backup → Export backup file saves chats,
  messages (with reactions and media names) and contacts as one readable JSON
  document you choose. Nothing leaves the phone unless you share that file.

## 1.22.0 — 2026-11-19

### Added
- **Delete for everyone.** Long-press one of your own messages in a private
  chat and choose Delete for everyone. The other phone removes it too over the
  encrypted control channel. In groups the app falls back to deleting your
  local copy, because each member copy has its own ID.

## 1.21.0 — 2026-11-19

### Added
- **Group info.** Open a group chat menu to see every member, add someone by
  MeshLink ID (they get an invite instantly) or leave the group and clear its
  local history.

## 1.20.0 — 2026-11-19

### Added
- **Read receipts.** When the other person opens the chat, your double tick
  turns blue and the message info says Read. Receipts travel as a tiny
  encrypted control message, only when the chat is actually opened, and never
  downgrade an already-read message.

## 1.19.0 — 2026-11-18

### Added
- **Message reactions.** Long-press any bubble and tap ❤️, 👍, 😂, 😮, 😢 or 🙏.
  The emoji shows on the bubble on both phones, in private chats and groups,
  travelling as a tiny encrypted control message.

## 1.18.0 — 2026-11-18

### Added
- **Photos, documents and voice notes in groups.** Pick media in a group chat
  and it is streamed to every member with the same encrypted file transfer
  used in one-to-one chats. Incoming group media lands in the group chat with
  the real file name.

## 1.17.0 — 2026-11-18

### Added
- **Search inside a chat.** Tap the search icon in the chat header, type any
  word, then step through matches with Previous / Next. The matching bubble is
  highlighted and the list scrolls to it.

## 1.16.0 — 2026-11-18

### Added
- **Profile photo.** Tap your avatar in Settings (or the chat header) to pick
  a photo from the gallery. It is cropped into the avatar and kept in
  app-private storage; no upload or account is involved.
- Remove photo with one tap. Until contact photo sync ships, this picture is
  local to your phone only.

## 1.15.0 — 2026-11-18

### Added
- **Groups.** Create a group from the chat header, add members by MeshLink ID,
  and every message is relayed encrypted to each member — across countries
  when everyone has Online mode on. A distinct group chat keeps the history.
- Invites travel over the same online/mesh path; no group server or account.
- Group messages use the existing segmentation and dedupe, so long texts and
  flaky links behave like normal chats. Media in groups is not wired up yet.

## 1.14.0 — 2026-11-18

### Added
- **Video calls.** Chat menu → Video call starts an encrypted WebRTC video call
  over the internet: full-screen remote video, mirrored self-preview, mute,
  camera on/off, front/back flip and end. Audio-only calls stay one tap away.
- Camera permission is requested only when you start or accept a video call;
  without it the call continues as voice.

## 1.13.0 — 2026-11-18

### Added
- **Pin and mute chats.** Long-press a chat row: pin it to the top or mute its
  notifications. Muted chats still receive messages, they just stay quiet.
  Both choices are stored on the phone only.
- **Message info.** Long-press a bubble → Message info shows direction, exact
  time, which route carried it (Wi-Fi, Bluetooth, SMS, online), delivery
  status and file size. A reminder that the only copy lives on this phone.

## 1.12.1 — 2026-11-18

### Added
- **Typing indicator.** When the other side writes, the chat header switches to
  a mint "typing…" line for a few seconds. It travels as a one-byte control
  frame over online mode only, so Bluetooth and Wi-Fi airtime is never wasted
  on keystrokes.

## 1.12.0 — 2026-11-18

### Added
- **Long-press any message** for three actions, WhatsApp style:
  - **Reply** — the quoted snippet appears above the composer and is sent
    with your text (works over every transport, no protocol change).
  - **Forward** — pick any other chat from a list; text, photos, documents and
    voice notes all forward without re-downloading.
  - **Delete for me** — removes the message from this device only (there is no
    server to delete from).
- **Fresh visual design.** New violet/indigo gradient header, brand avatars
  with online dots, rounded nav bar, pill unread counters, redesigned empty
  states and a cleaner conversation header.

### Fixed
- **Calls on mobile data.** Added public TURN relays next to STUN, so real
  calls connect behind carrier-grade NAT instead of failing when both phones
  are on 4G/5G. Media is still DTLS-SRTP end-to-end; TURN only forwards
  sealed packets.
- **Relay reliability.** Reconnects now back off 2→15 s with jitter instead of
  hammering the public broker every 3 s (which caused throttling), and the
  backoff resets after a good connection.

## 1.11.1 — 2026-11-18

### Added
- **Voice notes.** Tap the microphone in the composer, speak, then tap stop.
  The clip is recorded as AAC (32 kbps, up to 60 s) and travels exactly like a
  photo: online in 48 KB pieces or over the local radios. Playback happens
  inside the bubble with a play/pause button — no external player.

## 1.11.0 — 2026-11-18

### Added
- **Mesh → internet bridge (country-wide offline messaging).** Every text
  message now also travels as a compact "bridge" frame that names the
  destination UIN. Any MeshLink phone that sees it and has mobile data
  forwards it to the relay, so an offline sender can reach a contact anywhere
  in the country through a nearby phone with internet. No setup, no accounts.
- **Reverse gateway.** When a phone with internet hears an offline phone on
  Bluetooth/Wi-Fi, it subscribes to that phone's relay topic and hands its
  incoming messages back over the local radios. Two-way chat works even when
  only one side has data.
- Duplicate protection now collapses copies that arrive over both the mesh
  and an internet bridge.

### Notes
- Honest limits: a bridge needs at least one phone in the chain to have
  internet at some point. With no internet anywhere, messages still travel
  only hop-by-hop (Bluetooth 10–50 m, Wi-Fi Direct 50–200 m) or by the
  optional paid SMS fallback. Phones still cannot transmit FM.
- Bridge frames enlarge the radio traffic a little (text only, never files).

## 1.10.1 — 2026-11-18

### Fixed
- File chunks and internet-call signalling now use MQTT QoS 1. A single lost
  48 KB chunk used to stall a photo forever; now the broker retries it.
- Text messages keep QoS 0 so normal chat stays instant.

## 1.10.0 — 2026-11-18

### Added
- **Photos and documents over the internet.** With online mode on, a picture
  or document is published in 48 KB pieces (about 7 messages for a phone
  photo) instead of 90-byte mesh chunks, so it reaches a contact on another
  continent in seconds. Files are still capped at 3 MB.
- **Real app-to-app internet calls.** Press the call button in a chat while
  online mode is connected: the other phone rings inside MeshLink, the call
  is accepted with one tap, and WebRTC carries encrypted audio directly
  between the two phones. No code copying. Decline, hang up and the call log
  all work like a normal messenger.
- **The call button never opens the SIM dialer.** Calls stay inside MeshLink:
  internet first, then the local Wi-Fi path. If neither exists the app says so
  instead of jumping to the phone app.
- A call is routed online only when the other phone has not been heard on a
  local radio in the last three minutes, so nearby photos still use Wi-Fi.

### Changed
- Online mode carries three extra envelope types (file metadata, file chunks,
  call signalling) inside the same AES-GCM envelope as normal frames.
- Incoming internet calls keep a 45-second ring timeout.

### Notes
- Internet calls need both users in online mode and a network that allows
  direct peer-to-peer UDP; strict CGNAT still blocks some calls (no TURN).
- Offline country-wide messaging is still physically limited: Bluetooth and
  Wi-Fi Direct are short range, phones cannot transmit FM, and SMS costs the
  operator's normal rate. Multi-hop relaying and the optional SMS fallback are
  the only no-internet ways to cross a country.

## 1.9.0 — 2026-11-17

### Added
- **Online mode (www).** Switch it on in Settings and messages can reach a
  MeshLink phone anywhere in the world over Wi-Fi or mobile data, through an
  MQTT relay. The default relay is a free public broker; the address is
  editable if you run your own. Message frames are encrypted with AES-GCM
  using a key derived from both UINs, so other listeners on the broker see
  only ciphertext. Off by default.
- **Calls over ordinary Wi-Fi.** A MeshLink call no longer needs a Wi-Fi
  Direct group: both phones on the same router or hotspot exchange voice as
  20 ms UDP packets. Call signalling now carries each phone's LAN address.
  Wi-Fi Direct calls still work when there is no shared network.
- **A permissions card that actually helps.** Settings lists every missing
  permission by name with an *Allow now* button, and the list refreshes when
  the app comes back to the foreground. A permanently denied permission has a
  direct link to Android's app settings.
- Online status is visible in Settings (connected / connecting / off).

### Changed
- The send order now prefers a radio that has actually heard the other phone
  (Wi-Fi LAN, then Wi-Fi Direct, then BLE) before falling back to the rest, so
  a nearby message does not wait on an empty radio.
- `BLUETOOTH_SCAN` is declared `neverForLocation`, which lets Android 12+
  phones scan without a location permission.
- Call ids are shortened so the invite/accept frame fits in one 96-byte mesh
  frame together with the LAN address.

### Notes
- Online mode is a convenience transport, not a replacement for a verified
  end-to-end key exchange: the key comes from the two public UINs, and the
  relay can be replaced by anyone who shares the address. Read the Security
  section before using it for sensitive chats.
- Photos and documents still travel only over local radios (Wi-Fi LAN,
  Wi-Fi Direct, BLE); online file transfer is not implemented yet.
- Calls over Wi-Fi need the network to allow phone-to-phone traffic. Public
  Wi-Fi with client isolation will refuse it, and the call screen says so.

## 1.8.0 — 2026-11-16

### Added
- **Photos and documents in chats.** The paperclip button opens a menu with
  *Photo from gallery*, *Take photo* and *Document*. Photos are rotated from
  their EXIF orientation, scaled to 1024 px and compressed to JPEG so they fit
  through the radios; documents are copied into app storage and shown as a
  card with name and size. Tap a photo for a full-screen view, tap a document
  to open it in whatever viewer the phone has.
- **A file-transfer layer for the mesh** (`FileTransfer` + two new frame
  types). Files are split into 90-byte chunks, reassembled out of order on the
  other phone, saved privately and acknowledged so the sender gets double
  ticks. Progress is shown live in the bubble ("Sending 42%").
- Files use Wi-Fi LAN, then Wi-Fi Direct, then Bluetooth LE — never SMS, and
  they are capped at 3 MB. A phone photo is usually 150–350 KB, so it travels
  in seconds over Wi-Fi and about a minute over Bluetooth.
- Eight new unit tests for chunk splitting and reassembly, including
  out-of-order and duplicate chunks.

### Notes
- Large files over Bluetooth LE are slow by nature. Wi-Fi Direct or a shared
  Wi-Fi network is the right tool for photos.
- Photo and document payloads are *not* end-to-end encrypted yet, exactly like
  text messages today (see the Security section of the README). Online calls
  are encrypted with DTLS-SRTP.

## 1.7.0 — 2026-11-16

### Added
- **Online mode: worldwide voice calls over the internet, with no server.**
  Calls → "Online call (worldwide)": the caller creates a call code, the other
  person pastes it, sends back a reply code, and the call connects directly
  between the two phones. Audio is encrypted end to end with DTLS-SRTP, which
  WebRTC enables on every call; no server sees or relays the audio.
- WebRTC (`io.github.webrtc-sdk:android`) with public STUN servers for NAT
  traversal, echo cancellation, noise suppression and automatic gain control.
- Mute, speakerphone and call timer; microphone permission is requested only
  when a call is started.
- `CallCodes` with unit tests, so a broken pairing code cannot slip through.

### Notes
- Some very strict mobile networks (CGNAT) block direct peer-to-peer audio.
  The app says so clearly instead of failing silently; switching one phone to
  Wi-Fi or a hotspot usually works. No TURN relay is included (it is not free
  at scale).
- APK size grows to about 46 MB because it now contains the WebRTC libraries.

## 1.6.0 — 2026-11-16

### Added
- **Wi-Fi LAN transport**: two phones on the same router or on one phone's
  hotspot can message each other over the local network, with no mobile data
  and no internet. It sits between Wi-Fi Direct and Bluetooth in the radio
  order and relays frames like the other transports.
- **SMS fallback is now opt-in.** It is off by default and the app never asks
  for the SMS permission on first launch. A switch in Settings requests the
  permission only when the user turns it on, which avoids Android's
  "SMS may cost you money" warning for everyone who does not need SMS.
- Incoming messages carry the sender's UIN, so replies go back to that phone
  instead of to a raw MAC/IP address on every transport.

### Fixed
- **Crash every 45 seconds** in the store-and-forward retry loop: the query
  used a `timestamp` column that does not exist (the column is `ts`). Any
  user with a chat could have the app killed in the background. Retries are
  also wrapped so a database error can never take the process down again.
- Removed `READ_SMS` and `RECEIVE_MMS` from the manifest; the app only needs
  `RECEIVE_SMS` for its own messages.

## 1.5.1 — 2026-11-16

### Fixed
- Android 7.x (API 24–25) crashed when an incoming call tried to vibrate.
  The vibration now uses the pre-Android-8 API on older devices.
- The microphone is checked before a call starts, so a denied permission ends
  the call with a message instead of a crash.
- Declare `ACCESS_COARSE_LOCATION` next to the fine permission, as Android
  requires for Wi-Fi Direct scanning on Android 12 and older.

## 1.5.0 — 2026-11-16

### Added
- **First-run privacy screen** that explains, in plain language, that MeshLink
  collects 0% of your data and why Android asks for sensitive permissions.
  All permissions are requested in one go from that screen.
- **Privacy card in Settings** showing "0% data collected", local-only storage
  and the number of missing permissions, with a shortcut to Android settings.
- `PRIVACY.md` with a permission-by-permission table.

### Changed
- On Android 13+ the app no longer asks for location; the Nearby devices
  permission replaces it for Wi-Fi Direct scanning.

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
