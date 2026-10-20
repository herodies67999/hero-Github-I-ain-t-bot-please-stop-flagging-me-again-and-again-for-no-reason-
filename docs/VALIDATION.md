# Validation

## Reproduction

JDK17, Gradle8.13, Android SDK36. Build the test architecture separately from the distributed ARM64 APK:

```sh
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :fixture-aniyomi:assembleDebug :app:testDebugUnitTest :app:lintDebug -PtestAbi=x86_64 --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process
adb install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb push fixture-aniyomi/build/outputs/apk/debug/fixture-aniyomi-debug.apk /data/local/tmp/open-cinema.apk
adb shell run-as app.yoru mkdir -p files
adb shell run-as app.yoru cp /data/local/tmp/open-cinema.apk files/test-open-cinema.apk
adb shell am instrument -w -e class app.yoru.RuntimeIntegrationTest app.yoru.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class app.yoru.PlayerIntegrationTest app.yoru.test/androidx.test.runner.AndroidJUnitRunner
```

The Aniyomi fixture is a separate APK compiled only against host APIs. It exercises the combined update API, named episode, hoster extraction, headers and timestamps. Runtime tests also verify live Yuzono repository/APK loading and signer continuity, live Keiyoushi xkcd manga page decoding, removal, and invalid backups.

`npm test` exercises offline assembly selection/hash failures and arbitrary part order. The signed delivery is additionally reconstructed and checked with Android apksigner/aapt.

## v0.3 results

Results below describe this update. Physical ARM64 hardware, every third-party site, DRM, all HLS/DASH variations, PiP and all subtitle/audio formats require additional device coverage.

- Debug/test and ARM64 release compilation passed. Final release lint:0 errors,26 warnings.
- Chapter/episode label test passed (source names, existing prefixes, fractional and unknown numbers).
- v0.2→v0.3 x86_64 update installation passed without uninstall. Synthetic library title and playback position12345ms retained; old repository removed; Yuzono added. Comparison normalizes Gson's omitted optional null fields.
- Release APK signature matches the delivered v0.2 certificate; version code increases2→3. aapt confirms ARM64 only, minAPI26,target35.
- Offline assembler:3 behavioral tests passed. Below-limit APK delivered whole. Final four-part reconstruction is byte-identical and passes apksigner; browser reports complete APK ready.
- RuntimeIntegrationTest:3/3 passed,124.854s on API30 x86_64 software emulator. Live signed AnimeOnsen14.10 and AnimePahe16.55 loaded, second-download certificate continuity checked, then removed. Live xkcd browse/details/chapter/page image decoded. Separate Aniyomi fixture exercised combined update, named episode, hoster stream, Referer and timestamps. Malformed-backup rejection passed. This proves host execution and fixture extraction; it does not assert live streaming from every Yuzono site.
- PlayerIntegrationTest:1/1 passed,23.76s. Final PlayerActivity decoded a real MP4, reported nonzero video dimensions, advanced beyond500ms, and saved resume progress.

- Final UI build/lint passed after viewport-aware hero spacing, artwork palette/parallax and mode-switch contrast adjustments. Engine/player passes reused because those inputs are unchanged. Final anime/manga screenshots inspected: mode/artwork/typography changed correctly, actions visible above navigation, no overlap. Snapshot helper was unreliable in software emulation; screenshots and Android input were used for visual verification.
