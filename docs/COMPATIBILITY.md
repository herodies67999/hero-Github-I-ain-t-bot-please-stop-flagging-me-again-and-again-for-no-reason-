# Extension compatibility

Yoru hosts executable third-party packages through the pinned AnymeX runtime.

## Mihon

Keiyoushi gzip protobuf index, APK package/version/signer checks, explicit trust confirmation, private package installation, source enumeration, popular/search/detail/chapter/page calls, lazy page-image resolution and request headers. API support follows the vendored runtime (up to extension-lib 1.6). Source dependencies, anti-bot challenges, WebView login and source-specific preferences can affect individual providers. Universal compatibility is not claimed.

## Cloudstream

Add an HTTPS repository JSON with pluginLists. Provider metadata and CS3 archives are downloaded on demand. Plugins register actual MainAPI providers and extractors. Source search/detail/loadLinks results feed Media3 with headers, stream choice and external subtitles. CS3 code is executable code; confirm trust only for repositories you trust. The runtime shares the app process and is not a security sandbox.

## Installation and updates

Download, inspect and hash before explicit confirmation. Mihon signer identity is verified with apksig and pinned for Keiyoushi. Installed packages are checked against recorded hashes before execution. Replacement keeps a rollback copy until loading succeeds. Removal unloads providers and deletes private files. Android does not need to install extension APKs as system applications.

## Delivery

Only arm64-v8a is distributed. x86_64 is an internal test override. Offline delivery files are byte chunks of one signed APK, not Android split-install APKs. Open assemble-yoru.html locally, choose every .part file, and save the verified complete APK. The HTML checks each part and the final SHA-256 without network access.
