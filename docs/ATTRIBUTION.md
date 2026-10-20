# Provenance and licenses

Yoru 0.3 incorporates AnymeXExtensionRuntimeBridge under its Unabandon Public License, which incorporates GPLv3 and requires public source availability. See root LICENSE and docs/licenses/GPL-3.0.txt. This is the upstream's license, not the unrelated Universal Permissive License.

## Runtime

- Source: https://github.com/RyanYuuki/AnymeXExtensionRuntimeBridge
- Exact revision: 0800a7d60d042a87f1f6ce23bb0aa83df5fa2990
- Vendored directory: android/extension-runtime/src/main/kotlin
- Modifications: Android library conversion; removal of Kotatsu integration; explicit source-cache invalidation; modern Keiyoushi combined-update API dispatch; private trusted-package loading instead of scanning system packages; lazy manga-image resolution; token-protected loopback image proxy.
- Includes Mihon/Tachiyomi/Aniyomi API implementations; the retired anime provider/plugin runtime has been removed. Original file-level copyright and license notices remain in place.

## Application references

- Mihon: https://github.com/mihonapp/mihon — source APIs and reader/library workflow.
- Keiyoushi: https://github.com/keiyoushi/extensions — live protobuf repository and installable APK sources.
- Aniyomi: https://github.com/aniyomiorg/aniyomi — anime source, video and hoster APIs.
- Yūzōnō: https://github.com/yuzono/anime-extensions — current extension index and reference implementations; revision 466fb9c4f9338322846cd371050ca9c45d418aad.
- AndroidX Media3: https://github.com/androidx/media — native playback, HLS, DASH, captions and tracks (Apache 2.0).
- Jetpack Compose, AndroidX and Material icons: Apache 2.0.
- Coil, OkHttp and Gson: Apache 2.0.
- noble-hashes and esbuild: MIT. Gradle wrapper: Apache 2.0.

## Artwork and fonts

Title text, covers and banners were obtained from AniList. artwork.json records the original URLs and filenames. Artwork, characters and titles belong to their respective creators/publishers and are excluded from the code license; API access does not grant blanket redistribution rights. No episodes or manga chapters are bundled. Review artwork rights before public commercial distribution.

DM Sans, Manrope and Cormorant Garamond: SIL Open Font License 1.1; license files included here.
The separately built test extension APK uses Big Buck Bunny (Blender Foundation, CC BY 3.0) only for runtime verification; it is not bundled as a production source.

Cormorant Garamond downloaded from https://github.com/google/fonts/tree/main/ofl/cormorantgaramond for manga editorial typography. Original font license is included.
