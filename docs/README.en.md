# WeType Quick · Unofficial patch tools

[繁體中文](../README.md) · [简体中文](README.zh-CN.md) · English

Experimental source and patch tools that add Hong Kong-style Quick input to the
Android WeType keyboard. The new engine provides Traditional Chinese/Cantonese
words, continuous composition and local ranking, integrated with the host UI.

This project is unofficial and is not affiliated with or endorsed by Tencent or
WeType. Open-source licenses cover this repository's new code and separately
licensed data only, not the original app, trademarks, assets or speech services.

> v0.1.0 is the first source release of this project. The supported host is WeType 3.5.4.
> Releases contain no original or modified APK. Supply an exact original you are
> entitled to use, and perform the build locally.

## Introduction video

[![WeType Quick introduction video poster; click to play](media/poster.jpg)](media/intro.zh-Hant.mp4)

[Play / download](media/intro.zh-Hant.mp4) · [Traditional Chinese subtitles](media/intro.zh-Hant.srt) · [Simplified Chinese subtitles](media/intro.zh-Hans.srt) · [English subtitles](media/intro.en.srt)

An 85-second original illustrative walkthrough, not a phone recording or evidence
of full-app, privacy or voice validation on a physical device.

## Features

- First/last Cangjie-code Quick input: 17,213 characters and 100,032 phrases
- Continuous composition of up to 8 characters / 16 roots, with explicit separators
- Integration with the original full Chinese keyboard, candidate strip, expanded
  candidates and keyboard picker
- Roots appear in the keyboard's pending line; text is committed only after selection
- Single-character filtering, bounded candidate windows and local pin/unpin/hide actions
- Local learning, disabled according to effective password/no-personalized-learning fields
- The host's personal-dictionary clearing action also clears Quick records
- A request to prefer the built-in phone microphone when Bluetooth audio is already
  connected at the start of a voice-input session

Character forms and candidate ordering are not claimed to match Gboard or other keyboards.

## Get the source

Use the `v0.1.0` source/patch archive and `SHA256SUMS` in GitHub Releases, or download
that tag's source. An APK from a third party is not an endorsed project release.
The release also includes `wetype-quick-intro.zh-Hant.mp4` and subtitles in three
languages. Its illustrative screens are not a recording of a real phone or evidence
of device validation.

## Compatibility

- Exact original **3.5.4**, 224,668,031 bytes
- Original SHA-256: `c036f49444b3230fc2fc7c119ae79f32b33766374fd5dc6d48246dea378ff889`
- Local output is labeled `3.5.4-quick.0.1.0`, versionCode 57207, not an official Tencent release
- Added DEX targets API 24 minimum; this does not guarantee every Android 7+ device works
- Other versions, split APKs and repackaged originals fail the identity check

## Build locally

Requirements: Python 3.10+, Java 21, curl and sufficient disk space. Linux is tested;
Windows/macOS have not been exercised. Tools come from official projects/Maven repositories and are checked against
fixed SHA-256 hashes. The build never downloads the original APK. Initial dependency
setup requires internet access.

```sh
python3 scripts/build_local.py /absolute/path/to/original-3.5.4.apk
```

On native Windows, the intended command is `py -3 scripts/build_local.py "C:\path\original-3.5.4.apk"`. This route is untested on Windows and does not require WSL.

Output: `.local/build/wetype-quick-0.1.0-local-unsigned.apk`, plus local structural
reports. The tools never install, execute, upload or sign the host app, and never
create or store your signing key. See [BUILD.md](BUILD.md) for optional tests,
signing and installation. Do not bypass version/hash checks.

Modification, use and distribution may be restricted by applicable law and host
terms. A source-only patch release does not automatically grant those rights.

## Install and use

1. Read [building, signing and installation](BUILD.md); keep your own signing key secure.
2. Install your locally signed output through Android and enable it in system IME settings.
3. Select “速成” in the host keyboard/input-method picker. Other choices use the original backend.
4. Enter `enym` (水弓卜一); candidates include “測試”. Select a candidate to commit text.

A differently signed app cannot update Tencent's original, and the same package
name prevents side-by-side installation. Uninstalling may permanently delete app
data. Back up first; do not blindly uninstall to bypass a signature conflict.
Your key also cannot update an experimental build signed with someone else's key.

## Privacy, voice and limitations

Read the [full privacy notice](PRIVACY.en.md), including stored records, clearing and retained host data paths.

The new Quick engine has no networking implementation. Lookup, learning and
pin/hide records are local. Identified candidate/recommendation entry points are
intercepted, but the entire host's logging and network behavior has not been
audited. This is not a claim that the whole app is offline, uploads nothing, or
is suitable for sensitive input.

Voice follows the original implementation. This patch grants no speech-service
license. Tencent's original offline-ASR signature gates remain intact, so a new
signature may prevent protected offline resources from working. Android/OEMs can
ignore microphone preferences. Online/offline recognition, quality and actual
audio routing are not guaranteed.

The upward-clear Quick path deletes text before the cursor and preserves the
suffix. It refuses body deletion when absolute position or grapheme boundaries
cannot be established. It does not retain the deleted body or provide the host's
downward-swipe undo for that path. Full-app behavior, all host features, OEM audio
and each device still need physical-device testing.

See [TESTING.md](TESTING.md). Component and structural tests are not whole-device validation.

## Contributions and issues

Read [CONTRIBUTING.md](../CONTRIBUTING.md). Include project version, Android version,
device model and a reproduction without personal text. Do not upload APKs,
decompiled host code, private typing, keys, account information or unreviewed full logs.

## Voluntary support

If these open-source tools help you, voluntary support for development and maintenance
is welcome. Support is not a purchase or license for WeType and does not provide
Tencent's official services. No payment destination has been configured yet.

## Licenses and acknowledgements

New code uses [MIT](../LICENSE). Data has separate MIT, CC BY 4.0 and Apache 2.0
licenses. See [NOTICE.md](../NOTICE.md) and [full third-party notices](../quick-engine/THIRD_PARTY_NOTICES.md)
for sources, modifications and exact terms. Thanks to Cangjie-system, CanCLID /
Rime Cantonese, OpenVanilla / McBopomofo, OpenCC and their contributors.
