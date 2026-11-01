# Releasing

## Signing key

- The release key is `D:\android\keys\sbphone-release.jks`, with its passwords in `D:\android\keys\keystore.properties`. Both live outside the repository.
- `local.properties` (not committed) carries the same four `release.*` lines so Gradle can sign.
- **Back the key up.** Android only installs an update over the app if it is signed with the same key. A lost key means uninstalling the app, which deletes the notes on the phone unless they were exported first.

## Steps

1. Raise `versionCode` (by one) and `versionName` in `app/build.gradle.kts`.
2. `.\gradlew.bat :fold:test :datapack:test :datapack:run :app:assembleRelease`
3. Check the datapack log: rights gate passes, every fingerprint line shows `0 found in app.db`.
4. Create a GitHub release with the APK attached, named `sb-phone-<versionName>.apk`:
   `gh release create v<versionName> app/build/outputs/apk/release/app-release.apk#sb-phone-<versionName>.apk --notes "..."`

## Installing on the phone

- **First time:** open the release page on the phone, tap the APK, and allow installs from the browser when Android asks.
- **Updates:** Obtainium (free, open source) can watch the repository and install new releases. Point it at https://github.com/simple-eiffel/sb_phone.
