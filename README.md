# BLE Connect

A minimal native Android app for discovering nearby Bluetooth Low Energy devices,
connecting to a selected device over GATT, and displaying connection state. It
does not assume any device-specific services, characteristics, or data protocol.

Open the repository in Android Studio and run the `app` configuration on an
Android device with BLE hardware. The app requests location permission when
scanning on Android 6–11 and nearby-device scan/connect permissions on Android
12 and later. Bluetooth must be enabled.

The first milestone is connection status only; testing actual discovery and
connections requires a BLE-capable Android device and a nearby BLE peripheral.
