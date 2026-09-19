# Release builds and publishing

Guidelines for building and publishing Curbox release artifacts.

## Release APK commands and outputs

| Variant | Command | Unsigned output |
| --- | --- | --- |
| Full | `.\gradlew.bat assembleFullRelease` | `app/build/outputs/apk/full/release/app-full-release-unsigned.apk` |
| Play Store | `.\gradlew.bat assemblePlaystoreRelease` | `app/build/outputs/apk/playstore/release/app-playstore-release-unsigned.apk` |
| F-Droid | `.\gradlew.bat assembleFdroidRelease` | `app/build/outputs/apk/fdroid/release/app-fdroid-universal-release-unsigned.apk` |

## Publishing rules

- **Upload target**: Upload only the Full Release APK to GitHub. Do not upload Play Store or F-Droid APKs.
- **Asset naming**: Use a semver version name format `v<major>.<minor>.<patch>` and name the uploaded APK `curbox-<version>-full.apk` (e.g., `curbox-v4.0.4-full.apk`).
- **Direct upload**: Upload the APK file directly to GitHub releases. Do not use CI for APK uploads.
- **Release artifacts**: Treat only `*Release` tasks as release artifacts. Debug APKs use the `.debug` application ID suffix, the `Debug Curbox` label, and `application-debuggable`; never publish a debug APK as a release APK.
- **Signing verification**: Release tasks produce unsigned APKs by default. Verify signing before describing an APK as installable or publishing it without an `unsigned` notice.
- **Metadata inspection**: Before publishing a Full APK, inspect `app/build/outputs/apk/full/release/output-metadata.json`. It must report `variantName` as `fullRelease` and a version name matching `v<major>.<minor>.<patch>`.
- **File size**: File size alone does not identify a flavor. Debug builds contain additional debug symbols and assets and are larger than equivalent release builds.
