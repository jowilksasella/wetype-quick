# Third-party notices

## Cangjie dictionary

Source: https://github.com/cangjie-system/rime-cangjie-integrated

Pinned revision: `dcfba770189078a2f7b347793c8e155c3c39275d`

File: `cangjie.5-base.dict.yaml`

Pinned link: https://github.com/cangjie-system/rime-cangjie-integrated/blob/dcfba770189078a2f7b347793c8e155c3c39275d/cangjie.5-base.dict.yaml

License: MIT, exact text in `vendor/cangjie-system/LICENSE`.

Copyright (c) 2018 - 朱邦復（發明）、RIME 倉頡輸入法集成（收集整理及修訂）

All rights reserved.

The upstream header describes the official fifth-generation table, sourced from the table included with Malaysia Friends of Cangjie's Cangjie Platform 2012, converted to Rime format and amended using available official information. That header is preserved in the original vendored file.

Changes made in this package: traditional/HKSCS filtering, first/last-stroke Quick code derivation, deduplication, default candidate ordering, and conversion to a runtime TSV. The exact original source is retained, and the offline transformation is in `scripts/build_dictionary.py`. Preserve this notice and the MIT license when distributing the derived dictionary.

## OpenCC character classification data

Source: https://github.com/BYVoid/OpenCC

Pinned revision: `3ac34aa439a9908dd49fa92b5174b46314787ac2`

File: `data/dictionary/STCharacters.txt`

Pinned link: https://github.com/BYVoid/OpenCC/blob/3ac34aa439a9908dd49fa92b5174b46314787ac2/data/dictionary/STCharacters.txt

License: Apache License, Version 2.0, exact text in `vendor/opencc/LICENSE` and licensing header in the unchanged data file. No top-level NOTICE file exists at this pinned revision.

The unchanged data is used only by the offline builder as a conservative exclusion list of non-identity S→T mappings. OpenCC also normalizes traditional variants; three individually reviewed exceptions (群, 峰, 晒) are retained using the original MIT Cangjie entries. This is not a claim that every OpenCC source key is simplified-only. No OpenCC code or runtime library is included. The derived output is a filtered Cangjie dictionary, not an OpenCC conversion implementation. Preserve the license and these attribution/modification notices with distributions of this source package and generated dictionary.

## New engine code

The new Java engine, tests, and builder in this directory are supplied under the MIT license in `LICENSE`. Third-party files retain their own licenses. The generated dictionary retains its source attribution and applicable license notices.

## Rime Quick reference

The first/last-stroke input convention was checked against https://github.com/rime/rime-quick/blob/5dcdb9e353d314239e9c8cddc0f42d52da4837bb/README.md and its schema. No Rime Quick or rime-cangjie GPL/LGPL dictionary data, schemas, or implementation code is included in this package. The official rime-cangjie base table was not chosen because its file header and repository license have differing GPL/LGPL labels; this package instead uses the clearly MIT-labeled Cangjie-system source above.

## Traditional/Cantonese phrase lexicon

`resources/quick-phrases.tsv` contains a filtered adaptation of the curated **Rime Cantonese** word list by the **Cantonese Computational Linguistics Infrastructure Development Workgroup (CanCLID) and contributors**.

- Project: https://github.com/rime/rime-cantonese
- Pinned revision: `259f0e48bba840c3a2e0d117539e96937f3d89bc`
- Source: https://github.com/rime/rime-cantonese/blob/259f0e48bba840c3a2e0d117539e96937f3d89bc/jyut6ping3.words.dict.yaml
- The source header identifies https://github.com/CanCLID/rime-cantonese-upstream as its maintained upstream.
- License: **Creative Commons Attribution 4.0 International (CC BY 4.0)**, https://creativecommons.org/licenses/by/4.0/
- Unmodified license: `vendor/rime-cantonese/LICENSE-CC-BY`
- Unmodified source and licensing statement: `vendor/rime-cantonese/jyut6ping3.words.dict.yaml` and `vendor/rime-cantonese/README-en.md`

Changes made here: discard pronunciation columns; restrict phrases to 2–8 Unicode codepoints and characters having a code in the shipped traditional/HKSCS Quick dictionary; remove duplicate phrase spellings; attach the exact-text overlap weights described below; serialize as a UTF-8 TSV. No simplified-to-traditional conversion, regional-variant replacement, new phrase generation, or pronunciation-to-shape-code conversion is performed. Every retained spelling is unchanged. Alternative Quick codes remain in the character dictionary and are matched at runtime without expanding phrase code combinations.

CC BY 4.0 requires retaining attribution, a license link/copy, and identification of modifications. It permits commercial redistribution and adaptation and has **no share-alike requirement**. Do not imply the source authors endorse this modified keyboard. The separately ODbL-licensed `jyut6ping3.maps` data is not included or used. The legacy unweighted `jyut6ping3.phrase.dict.yaml` and `essay-cantonese.txt` are also not included or used. In particular, the historical essay builder merged `rime-essay` data; the repository's general CC BY label must not be treated as evidence that those frequencies are a clean CC BY source.

## Phrase overlap weights and greeting supplement

Exact-text overlap counts, plus the common greeting `你好`, come from **OpenVanilla McBopomofo**:

- Project: https://github.com/openvanilla/McBopomofo
- Pinned revision: `be6564acad6c4d3265c34a2e1a872d80f9db6068`
- Source: https://github.com/openvanilla/McBopomofo/blob/be6564acad6c4d3265c34a2e1a872d80f9db6068/Source/Data/phrase.occ
- License: **MIT**, exact text in `vendor/mcbopomofo/LICENSE.txt`.
- Copyright (c) 2011-2026 Mengjuei Hsieh et al.
- Unmodified source: `vendor/mcbopomofo/Source/Data/phrase.occ`
- Unmodified project licensing statement and data description: `vendor/mcbopomofo/README.markdown` and `vendor/mcbopomofo/Source/Data/README.md`.

Only the occurrence-data file is used. No McBopomofo implementation, Bopomofo mapping file, libtabe table, conversion library, or corpus text is included. Three duplicate occurrence records are merged by taking the maximum, then counts are looked up by unchanged phrase text. A positive count is copied as the static integer weight; absent/zero counts receive neutral weight 1. The greeting supplement must exist in this licensed source and pass the same character/code and length checks; it is not a fabricated test-only phrase.

These are Taiwan-oriented Mandarin occurrence counts, not Hong Kong usage probabilities, and they do not provide a complete Cantonese frequency model. 33,077 shipped phrases have positive counts; 66,955 have neutral weight. Preserve this attribution and the exact MIT license when distributing the weighted lexicon. MIT has no share-alike requirement. The Rime-derived phrase portion retains its CC BY attribution obligations.

The complete reproducible transformation is `scripts/build_phrases.py`; source hashes, exclusions, code-path examples, and output hashes are recorded in `provenance.json` and `resources/phrase-dictionary-stats.json`. The source archive retains all source data needed for this build. Binary/APK distributions should include this notice, `LICENSE-CC-BY`, and the McBopomofo MIT license, in addition to the existing character-table notices.


## Reviewed traditional-character exceptions

The original conversion-based filter incorrectly excluded 群, 峰, and 晒 because OpenCC maps them to 羣, 峯, and 曬. A bounded reviewed allowlist in `resources/traditional-variant-allowlist.json` now preserves these three characters. All three still need to exist in the pinned MIT Cangjie table and Python Big5-HKSCS repertoire; their original codes are retained. The existing exclusions for clearly simplified characters such as 国 and 汉 remain in force. This change restores 390 unchanged curated phrase spellings, including 群組, 山峰, 晒命, and 唔該晒. It does not automatically replace characters or fabricate additional phrases.

Primary classification references, consulted 2026-10-05:

- Taiwan Ministry of Education, 群: https://dict.revised.moe.edu.tw/dictView.jsp?ID=6729&la=0&powerMode=0 and variant 羣: https://dict.revised.moe.edu.tw/dictView.jsp?ID=103808&la=1&powerMode=0
- Taiwan Ministry of Education, 峰: https://dict.revised.moe.edu.tw/dictView.jsp?ID=1736&word=%3D%E5%B3%B0 and variant 峯: https://dict.revised.moe.edu.tw/dictView.jsp?ID=37377&la=0&powerMode=0
- Chinese University of Hong Kong, 漢語多功能字庫, 晒: https://humanum.arts.cuhk.edu.hk/Lexis/lexi-mf/search.php?word=%E6%99%92

These websites are evidence for manual classification only. No dictionary definitions, pronunciations, or corpus data are copied into the assets. Character/code data still comes exclusively from the previously licensed Cangjie source. The allowlist is deliberately limited to these reviewed forms; the character filter is not an exhaustive linguistic classification of every Unicode traditional variant.
