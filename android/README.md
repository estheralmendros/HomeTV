# HomeTV for Android TV

Viewer-only Android TV / Google TV client of HomeTV.

- `core/`: pure Kotlin/JVM module (API client, session renewal, repositories, version/track/caps
  rules, error mapping, progress reporting). Tested with JUnit 5, MockWebServer and coroutines-test.
- `app/`: Android application (Compose for TV, Media3 ExoPlayer, and libVLC 3.7.2 as the fallback player for
  formats the TV can't decode). libVLC's native code makes the APK ARM-only (`armeabi-v7a` + `arm64-v8a`, one
  APK of about 95 MB). Its LGPL notice is `app/src/main/res/raw/third_party_notices.txt` (Ajustes → Acerca de →
  Licencias de código abierto); a libVLC bump must update it (`ThirdPartyNoticesTest` checks the version).

## Requirements

JDK 21 (bytecode targets 17), Android SDK with `platforms;android-35` and `build-tools;35.0.0`.
Write `local.properties` with `sdk.dir=<path to the SDK>` (gitignored). The Gradle wrapper (8.x) is
committed; always use `./gradlew`.

## Build, test, install

```sh
./gradlew :core:test :app:testDebugUnitTest   # JVM tests (also `make tv-test` from the repo root); app
                                              # ViewModel tests (JUnit 5) + Compose focus/navigation tests
                                              # on Robolectric (src/sharedTest, also compiled for androidTest)
./gradlew :app:assembleDebugAndroidTest       # the same Compose tests for a TV emulator (connectedDebugAndroidTest)
./gradlew :app:lintDebug                      # Android lint (`make tv-lint`)
./gradlew :app:assembleDebug                  # debug APK (allows http://10.0.2.2 and localhost)
./gradlew :app:assembleRelease                # signed release APK (`make tv-build`)
```

Release signing: create a key once
(`keytool -genkeypair -v -keystore ~/keys/hometv-tv.jks -alias hometv -keyalg RSA -keysize 4096 -validity 10000`),
copy `keystore.properties.example` to `keystore.properties` and fill it in. Updates with
`adb install -r` need the same key. Without `keystore.properties`, `assembleRelease` fails with a
message that says so.

Install or update on the TV:

```sh
adb pair <tv-ip>:<pairing-port>               # once per computer (Wireless debugging → pair with code)
adb connect <tv-ip>:<connection-port>         # port from the Wireless debugging screen; changes on restart
adb -s <tv-ip>:<connection-port> install -r app/build/outputs/apk/release/app-release.apk
```

The version comes from the repo-root `version.txt`. Step by step (key questions, ports, connection
errors, the update checklist): see [`docs/tv-app-installation.md`](../docs/tv-app-installation.md).
