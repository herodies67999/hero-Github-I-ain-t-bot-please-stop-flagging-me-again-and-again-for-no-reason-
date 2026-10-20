# Extension compatibility

## Aniyomi / Yūzōnō

Default index: https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json

Yoru parses package IDs, APK paths, language, version and content flags from the JSON array. APKs must have a valid Android signature, matching package/version and `tachiyomi.animeextension` feature. First installation shows the certificate for explicit trust; updates must retain the installed certificate. The index itself does not publish a signing certificate, so an HTTPS download alone is not represented as publisher verification.

The private loader supports extension library versions 12–17, source factories, legacy RxJava calls, combined anime/episode updates, hoster-based extraction and deferred video URLs. Headers, external subtitles/audio and segment timestamps pass to Media3. Source preferences use the source's own callbacks. System-installed APKs are not automatically executed.

Reference studied: https://github.com/yuzono/anime-extensions at `466fb9c4f9338322846cd371050ca9c45d418aad`, including AnimeOnsen's legacy API and AnimePahe's hoster API. The supplied live index contained 251 packages during implementation. Individual sites can change or require login/WebView challenges. MPV/FFmpeg-specific arguments and DRM licenses are not implemented by the Media3 player. Universal provider compatibility is not claimed.

## Mihon

Keiyoushi gzip protobuf index, APK package/version/signer checks, explicit trust, private installation, popular/search/details/chapters/pages, combined-update API and lazy image resolution with source clients. API support follows the vendored runtime (extension-lib through 1.6). Source headers and interceptors are retained for manga images.

## Updates and migration

v0.3 removes the previous anime plugin engine and its executable packages. Existing library/history, manga extensions, themes and backup data remain. Saved titles belonging to retired providers remain visible but must be found again in an Aniyomi source. Source IDs and episode URLs cannot safely be mapped automatically between unrelated engines.

Both engines validate stored package hashes before execution, quarantine unknown APKs, and roll back unsuccessful installation. Extensions execute inside the app process; they are not isolated from app data.

## Playback and reading

Media3 supports MP4/HLS/DASH, source headers, embedded tracks, external captions, external audio selection, opening/ending timestamp skips, speed, seek, fit/fill, rotation, PiP and resume. Codec availability depends on Android/device hardware. External audio must be time-aligned with its video.

Chapter/episode labels retain the exact extension name and add its supplied number when needed; unknown numbers are not fabricated. Reader options persist, layout switching retains the current page, and both paged and webtoon modes save progress.

## Delivery and signing

Only ARM64 is distributed. Internal x86_64 builds are for tests. Release variant uses the same sandbox development signing identity as v0.2 so this delivered build can update it. This is not a store production key. A fresh clone has a different debug key; maintainers must securely preserve their original signing key for future updates.

Files above 25,000,000 bytes are split into byte chunks with an offline HTML assembler. These are pieces of one signed APK, not Android split-install APKs. Smaller APKs are delivered whole. All chunks and the reconstructed APK are SHA-256 verified.
