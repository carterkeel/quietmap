# Quietmap

Private location sharing with the people you choose. No accounts, no phone numbers, and no Quietmap server.

- **Identity is a key on your phone.** On first launch the app creates a secp256k1 keypair. Your *device code* (for example `7F3A 91C2 D04E 6B1A`) is a fingerprint of the public key; compare it in person to verify someone.
- **Circles share both ways**, like Life360. The circle admin sends a single-use invite link that expires in 24 hours, and approves each person who opens it.
- **Everything is end-to-end encrypted** on the device before it leaves. Updates travel through public [Nostr](https://nostr.com) relays, which only ever see ciphertext.
- **Open source.** Anyone can check what the app sends.

Status: early development. Android first; iOS will reuse the shared Kotlin Multiplatform core.

## How it works

| Piece | What it does |
|---|---|
| Location updates | Encrypted once per circle (NIP-44 v2 with a circle key), padded to a fixed size, and published as a replaceable Nostr event. Relays keep only the newest one. |
| Unlinkability | The signing key and the event tag both change every hour and are derived from the circle key, so relays can't tie a member's updates together across hours. Each update is also signed inside the encryption by the sender's real key, so circle members know who sent it. |
| Send rhythm | Two fixed rates with jitter: about every minute while moving, about every five minutes while still. Relays can tell at most whether someone is moving. Walking, driving and speed are inside the encryption. |
| Invites and approvals | Sealed one-to-one messages (kind 1059) signed by a throwaway key and encrypted to the recipient. |
| Removing someone | The admin rotates the circle key and sends it only to remaining members. |
| Storage | App state, including your private key, is encrypted with an Android Keystore key and excluded from backups. |
| Map | MapLibre (via maplibre-compose, on Android and iOS) with OpenFreeMap tiles. The tile server sees roughly which area you're viewing, never anyone's position. |

## Project layout

```
shared/       Kotlin Multiplatform core: crypto, Nostr, circles, invites, motion policy (+ unit tests)
ui/           Compose Multiplatform screens and map, shared by Android and iOS
androidApp/   Android app: foreground location service, Keystore storage, permissions
```

## Building

Requires JDK 17 and the Android SDK (Android Studio installs both).

```
./gradlew :shared:testDebugUnitTest      # crypto and protocol tests
./gradlew :androidApp:assembleDebug      # debug APK in androidApp/build/outputs/apk/debug/
```

Every push also builds on GitHub Actions; the debug APK is attached to each run as `quietmap-debug-apk`.

See [TESTING.md](TESTING.md) for how to test on real phones.
