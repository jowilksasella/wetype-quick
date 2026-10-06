#!/usr/bin/env python3
"""Reproducibly build the offline, traditional Quick phrase lexicon.

No downloads, third-party code execution, script conversion, or code Cartesian
products occur here. Higher integer weight means earlier static ranking.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
PINNED = {
    'vendor/rime-cantonese/jyut6ping3.words.dict.yaml':
        '54d174ad2bb997e4a678b7b076b84e4dc5914481b32467cdea3b43e88b7d5474',
    'vendor/mcbopomofo/Source/Data/phrase.occ':
        'dcd11597090b2bef88fb385bdd567e235ccd916728c84b8d0d3f8069ccfc7b8d',
}
# The requested common greeting is absent from the curated Cantonese word table.
# It is included only if it exists, unchanged, in the second licensed source.
REQUIRED_SUPPLEMENTS = ('你好',)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def build():
    for name, digest in PINNED.items():
        if sha256((ROOT / name).read_bytes()) != digest:
            raise ValueError('Source hash mismatch: ' + name)
    chars = defaultdict(set)
    char_data = (ROOT / 'resources/quick-traditional.tsv').read_bytes()
    for line in char_data.decode('utf-8').splitlines():
        if not line or line.startswith('#'):
            continue
        code, char, rank, full = line.split('\t')
        if not re.fullmatch('[a-y]{1,2}', code) or len(char) != 1:
            raise ValueError('Invalid character dictionary entry')
        chars[char].add(code)

    counts = {}
    duplicate_occurrence_records = 0
    occurrence_data = ROOT / 'vendor/mcbopomofo/Source/Data/phrase.occ'
    for line in occurrence_data.read_text(encoding='utf-8').splitlines():
        if not line or line.startswith('#'):
            continue
        phrase, number = line.split()
        if not re.fullmatch('[0-9]+', number):
            raise ValueError('Invalid occurrence count: ' + line)
        weight = int(number)
        if weight > 2147483647:
            raise ValueError('Occurrence count exceeds Java int')
        if phrase in counts:
            duplicate_occurrence_records += 1
        counts[phrase] = max(weight, counts.get(phrase, 0))

    words_source = ROOT / 'vendor/rime-cantonese/jyut6ping3.words.dict.yaml'
    lines = words_source.read_text(encoding='utf-8').splitlines()
    lines = lines[lines.index('...') + 1:]
    words = set()
    excluded = Counter({'outside_2_8_codepoints': 0,
                        'character_not_in_quick_dictionary': 0,
                        'duplicate_pronunciation_record': 0})
    source_records = 0
    unsupported = Counter()
    for line in lines:
        if not line or line.startswith('#'):
            continue
        fields = line.split('\t')
        if len(fields) < 2:
            raise ValueError('Missing pronunciation: ' + line)
        phrase = fields[0]
        source_records += 1
        if not 2 <= len(phrase) <= 8:
            excluded['outside_2_8_codepoints'] += 1
            continue
        missing = {c for c in phrase if c not in chars}
        if missing:
            excluded['character_not_in_quick_dictionary'] += 1
            unsupported.update(missing)
            continue
        if phrase in words:
            excluded['duplicate_pronunciation_record'] += 1
        words.add(phrase)
    curated_words = len(words)
    for phrase in REQUIRED_SUPPLEMENTS:
        if phrase not in counts or not 2 <= len(phrase) <= 8 or any(c not in chars for c in phrase):
            raise ValueError('Supplement lacks licensed source or Quick path: ' + phrase)
        words.add(phrase)

    weights = {word: max(1, counts.get(word, 0)) for word in words}
    output = [
        '# quick-phrases-v1: phrase<TAB>integer_weight (higher ranks earlier)',
        '# Curated Rime Cantonese words: CanCLID and contributors, CC BY 4.0.',
        '# Overlap weights and the greeting 你好: McBopomofo, MIT; see notices.',
        '# Filtered without script conversion to 2-8 Unicode codepoints in the shipped Quick dictionary.',
        '# Missing/zero occurrence counts use neutral weight 1, not a measured frequency.',
        '# See THIRD_PARTY_NOTICES.md, provenance.json, and vendor/ for exact sources and licenses.',
    ]
    output.extend(f'{word}\t{weight}' for word, weight in sorted(weights.items(), key=lambda item: (-item[1], item[0])))
    output_data = ('\n'.join(output) + '\n').encode('utf-8')
    samples = {}
    for phrase in ('測試', '香港', '你好', '唔該', '多謝', '返工', '食飯', '的士', '巴士', '港鐵', '八達通', '冇問題', '輸入法', '群組', '山峰', '晒冷', '晒命', '唔該晒'):
        if phrase not in words:
            raise ValueError('Expected coverage missing: ' + phrase)
        samples[phrase] = {'weight': weights[phrase], 'character_quick_codes': [sorted(chars[c]) for c in phrase]}
    stats = {
        'entries': len(words), 'curated_entries': curated_words,
        'source_records': source_records, 'excluded_source_records': dict(excluded),
        'duplicate_occurrence_records_max_merged': duplicate_occurrence_records,
        'supplements': list(REQUIRED_SUPPLEMENTS),
        'length_counts': {str(k): v for k, v in sorted(Counter(map(len, words)).items())},
        'phrases_with_positive_occurrence_count': sum(counts.get(word, 0) > 0 for word in words),
        'neutral_weight_phrases': sum(counts.get(word, 0) <= 0 for word in words),
        'weight_semantics': 'Higher is earlier; McBopomofo occurrence count when positive, otherwise neutral 1. Not Hong Kong usage probabilities.',
        'max_weight': max(weights.values()),
        'unique_characters': len(set(''.join(words))),
        'phrases_containing_reviewed_variants': sum(any(c in '群峰晒' for c in word) for word in words),
        'unsupported_character_examples': dict(unsupported.most_common(20)),
        'source_sha256': PINNED,
        'character_dictionary_sha256': sha256(char_data),
        'output_sha256': sha256(output_data), 'output_bytes': len(output_data),
        'sample_coverage': samples,
        'limitations': ['Curated finite lexicon; arbitrary unseen phrases are not invented.',
                       'Some Hong Kong forms are excluded by the existing traditional/HKSCS character repertoire.',
                       'Weights come from a Taiwan-oriented Mandarin source on exact-text overlap; most Cantonese-only phrases have neutral weight.',
                       'No script normalization, transliteration, surrounding-text modeling, or keystroke transmission.'],
    }
    return output_data, (json.dumps(stats, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Verify checked-in output without writing')
    args = parser.parse_args()
    data, stats = build()
    for name, content in [('quick-phrases.tsv', data), ('phrase-dictionary-stats.json', stats)]:
        path = ROOT / 'resources' / name
        if args.check:
            if not path.exists() or path.read_bytes() != content:
                raise SystemExit('Not reproducible: ' + str(path))
        else:
            path.write_bytes(content)
    print(stats.decode('utf-8'))


if __name__ == '__main__':
    main()
