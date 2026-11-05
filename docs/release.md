# Release builds and publishing

Guidelines for building and publishing Curbox release artifacts.

## Release APK commands and outputs

| Variant | Command | Unsigned output |
| --- | --- | --- |
| Full | `.\gradlew.bat assembleFullRelease` | `app/build/outputs/apk/full/release/app-full-release-unsigned.apk` |

## Publishing rules

- **Upload target**: Upload only the Full Release APK to GitHub.
- **Asset naming**: Use a semver version name format `v<major>.<minor>.<patch>` and name the uploaded APK `curbox-<version>-full.apk` (e.g., `curbox-v4.1.0-full.apk`).
- **Upload**: The GitHub Release workflow signs and uploads the Full APK; a manual release can upload the signed APK directly.
- **Release artifacts**: Treat only `*Release` tasks as release artifacts. Debug APKs use the `.debug` application ID suffix, the `Debug Curbox` label, and `application-debuggable`; never publish a debug APK as a release APK.
- **Signing requirement**: Release tasks produce unsigned APKs by default. Android refuses to install unsigned APKs ("The package is invalid" / `INSTALL_PARSE_FAILED_NO_CERTIFICATES`). Always sign the APK using `apksigner` before publishing.
- **Metadata inspection**: Before publishing a Full APK, inspect `app/build/outputs/apk/full/release/output-metadata.json`. It must report `variantName` as `fullRelease` and a version name matching `v<major>.<minor>.<patch>`.
- **File size**: File size alone does not identify a flavor. Debug builds contain additional debug symbols and assets and are larger than equivalent release builds.
