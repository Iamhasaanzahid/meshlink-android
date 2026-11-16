# MeshLink privacy policy

MeshLink collects **0% of your data**. There is no account, no server and no
analytics. Nothing you write, call or save is uploaded anywhere.

## What MeshLink stores

| Data | Where it lives | Leaves the phone? |
|---|---|---|
| Messages | local SQLite database | only inside the message frame you send, to the person you send it to |
| Calls (log and audio) | local database; audio is live only | never |
| Contacts | local database | never |
| Your ID (`OFF-XXXXXX`) | local preferences | included in the messages you send so replies can find you |
| Phone number (optional) | local preferences | only inside messages you send, and only if you typed it in Settings |

Deleting a chat or call log deletes it from the phone. Uninstalling the app
deletes the whole database.

## Permissions and why they exist

| Permission | Purpose |
|---|---|
| Send / receive SMS | **optional, off by default**: delivers a message over your SIM only after you enable SMS fallback in Settings |
| Microphone | live audio during a MeshLink call only; nothing is recorded |
| Phone | places a normal SIM call when a MeshLink call cannot connect |
| Bluetooth scan / connect / advertise | finds and connects nearby MeshLink phones |
| Wi-Fi / network state | sends messages over the local Wi-Fi network, never over the internet |
| Nearby devices (Android 13+) / Location (Android 12 and older) | required by Android to scan for Wi-Fi Direct peers; the location itself is never read or stored |
| Notifications | shows incoming messages and calls |
| Vibration | rings for incoming calls |

## What MeshLink never does

- no account, e-mail or phone verification
- no internet server, no cloud backup, no sync
- no advertising, tracking or analytics
- no reading your files, photos or contact list
- no uploading of chats, calls or contacts

## Your phone number is not used by the free radios

Bluetooth LE, Wi-Fi Direct and Wi-Fi LAN send only your MeshLink ID
(`OFF-XXXXXX`). Your SIM number is **not** included in those messages, so two
phones on the same Wi-Fi or within Bluetooth range can talk without either
side knowing the other's number. A number is shared only if you type one into
Settings.

## About Android's SMS warning

Android shows *"SMS may cost you money"* whenever an app asks for the SMS
permission. That warning is correct — an SMS is charged at your operator's
normal rate. MeshLink therefore:

- keeps SMS **off by default** and asks for the permission only when you flip
  the switch in Settings,
- never uses SMS for automatic retries,
- works fully over Bluetooth, Wi-Fi Direct and Wi-Fi LAN without the SMS
  permission at all.

There is no way to send an SMS without the network knowing the SIM it came
from, and no way to send free unlimited messages over a mobile network
without either mobile data or a paid SMS bundle. MeshLink does not pretend
otherwise.

## Relaying

A message can travel through other phones that run MeshLink and are in radio
range (multi-hop relay). Those phones forward an opaque frame; they do not get
a copy of the message in their chat list. The transport is not encrypted yet,
so treat MeshLink like a radio: useful when networks are down, not a
replacement for a secure messenger.

## Contact

Questions or reports: <https://github.com/Iamhasaanzahid/meshlink-android/issues>

_Last updated: 2026-11-16_
