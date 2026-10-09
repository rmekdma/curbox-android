# Full release

Build, sign, and verify a Full APK from a clean Windows checkout. Create the GitHub release manually from the matching tag and upload only the signed Full APK. Run the PowerShell blocks in one session; later steps reuse earlier values.

1. **Prepare the source.** In `app/build.gradle.kts`, increase `versionCode` and set `versionName` to the next `v<major>.<minor>.<patch>`. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`. Commit and push those changes to `kt-rewrite`; build from that exact clean commit.

2. **Build and check metadata.** Set `$version` to the value in `versionName`, then run from the repository root:

   ```powershell
   $ErrorActionPreference = 'Stop'
   $env:JAVA_HOME = 'C:\Users\DELL\.jdks\jbr-21.0.11'
   $version = 'v<major>.<minor>.<patch>'
   if ($version -notmatch '^v\d+\.\d+\.\d+$') { throw 'Use a v<major>.<minor>.<patch> version.' }
   if (git status --porcelain) { throw 'Checkout must be clean.' }
   $sourceCommit = (git rev-parse HEAD).Trim()
   if ($LASTEXITCODE -ne 0) { throw 'Could not read the source commit.' }

   .\gradlew.bat assembleFullRelease
   if ($LASTEXITCODE -ne 0) { throw 'Full release build failed.' }
   $metadataPath = 'app/build/outputs/apk/full/release/output-metadata.json'
   $metadata = Get-Content $metadataPath -Raw | ConvertFrom-Json
   if ($metadata.variantName -ne 'fullRelease' -or
       $metadata.applicationId -ne 'neth.iecal.curbox' -or
       $metadata.elements[0].versionName -ne $version) {
       throw 'Full release metadata does not match the requested version and package.'
   }
   $releaseDir = 'app/build/outputs/apk/full/release'
   $unsigned = Join-Path $releaseDir $metadata.elements[0].outputFile
   if (-not (Test-Path $unsigned)) { throw "Missing release APK: $unsigned" }
   ```

   Stop if the variant, application ID, version, or APK path differs.

3. **Confirm the signing key.** On this release host, the retained key is `$env:USERPROFILE\.android\debug.keystore`, alias `androiddebugkey`. Its SHA-256 certificate fingerprint is `24:29:A6:79:95:D6:E0:A0:33:12:FF:8F:67:D7:DF:53:C4:0D:BD:EB:86:E4:54:D6:2F:87:D8:E8:24:3E:4C:6A`, matching the published v4.1.2 and v4.1.3 APKs. Check it before signing:

   ```powershell
   $keyStore = Join-Path $env:USERPROFILE '.android\debug.keystore'
   & "$env:JAVA_HOME\bin\keytool.exe" -list -v -keystore $keyStore -alias androiddebugkey
   if ($LASTEXITCODE -ne 0) { throw 'Could not read the release key.' }
   ```

   Enter the local keystore password when prompted. If the file is missing or its fingerprint differs, stop and locate the original release key and credentials. Never generate a replacement key.

4. **Align, sign, and verify.** The signing commands prompt for local passwords; keep passwords out of command history.

   ```powershell
   $sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
   $buildTools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory |
       Sort-Object { [version]$_.Name } -Descending |
       Select-Object -First 1 -ExpandProperty FullName
   $aligned = Join-Path $releaseDir "curbox-$version-full-aligned.apk"
   $signed = Join-Path $releaseDir "curbox-$version-full.apk"

   & (Join-Path $buildTools 'zipalign.exe') -f -v 4 $unsigned $aligned
   if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
   & (Join-Path $buildTools 'apksigner.bat') sign --ks $keyStore --ks-key-alias androiddebugkey --out $signed $aligned
   if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
   & (Join-Path $buildTools 'zipalign.exe') -c -v 4 $signed
   if ($LASTEXITCODE -ne 0) { throw 'Signed APK alignment check failed.' }
   & (Join-Path $buildTools 'apksigner.bat') verify --verbose --print-certs $signed
   if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }

   $apkInfo = & (Join-Path $buildTools 'aapt.exe') dump badging $signed
   if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the signed APK.' }
   $apkInfo | Select-String '^(package:|application-label:|application-debuggable)'
   if (-not ($apkInfo | Select-String "^package: name='neth\.iecal\.curbox'.*versionName='$version'")) {
       throw 'Signed APK package or version is incorrect.'
   }
   if ($apkInfo -contains 'application-debuggable') { throw 'Signed APK is debuggable.' }
   ```

   Confirm the printed signing certificate matches the retained fingerprint and the APK label is `Curbox`.

5. **Tag and publish.** Confirm the checkout is still at `$sourceCommit` and clean. Create a new annotated tag using the same `$version`, then push it. If the tag already exists locally or the remote rejects it, stop and inspect it; never move a published tag.

   ```powershell
   if ((git rev-parse HEAD).Trim() -ne $sourceCommit -or (git status --porcelain)) {
       throw 'Checkout changed after the release build.'
   }
   if (git tag --list $version) { throw 'Tag already exists; inspect it before publishing.' }
   git tag -a $version -m $version $sourceCommit
   if ($LASTEXITCODE -ne 0) { throw 'Could not create the release tag.' }
   git push origin "refs/tags/$version"
   if ($LASTEXITCODE -ne 0) { throw 'Tag push failed; inspect the remote tag before retrying.' }
   ```

   Create the GitHub release from that tag and upload only `$signed`, named `curbox-$version-full.apk`, with the matching changelog as release notes. Do not upload Play Store or F-Droid APKs.
