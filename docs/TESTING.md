# 測試範圍 / Validation scope

Validation recorded on 2026-10-05 for the v0.1.0 source-only preparation.
The tests below were run against the public source copy, not merely copied from an
earlier private report. No physical phone or microphone was used.

## Source-only checks

`python3 scripts/test_local.py` (also available through `bash scripts/test.sh`) passed:

- Quick dictionary/engine: 58,750 assertions
- Continuous composition/phrase engine: 4,380 assertions
- Deterministic character-table rebuild and phrase-table check
- Local pin/hide storage: 133 assertions
- Microphone preference helper: 11 authored Android-audio-double tests
- Build/source guards: 6 Python tests, including wrong-input rejection and source-data pins

`bash scripts/test-host.sh` passed:

- 53 controller cases using real Robolectric Android 16 text/InputConnection behavior
  and authored service/model/key/data doubles
- 5 keyboard-picker adapter cases using Android views and authored host doubles

The public controller suite no longer relies on translated original host bytecode.
This makes the tests redistributable as authored fixtures, but limits the evidence:
it tests adapter contracts, not the proprietary host implementations themselves.

Coverage includes composition/segmentation, bounded phrase paging, first-character
continuation, commit-only learning, password/no-personalized-learning transitions,
reentrant/stale callbacks, mode/lifecycle changes, prefix-clear Unicode safety,
local history recovery/clearing, picker duplication, and audio preference cleanup.

## User-local build checks

A clean build from the exact original 3.5.4 passed. It did not use the old modified
decoded baseline. The two patched host modules match Q5r2. The added Quick module includes the new
already-private transient-cache cleanup and has a new hash:

- classes2.dex: `5c414d01d1410af168e9f873eb25d190a650e8c386d8009aed0b060925854ddf`
- classes3.dex: `f5f9e553fb74f6469e80267abf6eba8a98f7aeeed98dd0c039c3f660ff7ed14d`
- added Quick DEX: `7740e3c093f385f11ddf04360e91017b2f523a32babb9a5f31516512e25bad86`

The release also has source-specific app-label/version metadata. Eight additional
core assertions and a controller regression cover clearing an already-private
query plan at finish and the next start, without erasing saved learning.
The build checked 16,524 staged original classes, with 26 intended class changes;
all remaining original classes were unchanged. Original native libraries/resources
were byte-preserved. All 30 injected method signatures and 2 field signatures
resolved against the actual added DEX. No compile/test stubs leaked into it.
The 10 identified ASR gate classes were re-decoded from the built package and matched
the original. Stored native libraries retained 16 KiB ZIP alignment.

Fresh actual ART 33.10 and 36.0 verification was run against the final local unsigned
APK after the cache fix. Both returned successfully with zero hard class rejections;
the soft-failure method sets matched the respective original baseline (105 on ART33,
104 on ART36). Each verified 30,179 classes. These are DEX-verifier results, not a
running Android app: framework stubs establish type relationships; component
lifecycle, resources, JNI and physical ARM64/device behavior are not executed.

The public build does not bundle an ART runtime or run ART automatically. Its normal
checks are the structural validations above. The final local unsigned APK SHA-256
was `505381c44301755d8c9edf4fc0f7d3698c1081cd1573a97e1b8a6b41cd54ff73`; the APK itself is deliberately not released.


## Not established

- Full proprietary IME startup, JNI/engine behavior and every native observer
- Every Android version, OEM, screen configuration or app/editor
- Physical microphone route, Bluetooth timing, recognition quality, online/offline ASR availability
- End-to-end host privacy/security audit or absence of data transmission
- Legal permission to modify, use or redistribute proprietary host material

Compiler deprecation/unchecked warnings remain; tests passed despite those warnings.
The source release contains the reproducible tests, not raw logs with local paths,
original UI screenshots, proprietary bytecode, APKs, signing material or dependency binaries.
