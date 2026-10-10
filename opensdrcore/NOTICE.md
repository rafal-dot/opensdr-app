# Third-Party Components

`opensdrcore` builds against bundled third-party native sources from `third_party/`.

See the repository-level `THIRD_PARTY_NOTICES.md` for the production distribution checklist.

## Included dependencies

### libusb

- source path: `third_party/libusb`
- upstream licensing files:
  - `third_party/libusb/COPYING`
  - `third_party/libusb/README`
  - `third_party/libusb/AUTHORS`

### rtl-sdr-android

- source path: `third_party/rtl-sdr-android`
- upstream licensing files:
  - `third_party/rtl-sdr-android/COPYING`
  - `third_party/rtl-sdr-android/README`
  - `third_party/rtl-sdr-android/AUTHORS`

## Notes

- This file is only a repository-level pointer for maintainers.
- If `opensdrcore` or `rtlsdrhost` are published separately, include the relevant upstream license texts in the distributed package as required by their licenses.
