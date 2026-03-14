# Hubble Gateway SDK - Android Quickstart

## Build
- Set `JAVA_HOME` to the Android Studio JBR path (see memory for local paths), then: `./gradlew assembleDebug`
- Release build: `./gradlew assembleRelease` (requires signing config, see below)
- Uses composite build with local SDK from `../gateway-sdk-android` when `useLocalSdk=true` in `local.properties`
- SDK key is read from `hubbleSdkKey` in `local.properties` and injected via `BuildConfig.HUBBLE_SDK_KEY`

## Signing
- Release signing reads from `local.properties` (local) or environment variables (CI)
- Local: set `releaseKeystoreFile`, `releaseKeystorePassword`, `releaseKeyAlias`, `releaseKeyPassword` in `local.properties`
- CI: set `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` as secrets
- `.gitignore` already excludes `*.jks`, `*.keystore`, and `signing.properties`

## CI
- `.github/workflows/release.yml` — builds a signed release APK on `v*` tag push, creates a GitHub Release with APK and mapping file
- Required GitHub secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `HUBBLE_SDK_KEY`

## Project structure
- Single-activity app demonstrating all Hubble Gateway SDK features
- `QuickstartApplication` — SDK initialization
- `MainActivity` — scanning controls, capabilities display
- `ScanResultAdapter` — scan result rendering
- SDK source of truth: `../gateway-sdk-android/gateway-sdk/api/gateway-sdk.api` (binary compatibility contract)

## Conventions
- Java (not Kotlin) for the quickstart app
- ViewBinding for all UI access
- Material 3 components
- String resources in `strings.xml` for all user-visible text
