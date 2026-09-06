# Cutebot Voice Control (Android app)

Native Android app (Kotlin) that listens for voice commands and drives the
ELECFREAKS Cutebot Pro robot car over Bluetooth Low Energy.

## What it does

```
[voice] -> [Vosk (offline)] -> [keyword match] -> [BluetoothGatt write] -> [micro:bit BLE UART] -> [motors]
```

The phone does the speech recognition (offline, via Vosk). The micro:bit (running
the companion MakeCode firmware) only maps incoming bytes to motor commands.

## Command map

| Voice (EN) | Voice (NL) | Byte |
|---|---|---|
| forward | vooruit | `1` |
| left | links | `2` |
| right | rechts | `3` |
| stop | halt | `4` |
| back | achteruit | `5` |

A physical **STOP** button sends `4` instantly. A **language toggle** switches
between Dutch (default) and English.

## Companion firmware

The micro:bit half lives in a separate repo:
[`eggressive/cutebot-voice-control-firmware`](https://github.com/eggressive/cutebot-voice-control-firmware),
built with MakeCode. It advertises the Nordic UART Service (NUS) and maps the
command bytes above to Cutebot Pro motor commands over I2C (address 0x10).

The base car project (MicroPython drivers for the Cutebot Pro) lives in
[`eggressive/microbit-cutebot-pro`](https://github.com/eggressive/microbit-cutebot-pro).

NUS UUIDs (the micro:bit uses its OWN naming, the OPPOSITE of the standard Nordic
NUS convention):

- Service: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
- `6e400002` = TX (micro:bit -> phone, INDICATE only, NOT writable)
- `6e400003` = RX (phone -> micro:bit, WRITE) - the phone writes to THIS one

The phone writes command bytes followed by a newline to the RX characteristic
(`6e400003`). The firmware reads with a newline delimiter.

## Speech recognition (Vosk)

Offline, on-device speech recognition via
[Vosk](https://alphacephei.com/vosk/). Two models are bundled (fetched via
`fetch-models.sh`, not committed):

- English: `vosk-model-small-en-us-0.15`
- Dutch: `vosk-model-small-nl-0.22`

## Build

Requires JDK 17 and the Android SDK (platform 34, build-tools 34).

```bash
./fetch-models.sh          # download the Vosk models into app/src/main/assets/
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

## Permissions

- `RECORD_AUDIO` (speech recognition)
- `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (Android 12+)
- `BLUETOOTH` / `BLUETOOTH_ADMIN` (Android 11 and below)
- `ACCESS_FINE_LOCATION` (required for BLE scanning on Android 6 through 11)
