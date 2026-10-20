# Yoru 夜

Native Android anime and manga client with a cinematic Compose interface, a personal library, and dynamically installed Mihon/Cloudstream sources.

## Features

- Anime/manga mode switch, editorial artwork, layered dark surfaces, ambient cover colors, parallax hero, dark/light/custom accent themes.
- Source search, title details, chapter/episode lists, library, history and JSON backup/restore.
- Keiyoushi protobuf repository, verified APK installation, updates and removal.
- Cloudstream repository and CS3 plugin execution through the vendored runtime.
- Paged/webtoon manga reader, RTL, pinch zoom and progress.
- Media3 player with HLS/DASH, source headers, track/speed controls, captions, picture-in-picture and resume.

Install sources in Extensions, then open an installed source to browse or search. Editorial titles let you choose a source to search; artwork does not imply a bundled streaming catalog.

## Build

Requires JDK 17, Android SDK platform 36, and Node 20+ for APK delivery tooling. The Gradle wrapper pins Gradle 8.13. Set JAVA_HOME and ANDROID_HOME for your machine.

```sh
npm ci
npm test
npm run android -- artifacts/yoru-v0.2-arm64.apk
npm run split -- artifacts/yoru-v0.2-arm64.apk artifacts/delivery 8
```

The build produces one arm64-v8a debug-signed APK. Use your own signing configuration for a release; debug builds are not store releases. Never commit signing keys.

## Offline HTML APK assembler

Download all .part files and assemble-yoru.html from the same build. Open the HTML in a modern browser, select every part (any order), then save the reconstructed APK. Each part and the complete APK are SHA-256 checked. No server or Android compiler runs in the browser.

## Runtime and source

The extension runtime is derived from [AnymeXExtensionRuntimeBridge](https://github.com/RyanYuuki/AnymeXExtensionRuntimeBridge) at 0800a7d60d042a87f1f6ce23bb0aa83df5fa2990. See [provenance and licenses](docs/ATTRIBUTION.md), [compatibility](docs/COMPATIBILITY.md), and LICENSE. Public corresponding source must accompany distribution under the upstream license. Extensions execute inside the application process; install code only from repositories you trust.

## Device integration tests

The fixture-cloudstream module builds a separate test plugin (not included in the distributed app). RuntimeIntegrationTest installs the live signed xkcd extension, invokes browse/details/pages, and loads the separately compiled Cloudstream fixture to resolve a stream and headers. Device test setup and observed results are documented in docs/VALIDATION.md.
