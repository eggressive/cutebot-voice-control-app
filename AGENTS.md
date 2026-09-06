# AGENTS.md

Guidance for AI coding agents (Claude Code, Codex, Copilot, Hermes, etc.) working in this repo.

## What this repo is

A native Android app (Kotlin) that voice-controls the ELECFREAKS Cutebot Pro
robot car over Bluetooth Low Energy. Speech recognition is fully offline via
Vosk (English + Dutch models). The phone does the speech recognition; the
micro:bit (running the companion MakeCode firmware) only maps incoming bytes to
motor commands.

## Hard constraints (do not violate)

1. **No em dashes (U+2014)** in any generated content: code, comments, strings,
   commit messages, README, AGENTS.md. Use a hyphen, colon, parentheses, or
   separate sentences.
2. **Do not commit the Vosk models.** They live in `app/src/main/assets/` and are
   fetched by `fetch-models.sh` (~134 MB). They are gitignored. Never force-add
   them.
3. **Do not commit signing credentials.** Release keystore paths/passwords come
   from `local.properties` or environment variables (`RELEASE_STORE_FILE`,
   `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`).
   `local.properties` is gitignored. Never commit it or any keystore.
4. **Do not commit build artifacts.** `*.apk`, `*.aab`, `build/`, `.gradle/` are
   gitignored.
5. **The NUS write characteristic is `6e400003`, not `6e400002`.** The micro:bit
   uses its OWN UART naming, the OPPOSITE of the standard Nordic NUS convention:
   - `6e400002` = TX (micro:bit -> phone, INDICATE only, NOT writable)
   - `6e400003` = RX (phone -> micro:bit, WRITE) - the phone writes to THIS one
   Do not "fix" this to match the standard Nordic convention; it will break the
   write and the app will report "Failed to send".

## Command map (must stay in sync with the firmware)

| Voice (EN) | Voice (NL) | Byte |
|---|---|---|
| forward | vooruit | `1` |
| left | links | `2` |
| right | rechts | `3` |
| stop | halt | `4` |
| back | achteruit | `5` |

The firmware repo is
[`eggressive/cutebot-voice-control-firmware`](https://github.com/eggressive/cutebot-voice-control-firmware).
If you add or change a command here, change the firmware's `main.ts` to match,
and vice versa. The app writes `"N\n"` (command byte + newline); the firmware
reads with a newline delimiter.

## Speech recognition (Vosk)

- Offline, on-device. Dependency: `com.alphacephei:vosk-android:0.3.47`.
- Two models bundled: `vosk-model-small-en-us-0.15` (English) and
  `vosk-model-small-nl-0.22` (Dutch).
- Dutch loads at startup; English loads lazily on first toggle (reduces
  cold-start time and peak memory).
- **Only final recognition results drive the car.** Partial results are shown on
  screen but not sent. Do not revert to sending on partials: that workaround was
  specific to Google's `SpeechRecognizer` (which returned empty final results);
  Vosk always emits a final result.
- Commands are matched by **whole word** (`\bright\b`), not substring, to avoid
  false positives like "bright" or "alright".

## BLE handling

- A write queue serializes commands: each write waits for `onCharacteristicWrite`
  before the next is issued. Do not bypass it.
- The write characteristic's actual properties are inspected to pick WRITE vs
  WRITE_NO_RESPONSE. Do not hardcode one write type.
- There is a 15-second scan timeout and a NUS service UUID scan filter.
- Scanning/listening stop in `onPause` and resume in `onResume`. All Handler
  callbacks are removed in `onDestroy` (prevents a `postDelayed` leak). Models
  are closed and GATT disconnected on destroy.

## Build, verify, install

```bash
# one-time: fetch the Vosk models (not committed)
./fetch-models.sh

# build the debug APK
./gradlew assembleDebug

# lint (must pass)
./gradlew lintDebug

# install/update on a USB-connected phone (USB debugging authorized)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 (sdkman Temurin 17) and the Android SDK (platform 34,
build-tools 34). The debug APK is ~120 MB because both Vosk models are bundled.

## Verification standard

- `./gradlew clean assembleDebug lintDebug` is the mandatory pre-commit bar.
- There is no emulator for the BLE + micro:bit path. On-device behavior (BLE
  connect, motor movement, speech accuracy) must be verified by a human with the
  phone + micro:bit + car. Flag such claims as unverified otherwise.
- The micro:bit must be unplugged from USB and powered by the car's battery to
  advertise BLE. USB disables BLE advertising. The car must be switched ON for
  the motor board (I2C `0x10`) to respond.

## Commit / PR style

- Conventional-ish subject line, imperative mood. Body explains WHY.
- No generated-file noise: never commit `*.apk`, `*.aab`, `build/`, or the Vosk
  models.
- Keep diffs mechanical: one logical change per commit.
