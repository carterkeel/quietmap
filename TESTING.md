# Testing Quietmap

Sections 1–7 cover Android. iOS is in [section 8](#8-ios); the two-phone test works across platforms, so an iPhone and an Android phone can test each other.

## 1. Get a build

**Easiest:** open the repo's **Actions** tab, pick the latest green run, and download an artifact (you need to be signed in to GitHub). Unzip it to get the `.apk`.

- **`quietmap-staging-apk`**: optimised like a release build. Use this one to judge smoothness and battery.
- **`quietmap-debug-apk`**: debuggable and noticeably slower (Compose runs unoptimised). Use it for `adb` debugging.

**From source:** install [Android Studio](https://developer.android.com/studio), open the repo folder, and press Run. Android Studio installs the JDK and SDK for you.

The debug build installs as **app.quietmap.debug** and the staging build as **app.quietmap.staging**, so they can sit side by side and won't clash with a future release build.

## 2. Install on a phone

1. Copy the APK to the phone (Google Drive, a USB cable, or email it to yourself).
2. Tap it. Android asks you to allow installs from that app once.
3. Or, with USB debugging on: `adb install -r quietmap-debug.apk`.

## 3. The core test: two phones

Location sharing needs at least two devices. Two real phones are best; one phone plus an emulator also works.

1. **Phone A:** enter a name, tap **Create a circle**, allow location (choose *While using the app*), and allow notifications. You should see a "Sharing your location" notification.
2. **Phone A:** tap **Invite**, then **Create invite**. A QR code appears, along with **Share link**.
3. **Phone B:** in person, tap **I have an invite → Scan QR code** and point it at phone A. Remotely, tap the shared link, or paste it into **Paste an invite link**. Check the inviter's device code matches phone A's screen, enter a name, and tap **Join and share my location**.
4. **Phone A:** a banner says B wants to join. Tap **Review** and compare the device code with phone B, then **Approve**.
5. Within a minute or two, both phones should show each other on the map and in the list.

Things to check after that:

| Test | Expected |
|---|---|
| Walk or drive with phone B | Phone A shows "Walking" or "Driving · NN mph", updating about every minute |
| Leave phone B still for 10 minutes | Updates slow to about every 5 minutes; status shows "Still" |
| Turn phone B off for an hour | Phone A shows "Offline · last seen 1 h ago" after 45 minutes |
| Phone A removes B (profile → Remove) | B's circle disappears; A no longer receives B's updates |
| Reuse an invite link | The second person's request is ignored (single use) |
| Restart phone B | Sharing resumes if "Allow all the time" was granted |
| Settings → Relays | Green dots for connected relays; removing one still works |

## 4. Faking movement

- **Emulator:** in the emulator's **⋮ → Location** panel, load a GPX/KML route and press Play to simulate driving.
- **Real phone:** enable Developer options, install a mock-location app, and choose it under *Select mock location app*.

## 5. Watching what happens

With the phone connected over USB:

```
adb logcat -s Quietmap
```

This shows relay connections, rejections, and notices. It never logs locations or keys.

## 6. Battery and background reliability

- Test on at least one **Samsung or Xiaomi** phone, not just a Pixel. Those brands stop background apps most aggressively.
- In Quietmap's **Settings**, use **Battery optimization → Fix** and **Allow all the time → Open**, then leave the phone in a pocket for a few hours and check whether updates kept arriving.
- Android's **Settings → Battery → Battery usage** shows how much Quietmap used.

## 7. Automated tests

```
./gradlew :shared:testDebugUnitTest
```

These cover NIP-44 encryption (including the spec's test vectors), event signing, sealed messages, circle key rotation, fixed-size updates, invite links and the motion policy. They run on every push in GitHub Actions.

## 8. iOS

You need a Mac with Xcode. The iOS app lives in `iosApp/`; it hosts the same shared screens and protocol as Android.

### Run in the simulator

1. Install Xcode and JDK 17 (`brew install openjdk@17`). Xcode's build step finds JDK 17 by itself.
2. Open `iosApp/iosApp.xcodeproj`, pick an iPhone simulator, and press **Run**. The first build takes a few minutes because Gradle builds the Kotlin code.
3. Give the simulator a location: **Features → Location** in the Simulator app, or from a terminal:

```
xcrun simctl location booted set 30.2672,-97.7431
```

From a terminal instead of Xcode:

```
cd iosApp
xcodebuild -project iosApp.xcodeproj -scheme Quietmap -destination 'generic/platform=iOS Simulator' -derivedDataPath build/dd build
xcrun simctl install booted build/dd/Build/Products/Debug-iphonesimulator/Quietmap.app
xcrun simctl launch booted app.quietmap
```

Only `iosApp/project.yml` is edited by hand. After changing it, run `xcodegen` in `iosApp/` (`brew install xcodegen`) and commit the regenerated project too.

### Install on your own iPhone

A free Apple account is enough for your own phones:

1. Connect the iPhone by USB. On the phone, turn on **Settings → Privacy & Security → Developer Mode** (it restarts).
2. In Xcode, select the **Quietmap** target → **Signing & Capabilities**, choose your personal team, and if Xcode says the bundle identifier is taken, change it to something unique such as `app.quietmap.yourname`. Don't commit that change.
3. Pick the phone as the run destination and press **Run**. The first time, trust your developer certificate on the phone under **Settings → General → VPN & Device Management**.

With a free account the app stops opening after **7 days**; press Run again to renew it. Giving builds to other people needs TestFlight, which needs the paid Apple Developer Program.

### How iOS differs in the two-phone test

The steps in [section 3](#3-the-core-test-two-phones) are the same, with these differences:

| On iOS | What to expect |
|---|---|
| Location permission | Asks for **While Using the App** first. Once sharing starts, iOS offers **Change to Always Allow**: choose it, or sharing stops when the app is in the background. |
| Settings → Sharing | Shows **Allow all the time** with **Open** until Always is granted. |
| While sharing | The blue location indicator in the status bar takes the place of Android's "Sharing your location" notification. |
| Scanning an invite | There is no in-app scanner yet. Scan the QR code with the **Camera app**; it opens the invite in Quietmap. Shared `quietmap://` links open the app the same way. |
| Profile photo | Uses the system photo picker; no photo library permission is needed. |

Things to check on iOS in particular:

| Test | Expected |
|---|---|
| Swipe Quietmap away in the app switcher, then move about 500 m | iOS relaunches it in the background and updates resume (needs Always) |
| Restart the iPhone and unlock it, then move | Same as above. Before the first unlock nothing is sent, because saved state can't be read yet |
| Leave it still for 10 minutes | Updates slow to about every 5 minutes, as on Android |

### Faking movement

- **Simulator:** **Features → Location → City Run** or **Freeway Drive** for continuous movement, or **Custom Location** for a fixed point.
- **Real iPhone:** while it runs from Xcode, use **Debug → Simulate Location**, or add a GPX file to the scheme.

### Watching what happens

Open the **Console** app on the Mac, select the phone or simulator, and search for `Quietmap:`. Or for the simulator:

```
xcrun simctl spawn booted log stream --predicate 'eventMessage BEGINSWITH "Quietmap:"'
```

As on Android, this shows relay status only, never locations or keys.

### Automated tests on iOS

```
./gradlew :shared:iosSimulatorArm64Test :ui:iosSimulatorArm64Test
```

These run the shared crypto, protocol and motion tests on iOS, plus the iOS state store tests.
