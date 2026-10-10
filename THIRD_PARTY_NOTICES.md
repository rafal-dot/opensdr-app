# OpenSDR Third-Party Notices

This file covers the public OpenSDR Radio Host source package.

## Shared Radio API

The bundled `radioapi` module is project-owned software under the MIT License,
separate from the GPL-licensed Host and core. Preserve `radioapi/LICENSE` and
its binary asset copy `radioapi/src/main/assets/licenses/radioapi-MIT.txt`.
The MIT grant does not change the licenses of the dependencies listed below.

## Bundled Native Components

### libusb

- Local path: `third_party/libusb`
- Upstream project: https://libusb.info/
- License: GNU Lesser General Public License version 2.1 or later, with some files under compatible or separately noted licenses.
- License file in package: `third_party/libusb/COPYING`
- Authors file: `third_party/libusb/AUTHORS`

### rtl-sdr-android

- Local path: `third_party/rtl-sdr-android`
- Upstream project family: librtlsdr / rtl-sdr
- License: GNU General Public License version 2, with additional details in bundled upstream files.
- License file in package: `third_party/rtl-sdr-android/COPYING`
- Authors file: `third_party/rtl-sdr-android/AUTHORS`

Because OpenSDR Radio Host builds and distributes GPL-covered RTL-SDR native code, binary redistribution should be accompanied by corresponding source code and preserved license texts.

## Android And Kotlin Dependencies

The Android build uses Android Gradle Plugin, Kotlin, AndroidX, Jetpack Compose, and Material components from Maven repositories. These dependencies are not vendored into this source package.

Typical upstream licenses:

- Android Gradle Plugin: Apache License 2.0
- AndroidX / Jetpack Compose / Material: Apache License 2.0
- Kotlin: Apache License 2.0

When producing a binary distribution, verify notices from the exact resolved dependency graph.

## Package Notice Rules

When publishing or redistributing OpenSDR Radio Host:

- Preserve `LICENSE.md`.
- Preserve this `THIRD_PARTY_NOTICES.md`.
- Preserve `radioapi/LICENSE` and its bundled Android asset notice.
- Preserve upstream license files in `third_party/libusb` and `third_party/rtl-sdr-android`.
- Do not remove copyright headers from upstream source files.
- Document local modifications to bundled third-party code if such modifications are made.
