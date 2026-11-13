# Quietmap: notes for Claude (and humans)

Quietmap is an open-source, Life360-style location sharing app with **no accounts and no server of its own**. Phones exchange end-to-end encrypted updates through public [Nostr](https://nostr.com) relays. Android ships first; iOS reuses the shared Kotlin code.

This repo is **public**. Never commit secrets, real keys, keystores, personal emails or real location data. Commit as the GitHub no-reply address.

## Ground rules

These are product decisions, not style preferences. Don't weaken them without the owner (carterkeel) agreeing.

- **No backend.** Nothing the app needs may depend on a server we run. Public relays, OpenFreeMap tiles and a static page for invite links are the only outside services.
- **Never hand-roll crypto.** Primitives come from audited libraries: secp256k1-kmp, javax.crypto and BouncyCastle on Android, CommonCrypto and libsodium on iOS. NIP-44 and NIP-01 are implemented to spec and checked against the spec's test vectors in `CryptoTest`.
- **Relays must learn as little as possible.**
  - Location events are padded to one fixed size (`Envelopes.LOCATION_SIZE`, `Engine.MAX_LOCATION_CONTENT`).
  - They're signed with a per-hour publisher key and use per-hour `d` tags derived from the circle key.
  - Updates go out at only two steady rates (~60 s moving, ~300 s still, ±15% jitter), plus one extra update when movement starts or stops.
  - Activity, speed, battery, names and photos travel only inside the encryption.
- **Never log locations, keys or message contents.** `Log.d("Quietmap", …)` is for relay status only.
- **Keys stay on the device.** State is encrypted with an Android Keystore key and excluded from backups (`data_extraction_rules.xml`, `allowBackup=false`).
- **Consent and anti-stalkerware.** Joining needs an explicit tap, the admin approves every join, and Android shows a persistent "Sharing with N people" notification. Keep all of these.

## Layout

```
shared/      Kotlin Multiplatform core. No UI. Android + iOS targets.
  core/Engine.kt            The whole protocol: state, circles, invites, key rotation, profiles,
                            publishing, handling incoming events, relay subscriptions.
  core/MotionPolicy.kt      When to publish and which activity to report (still/walking/...).
  core/model/Models.kt      AppState (everything persisted) and data classes.
  core/protocol/Protocol.kt Message bodies, sealed DMs, circle crypto (tags, publisher keys),
                            profiles, device codes, invite links.
  core/nostr/               NIP-01 events, relay protocol, RelayPool (Ktor websockets).
  core/crypto/              PlatformCrypto (expect/actual), Keys (secp256k1), Nip44, encoding.
  commonTest/               Crypto, protocol and motion tests (run on the JVM in CI).

ui/          Compose Multiplatform screens shared by Android and iOS.
  App.kt          QuietmapScreens(): navigation. Home is always composed; other screens
                  slide over it (AnimatedContent), so the map never reloads.
  HomeScreen.kt   Map + draggable people sheet (BottomSheetScaffold), circle picker, welcome.
  DetailScreens.kt Profile, EditProfile (photo/colour avatar), Add (QR invite), Join, Settings.
  Avatars.kt      Avatar colours, photo decoding cache, verified badge.
  Components.kt   Signal-style rows, buttons, top bar, custom icons (AppIcons).
  Theme.kt        Colours, type scale (system font), shapes.
  MapPane.kt      maplibre-compose map with OpenFreeMap tiles.
  Platform.kt     PlatformBridge (toast, clipboard, share, photo picker, QR scanner) + expects.

androidApp/  Android host app.
  QuietmapApp.kt     Application: creates the Engine, notification channel.
  MainActivity.kt    Permissions, photo picker and QR scanner launchers, hosts QuietmapScreens.
  AndroidPlatform.kt PlatformBridge implementation.
  LocationService.kt Foreground service: GPS/network location, significant-motion sensor,
                     publish loop driven by MotionPolicy.
  SecureStore.kt     Keystore-encrypted state file.
  PhotoProcessor.kt  Crops/compresses profile photos to a small JPEG.
  BootReceiver.kt    Resumes sharing after reboot or update.
```

The iOS platform layer lives in `ui/src/iosMain/.../ios/` (Keychain state store, CoreLocation service, photo picker, `PlatformBridge`). The Xcode app shell is in progress (#13, PR #19).

## How it works

**Identity.** On first launch the phone generates a secp256k1 keypair (`KeyPair.generate`). The **device code** is the first 8 bytes of SHA-256(pubkey), shown as `7F3A 91C2 D04E 6B1A` (`DeviceCode`). People verify each other by comparing codes; verified contacts get a green check on their avatar.

**Circles.** A circle is an id, a 32-byte circle key, an epoch number, an admin pubkey, a member list and a list of managers.
- The **admin** invites, approves, removes (which rotates the key), chooses managers, hands the admin role to someone else (`transferAdmin`) and can delete the circle (`deleteCircle`, a `RemovedBody` with `deleted = true` to everyone).
- **Managers** can invite and approve. Their rosters are accepted only under the current key and only add people.
- Rosters under the same key are **merged**, so an admin and a manager approving at the same moment don't drop anyone. Only the admin's rosters can change the key, the admin or the managers.
- **Leaving** sends a `LeftBody` to the admin, whose app removes the member automatically (new key). An admin has to hand over the role or delete the circle before leaving.
- Only one person rotates keys, which avoids conflicting epochs. Moving to MLS later would remove that limit.

**Invites.**
- `Engine.createInvite` makes a single-use secret that expires in 24 h.
- The link is `quietmap://join#<base64url JSON>`. The payload is after `#`, so web hosts never see it.
- The same link is shown as a QR code (qrose) and can be scanned in-app (zxing-android-embedded).
- Flow: the joiner sends `JoinRequestBody` as a sealed DM, the admin approves, the admin sends `RosterBody` (circle key and members) to every member, and the new member publishes their profile.

**Sealed DMs** (kind 1059). Signed by a throwaway key, `p`-tagged to the recipient, and NIP-44 encrypted to them. Inside is an `Envelope` whose body is signed by the sender's real key (`Envelopes.open` verifies it). They're used for join requests, rosters and removals.

**Location updates** (kind 30078, addressable).
- Content is NIP-44 encrypted under a conversation key derived from the circle key and epoch.
- The `d` tag is `HMAC(circleKey, "qm-tag|epoch|hour")`.
- The signer is `HMAC(circleKey, "qm-pub|member|hour")`, so relays keep only the newest update per member per hour and can't link hours together.
- Receivers check that the outer signer matches the derived key for the claimed sender (`handleCircleUpdate`).
- Each update carries up to 6 recent points for path replay. The oldest points are dropped if needed to keep the fixed size.

**Profiles** (kind 30078). `ProfileBody` holds the name, avatar colour index and an optional photo (base64 JPEG, at most `CircleCrypto.MAX_PHOTO_CHARS`). It uses one tag and one publisher key per circle epoch, and is republished when the profile changes, when circle membership changes, and daily.

**Backups.** `Backups` (in `protocol/Backup.kt`) exports identity, profile, settings and circles as a text file: PBKDF2-HMAC-SHA256 (600k iterations, 16-byte salt) turns the passphrase into a key for NIP-44. Restoring replaces the identity and starts with sharing paused. Others' locations and photos aren't included; they come back from relays.

**Removing someone.** The admin makes a new key (epoch + 1) and sends it only to remaining members. The old key stays accepted for 10 minutes (`OVERLAP_SECONDS`), and the removed person gets a `RemovedBody`.

**Motion** (`MotionPolicy`).
- Speed comes only from fixes accurate to 30 m: the median of reported GPS speeds over 30 s, otherwise distance over time. Coarse network fixes count as movement only for a clear jump.
- Classification uses 15% hysteresis.
- The Android significant-motion sensor sets a "motion hint" that switches to GPS straight away.
- `shouldPublish` publishes on the steady rate, or on a still↔moving transition at least 20 s after the last publish.
- On the receiving side, `Format.status` shows a stale moving update as "Was driving".

**Offline.** No update for 45 min (`Engine.OFFLINE_AFTER_SECONDS`) shows the person as offline. The app can't tell a dead phone from no signal, by design.

**First run.** `SetupScreen` walks through name, photo (optional), creating or joining a circle, and location access, then sets `AppState.setupDone`. Installs that already had circles skip it. The app reopens on `AppState.lastCircleId`.

## Building and testing

Requires JDK 17 and the Android SDK.

```
./gradlew :shared:testAndroidHostTest        # crypto, protocol and motion tests
./gradlew :androidApp:assembleDebug          # debug APK (slow Compose: don't judge smoothness on it)
./gradlew :androidApp:assembleStaging        # R8-optimised, debug-signed: judge performance here
```

**CI** (`.github/workflows/android.yml`) runs the tests and both APK builds on every push. It uploads `quietmap-debug-apk` and `quietmap-staging-apk`, and writes a plain-text summary of each run to the **`ci-logs` branch** (`log.txt`, force-pushed, latest run only). Claude sessions without Maven access read build errors from that branch:

```
gh api repos/carterkeel/quietmap/commits/ci-logs --jq .commit.message
gh api repos/carterkeel/quietmap/contents/log.txt?ref=ci-logs --jq .content | base64 -d
```

Two-phone manual testing is described in `TESTING.md`.

## Toolchain gotchas

- **AGP 9 with its current setup, no compatibility switches.** The app module uses AGP's built-in Kotlin (no `org.jetbrains.kotlin.android`); `shared` and `ui` use `com.android.kotlin.multiplatform.library`, configured in `kotlin { android { … } }`. Host (JVM) tests live in the `androidHostTest` source set and run with `:shared:testAndroidHostTest`. compileSdk is 37 (required by Compose Multiplatform 1.12 and lifecycle 2.11).
- **No deprecated libraries.** The Material icons library is frozen, so icons are drawn in `AppIcons` (Components.kt); add new ones there. GitHub Actions use their Node 24 releases.
- **Kotlin 2.4.x.** Dependencies built with a newer Kotlin may not be readable. See the libsodium comment in `libs.versions.toml`.
- **CI runs steps with `shell: bash`** so that `| tee` doesn't hide a failing Gradle command (pipefail). Keep it.
- **iOS targets can't build on Linux.** `kotlin.native.ignoreDisabledTargets=true` silences that. iOS `actual`s (`*.ios.kt`) are only compiled on macOS, so double-check them by hand.
- **CI test builds share one signing key** (cached in GitHub Actions as `quietmap-ci-signing-v1`, never committed) and use the run number as `versionCode`, so a new APK installs over the previous one. Caches are per branch (branches can read `main`'s), so the first build on a new branch may get its own key until `main` has one.
- **The `staging` build uses R8.** Anything reached through JNI or reflection (secp256k1, MapLibre, kotlinx.serialization serializers, the zxing scanner) needs a rule in `androidApp/proguard-rules.pro`.
- **Compose is much slower in debug builds.** Judge smoothness on the staging APK.
- **maplibre-compose needs a renderer runtime on Android.** It only ships the JNI bridge; `libmaplibre-native-c.so` comes from `org.maplibre.nativeffi:maplibre-native-ffi-runtime-opengl` (in `ui` androidMain). Its version must match the `maplibre-native-ffi` version maplibre-compose pulls in. Without it the app crashes as soon as the map opens.
- **CI runs a UI smoke test on an emulator** (`androidApp/src/androidTest`, `ci/emulator-test.sh`): go through setup, create a circle, let the map and sharing service run, toggle sharing, open the invite screen and settings. Crash stack traces from logcat land in the `ci-logs` summary. Extend this test when you add a screen.

## Conventions

- Branch from the branch you're changing, open a PR, and keep `main` releasable. `ios-app` holds the multiplatform work in progress.
- UI changes go in `ui/` (shared), not `androidApp/`, so iOS gets them too. Android-only APIs belong behind `PlatformBridge` or an `expect`/`actual`.
- New persisted fields go in `AppState` **with defaults**, so older saved state still loads.
- New protocol messages: add a `Body` subclass with a `@SerialName`, keep old ones decodable, and add a round-trip test in `ProtocolTest`.
- User-facing text: plain and short, and say what happens ("Stop sharing with everyone", not "Disable").
- Look: Signal-like. White or near-black surfaces, one ultramarine accent, the system font, pill buttons, 24 dp gutters, and settings rows with leading icons. Use spring-based animations, nothing bouncy.
