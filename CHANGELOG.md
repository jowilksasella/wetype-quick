# Changelog

## v0.1.0

First public-source preparation of the experimental Quick engine and WeType 3.5.4
interoperability patches, based on the corrected Q5r2 integration.

- Traditional Chinese/Cantonese local lookup, composition, learning and candidate actions
- Original keyboard/candidate/picker integration with guarded lifecycle and editor handling
- Optional built-in-microphone preference for an already-connected Bluetooth audio session
- Direct clean-from-original, hash-pinned local build; no intermediate modified APK required
- Release the transient candidate-plan reference at already-private session boundaries, with regression coverage
- Source-only release boundary, separate data licensing and three-language README
- Reproducible source tests and authored host doubles; no original host bytecode in the repository

No original or modified APK, Tencent assets, signing key, speech-service license or
full-device compatibility guarantee is included.
