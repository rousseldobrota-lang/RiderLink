# RiderLink — Android Bluetooth intercom prototype

This project builds an Android APK for a two-phone voice link over Bluetooth Classic RFCOMM.

## Get an APK without installing Android Studio

A GitHub Actions workflow is included to build the APK in the cloud.

1. Sign in to GitHub and create a **new public or private repository** named `RiderLink`.
2. Upload the contents of this ZIP into the repository (including the hidden `.github` folder).
3. Open the repository's **Actions** tab.
4. Select **Build RiderLink APK** and press **Run workflow** (or wait for the first push build).
5. When the run completes, open it and download the `RiderLink-debug-apk` artifact.
6. Extract the downloaded artifact ZIP. It contains `app-debug.apk`. Transfer it to each Android phone and install it. Android may ask you to allow installation from that source.

The cloud workflow uses Java 17 and Gradle 8.9; it does not require Android Studio on your computer.

## Pair and use
1. Pair the two Android phones with each other in Android Settings → Bluetooth.
2. Pair each phone with its rider's helmet headset, if supported.
3. Open RiderLink on both phones and grant Microphone and Nearby devices permissions.
4. Rider A taps **CREATE INTERCOM**.
5. Rider B selects Rider A in the paired-device list and taps **CONNECT**.
6. Keep the app open on both phones. Tap **DISCONNECT** to end the link.

## Prototype limitations and safety
- This is not a road-tested motorcycle product. Bluetooth performance, microphone routing, echo, and headset compatibility vary by phone and helmet.
- It uses Bluetooth Classic data sockets between the phones, not a helmet manufacturer's proprietary mesh/intercom protocol.
- It currently runs in the foreground; it is not yet a persistent foreground-service implementation.
- Test while stationary, at low volume, with a helper before any ride. Do not operate or troubleshoot the phone while riding.
- A production version needs device testing, audio-route controls, reconnection handling, link-quality monitoring, battery optimization, and a safe one-touch disconnect.
- Both phones must remain within Bluetooth range.
