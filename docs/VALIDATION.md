# Validation

## Reproduce device tests

Build an internal x86_64 variant only for the emulator (distribution uses the default ARM64 variant):

```sh
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :fixture-cloudstream:assembleDebug -PtestAbi=x86_64
adb install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb push fixture-cloudstream/build/outputs/apk/debug/fixture-cloudstream-debug.apk /data/local/tmp/open-cinema.cs3
adb shell run-as app.yoru mkdir -p files
adb shell run-as app.yoru cp /data/local/tmp/open-cinema.cs3 files/test-open-cinema.cs3
adb shell am instrument -w -e class app.yoru.RuntimeIntegrationTest app.yoru.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class app.yoru.PlayerIntegrationTest app.yoru.test/androidx.test.runner.AndroidJUnitRunner
```

These tests make live network requests. A corporate/sandbox HTTPS proxy requires its public CA in the test device trust store; app TLS verification must remain enabled.

## Coverage

The runtime test checks live Keiyoushi index parsing, signature/package/version validation, loading the current signed xkcd APK, source browsing, details, chapters and decoded page images; a separately compiled CS3 fixture checks Cloudstream provider loading, search, details, stream resolution and Referer preservation. The same test checks removal. A backup test rejects incomplete data without replacing existing state.

The player test starts the real PlayerActivity, decodes a public CC0 MP4, checks video dimensions and advancing position, and checks persisted resume position.

Assembler tests reject missing, duplicate, renamed, wrong-size and corrupted parts and verify shuffled parts reconstruct the original bytes. Final delivery is checked with apksigner and aapt.

Passing these checks is not universal source certification. Real websites, source-specific anti-bot/login behavior, HLS/DASH variants, DRM, and hardware behavior vary. ARM64 physical-device performance, PiP interactions and external subtitle selection require further device coverage.

## Observed results (2026-10-20)

- API30 x86_64 software emulator: RuntimeIntegrationTest **2/2 passed** (149.069s), including live xkcd APK signature verification, extension execution, decoded page image, separately compiled Cloudstream provider/search/detail/stream with Referer, removal, and malformed-backup rejection.
- PlayerIntegrationTest **1/1 passed** (39.588s): real native MP4 decoding, advancing playback and saved resume position.
- Final ARM64 assemble and Android lint passed: **0 errors, 22 warnings**. Warnings concern newer dependency versions, the deliberate ABI restriction and Compose/KTX guidance.
- Offline assembler behavioral tests **3/3 passed**; final five-part APK reconstruction passed whole-file SHA-256 and Android v2 signature verification. Browser assembly also completed with a visible APK download.
- The live-source test exposed an upstream legacy/modern manga-API dispatch bug; it was fixed and the full test then passed. An earlier attempt was interrupted during emulator service recovery; it is not counted as a pass.
- Native visual automation was affected by emulator System UI ANRs. Hardware smoothness and the additional player flows listed above are not certified by these tests.
