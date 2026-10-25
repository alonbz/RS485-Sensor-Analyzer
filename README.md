# RS485 Protocol Explorer for Android

Android USB Host diagnostic app for CH340/CH341 and other adapters supported by usb-serial-for-android.

## Build
Import this directory in Android Studio (JDK 17, Android SDK 35), sync Gradle, then Build > Build APK(s). APK will be at `app/build/outputs/apk/debug/app-debug.apk`. Alternatively run `gradle assembleDebug` with a compatible Gradle 8.9+ installation and Android SDK.

## Use
1. Power the sensor **separately with its verified rated voltage**; RS485 USB adapters normally do not power it.
2. Connect RS485 A/B, plug the adapter into a USB-OTG capable Android phone.
3. Tap CONNECT USB and approve USB access.
4. Select baud/parity and tap APPLY SERIAL SETTINGS.
5. Tap START RAW MONITOR to listen for unsolicited bytes. Silence is normal for request-response devices.
6. Stop monitoring, enter a known read-only HEX command, tap SEND HEX + LISTEN. Share log for analysis.

## Limitations / safety
- This is a **diagnostic tool**, not an automatic protocol cracker. Without vendor protocol documentation or captured traffic from the original TC56 controller, unknown proprietary commands cannot be reliably inferred.
- The SEND HEX feature can transmit arbitrary bytes, including device-changing commands. Use only known read-only commands.
- USB connected means the adapter is open, **not** that the sensor is responding.
- This project has not been compiled or tested on the user's specific phone or hardware in this environment.
