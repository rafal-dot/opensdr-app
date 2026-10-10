# Building OpenSDR Radio Host

## Prerequisites

- Android Studio or Android SDK command-line tools.
- JDK 17.
- Android SDK platform matching `compileSdk = 36`.
- Android NDK and CMake installed through the Android SDK manager.
- Git, if building from a repository checkout.

No Play Console credentials, production signing keys, or private project files are required for a debug build.

## Get The Sources

Clone the [public repository](https://github.com/rafal-dot/opensdr-app/):

```bash
git clone https://github.com/rafal-dot/opensdr-app.git
cd opensdr-app
```

To reproduce a distributed version, use the tag or complete source archive
identified for that release rather than assuming the latest revision matches it.
The source archive can also be extracted without Git.

## Source Layout

Expected package layout:

```text
.
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew
├── gradle/
├── radioapi/
├── opensdrcore/
├── rtlsdrhost/
└── third_party/
```

The public `settings.gradle.kts` includes only:

- `:radioapi`
- `:opensdrcore`
- `:rtlsdrhost`

It does not require any separate client application module.

## Debug Build

```bash
./gradlew --no-daemon :rtlsdrhost:assembleDebug
```

On Windows PowerShell:

```powershell
.\gradlew.bat --no-daemon :rtlsdrhost:assembleDebug
```

Expected output:

```text
rtlsdrhost/build/outputs/apk/debug/opensdr-debug.apk
```

## Diagnostic APK

For buffered raw IQ and playback WAV recording in an optimized internal APK, use
`:rtlsdrhost:assembleDiagnostic`. Debug APKs include the same controls. See
[Diagnostic Recording](DIAGNOSTIC_RECORDING.md) for setup, formats and limits.
Recording is excluded from release APKs/AABs; diagnostic/debug AAB packaging is
blocked automatically. Release packaging verifies that recorder code is absent.

## Kotlin Compile Check

```bash
./gradlew --no-daemon :rtlsdrhost:compileDebugKotlin
```

## Native Build Notes

`opensdrcore` uses CMake through Android Gradle Plugin:

```text
opensdrcore/src/main/cpp/CMakeLists.txt
```

The native build uses bundled sources from:

- `third_party/libusb`
- `third_party/rtl-sdr-android`

If native build fails, first verify that Android NDK and CMake are installed for the SDK used by Gradle.

## Release Build

```bash
./gradlew --no-daemon :rtlsdrhost:assembleRelease
```

The resulting APK is not production-signed unless signing configuration is supplied by the distributor. Do not place private signing keys in the public source package.

## Android App Bundle

```bash
./gradlew --no-daemon --no-parallel :rtlsdrhost:bundleRelease
```

On Windows, use `.\gradlew.bat` with the same arguments. The final bundle is:

```text
rtlsdrhost/build/outputs/bundle/release/opensdr-release.aab
```

The module's `base.archivesName` supplies the `opensdr` prefix for AAB and APK
files; it does not change the application ID or signing. For Google Play,
configure release signing or use Android Studio's **Generate Signed Bundle / APK >
Android App Bundle**. The wizard can use a different destination directory.
Without release signing configured, `bundleRelease` alone does not produce a
signed upload build. Do not publish `intermediary-bundle.aab` from
`build/intermediates/`; use the final signed bundle instead.

## Clean Build

```bash
./gradlew --no-daemon clean
./gradlew --no-daemon :rtlsdrhost:assembleDebug
```

## Troubleshooting

- If Gradle cannot find the Android SDK, create a local `local.properties` outside version control or set `ANDROID_HOME`.
- If CMake or NDK is missing, install them through Android Studio SDK Manager.
- If a client cannot bind to the service, verify the package name, service action, and signature permission described in `COMPATIBILITY.md`.
