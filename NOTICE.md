# Scope of this source release

The root MIT license covers the newly authored engine, adapters, patch generators,
build utilities, tests, and documentation in this repository, except separately
licensed third-party files and data. The pre-existing engine copyright notice is
retained in `quick-engine/LICENSE`.

The original WeType / 微信輸入法 application, Tencent implementation, assets,
trademarks, native libraries, speech recognition service and models are not licensed
by this repository. No original or modified host APK is distributed here.
Host class names, method signatures, field names and small instruction anchors are
used to identify version-pinned interoperability points. The compile-only host
stubs are declarations/test seams, not an open-source replacement for the host.
This technical packaging boundary is not a legal opinion or a guarantee that all
forms of modification, use or redistribution are permitted in every jurisdiction.

The additive smali fragments contain newly authored publication/invalidation
methods. Generators read and modify the user's local original at build time; their
outputs contain original host code and must not be added to this source repository.

## Separately licensed data

See [complete attribution and modifications](quick-engine/THIRD_PARTY_NOTICES.md),
[pinned input provenance](quick-engine/provenance.json), and the exact license files:

- Cangjie-system character table: MIT (`quick-engine/vendor/cangjie-system/LICENSE`).
- Rime Cantonese phrase spellings: CC BY 4.0
  (`quick-engine/vendor/rime-cantonese/LICENSE-CC-BY`).
- McBopomofo exact-text occurrence weights/greeting supplement: MIT
  (`quick-engine/vendor/mcbopomofo/LICENSE.txt`).
- OpenCC offline classification data: Apache 2.0
  (`quick-engine/vendor/opencc/LICENSE`).

Generated dictionaries retain their source-specific attribution and applicable
licenses. The root MIT file does not relicense the CC BY data or OpenCC source.
Upstream names and necessary attribution stay intact; no upstream endorsement is
claimed.

## Build/test dependencies

Tools are fetched separately from official projects or Maven repositories and are
not part of this source archive. Apktool uses Apache 2.0; Eclipse ECJ uses EPL 2.0;
R8 uses BSD-3-Clause and includes third-party notices; Robolectric uses MIT and its
Android framework artifacts include Android/AOSP licensing. Maven-resolved test
transitives retain their own licenses. Read the dependency distributions' notices
before redistributing those tools. This project does not bundle their binaries.

## Original introduction media

The original explanatory video, poster and three subtitle tracks are included in
`docs/media/`. They use the same scoped MIT license; retain `docs/media/LICENSE`
and `docs/media/NOTICE.txt`. They contain illustrative diagrams, not copied Tencent
UI screenshots. No font binaries or third-party runtime tools are bundled.
