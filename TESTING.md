# Testing Quietmap

## 1. Get a build

**Easiest:** open the repo's **Actions** tab, pick the latest green run, and download the `quietmap-debug-apk` artifact (you need to be signed in to GitHub). Unzip it to get the `.apk`.

**From source:** install [Android Studio](https://developer.android.com/studio), open the repo folder, and press Run. Android Studio installs the JDK and SDK for you.

The debug build installs as **app.quietmap.debug**, so it won't clash with a future release build.

## 2. Install on a phone

1. Copy the APK to the phone (Google Drive, a USB cable, or email it to yourself).
2. Tap it. Android asks you to allow installs from that app once.
3. Or, with USB debugging on: `adb install -r quietmap-debug.apk`.

## 3. The core test: two phones

Location sharing needs at least two devices. Two real phones are best; one phone plus an emulator also works.

1. **Phone A:** enter a name, tap **Create a circle**, allow location (choose *While using the app*), and allow notifications. You should see a "Sharing your location" notification.
2. **Phone A:** tap **+**, then **Create invite link**, then **Share**. Send it to phone B (Signal, email, anything).
3. **Phone B:** tap the link. If the chat app doesn't make it clickable, copy it, open Quietmap, and use **Paste an invite link**. Check the inviter's device code matches phone A's screen, enter a name, and tap **Join and share my location**.
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
