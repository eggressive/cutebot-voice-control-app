# Cutebot Voice Control (Android app)

Native Android app (Kotlin) that listens for voice commands and drives the
ELECFREAKS Cutebot Pro robot car over Bluetooth Low Energy.

## What it does

```
[voice] -> [SpeechRecognizer] -> [keyword match] -> [BluetoothGatt write] -> [micro:bit BLE UART] -> [motors]
```

The phone does the speech recognition. The micro:bit (running the companion
MakeCode firmware) only maps incoming bytes to motor commands.

## Command map

| Voice (EN) | Voice (NL) | Byte |
|---|---|---|
| forward | vooruit | `1` |
| left | links | `2` |
| right | rechts | `3` |
| stop | halt | `4` |

## Companion firmware

The micro:bit half lives in a separate repo (`cutebot-voice-control-firmware`),
built with MakeCode. It advertises the Nordic UART Service (NUS) and maps the
command bytes above to Cutebot Pro motor commands over I2C (address 0x10).

NUS UUIDs:

- Service: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
- TX (phone -> micro:bit): `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
- RX (micro:bit -> phone): `6e400003-b5a3-f393-e0a9-e50e24dcca9e`

## Build

Requires JDK 17 and the Android SDK (platform 34, build-tools 34).

```bash
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

## Permissions

- `RECORD_AUDIO` (speech recognition)
- `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (Android 12+)
- `BLUETOOTH` / `BLUETOOTH_ADMIN` (Android 11 and below)
