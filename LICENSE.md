# OpenSDR Radio Host License

This license notice applies to the public OpenSDR Radio Host source package only.

## Project-Owned OpenSDR Code

Unless a file states otherwise, project-owned source files in the public OpenSDR package are distributed under:

```text
GNU General Public License version 2.0 or later
SPDX-License-Identifier: GPL-2.0-or-later
```

This includes project-owned files under:

- `opensdrcore`
- `rtlsdrhost`
- public package documentation
- public package scripts

Separate client applications are not part of this package and are not licensed by this notice.

## Radio API: MIT

The project-owned code, build configuration and documentation under `radioapi`
are licensed separately under the MIT License (SPDX-License-Identifier: MIT),
not the default GPL notice above. See [radioapi/LICENSE](radioapi/LICENSE) for
the full copyright and permission notice.

Open-source and proprietary client applications, including paid applications,
may include this API under the MIT terms. Preserve the MIT notice when
redistributing the API or binaries containing it. An Android asset copy is
provided in `radioapi/src/main/assets/licenses/radioapi-MIT.txt`.

This permission does not relicense the Host, receiver core or bundled native
dependencies. Clients communicate with the separately running Host through
Android IPC rather than linking its implementation. Earlier GPL grants for
previously distributed copies of the API are not revoked.

## Third-Party Code

This package includes third-party source code under its own licenses.

Important bundled components:

- `third_party/rtl-sdr-android`: GPL-covered RTL-SDR source. See `third_party/rtl-sdr-android/COPYING`.
- `third_party/libusb`: LGPL-covered libusb source. See `third_party/libusb/COPYING`.

See `THIRD_PARTY_NOTICES.md` for details.

## No Warranty

This software is provided without warranty. It is intended for general-purpose radio reception, hobby listening and experimentation, not safety-critical, navigation or operational decision-making systems. This safety information does not add a field-of-use restriction to the applicable open-source licenses.
