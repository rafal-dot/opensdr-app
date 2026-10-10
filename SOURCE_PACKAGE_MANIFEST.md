# OpenSDR Source Package Manifest

This standalone project contains the sources needed to build OpenSDR Radio Host.
Build and test instructions are in `BUILDING.md` and `DSP_ARCHITECTURE.md`.
No external packaging script or separate application repository is required.

## Included

- `README.md`
- `BUILDING.md`
- `COMPATIBILITY.md`
- `DSP_ARCHITECTURE.md`
- `DIAGNOSTIC_RECORDING.md`
- `AM_PROFILES.md`
- `LICENSE.md`
- `THIRD_PARTY_NOTICES.md`
- `SOURCE_PACKAGE_MANIFEST.md`
- `build.gradle.kts`
- `settings.gradle.kts`
- `.gitignore`
- `gradle.properties`
- `gradlew`
- `gradlew.bat`
- `gradle/wrapper/`
- `radioapi/`
- `radioapi/LICENSE` and `radioapi/src/main/assets/licenses/radioapi-MIT.txt`
- `opensdrcore/`
- `rtlsdrhost/`
- `third_party/libusb/`
- `third_party/rtl-sdr-android/`

## Release Archives

Publish the contents of this directory, including `.gitignore`, to
[rafal-dot/opensdr-app](https://github.com/rafal-dot/opensdr-app/).
Do not include local build output, IDE caches, signing keys, credentials or
machine-specific configuration. The supplied `.gitignore` excludes these files.

After committing and tagging a verified release in that repository, create a
source archive with Git. Replace `vX.Y.Z` with the actual release tag:

```bash
git archive --format=tar.gz --prefix=opensdr-radio-host/ --output=../opensdr-radio-host-source.tar.gz vX.Y.Z
```

The archive includes tracked sources and bundled native dependencies, not Git
history or untracked build products. Verify its contents before publishing it
as a release asset; changes not committed at the chosen tag are not included.

Before distributing a binary, publish a tag or complete corresponding source
release matching that binary and link it from the website's license page. Merely
linking the repository's latest revision does not identify the exact build source.
