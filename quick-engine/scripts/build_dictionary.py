#!/usr/bin/env python3
"""Offline, reproducible MIT Cangjie -> traditional Quick dictionary build."""
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# Hand-authored convenience priorities, not a downloaded corpus or Gboard ranking.
COMMON = '的一是在不了有和人這中大為上個國我以要他時來用們生到作地於出就分對成會可主發年動同工也能下過子說產種面而方後多定行學法所民得經之進著等部度家電力裡如水化高自二理起小物現實加量都兩體制機當使點從業本去把性好應開它合還因由其些然前外天政四日那社義事平形相全表間樣與關各重新線內數正心反你明看原又麼利比或但質氣第向道命此變條只沒結解問意建月公無系軍很情者最立代想已通並提直題程展果料象員位入常文總次品式活設及管特件長求老頭基資邊流路級少圖山統接知較將組見計別她手角期根論運農指幾九區強放決西被幹做必戰先回則任取據處隊南給色光門即保治北造百規熱領七海口東導器壓志世金增爭濟階油思術極交受聯什認六共權收證改清美再採轉更單風切打白教速花帶安場身車例真務具萬每目至達走積示議聲報鬥完類八離華名確才科張信馬節話米整空元況今集溫傳土許步群廣石記需段研界拉林律叫且究觀越織裝影算低持音眾書布復容兒須際商非驗連斷深難近礦千週委素技備半辦青省列習響約支般史感勞便團往酸歷市克何除消構府稱太準精值號率族維劃選標寫存候毛親快效院查江型眼王按格養易置派層片始卻專狀育廠京識適屬圓包火住調滿縣局照參紅細引聽該鐵價嚴龍飛香港愛謝請臺灣嗎呢啊吧吃喝睡買賣錢早晚歡喜樂朋友試輸簡倉頡速成繁體字'


def main():
    source = ROOT / 'vendor/cangjie-system/cangjie.5-base.dict.yaml'
    simp_source = ROOT / 'vendor/opencc/data/dictionary/STCharacters.txt'
    # OpenCC S->T includes traditional variant normalization; it is not a
    # definitive simplified-only classifier. Keep only individually reviewed
    # exceptions, still subject to upstream-code and Big5-HKSCS checks.
    allowlist_source = ROOT / 'resources/traditional-variant-allowlist.json'
    allowlist_data = json.loads(allowlist_source.read_text(encoding='utf-8'))
    allowlist = {entry['character'] for entry in allowlist_data['entries']}
    if len(allowlist) != len(allowlist_data['entries']) or any(len(c) != 1 for c in allowlist):
        raise ValueError('Malformed traditional variant allowlist')
    non_identity_candidates = set()
    for line in simp_source.read_text(encoding='utf-8').splitlines():
        if not line or line.startswith('#'):
            continue
        key, values = line.split('\t')
        if key not in values.split():
            non_identity_candidates.add(key)
    if not allowlist.issubset(non_identity_candidates):
        raise ValueError('Allowlist must override actual non-identity OpenCC mappings')
    priorities = {}
    for c in COMMON:
        if c not in priorities:
            priorities[c] = len(priorities)
    source_lines = source.read_text(encoding='utf-8').splitlines()
    source_lines = source_lines[source_lines.index('...') + 1:]
    records = {}
    excluded = {'non_hkscs': 0, 'opencc_non_identity_not_allowlisted': 0, 'non_character_or_code': 0, 'duplicates': 0}
    for order, line in enumerate(source_lines):
        if not line or line.startswith('#'):
            continue
        cells = line.split('\t')
        if len(cells) < 2 or not re.fullmatch('[a-y][a-z]{0,4}', cells[0]) or len(cells[1]) != 1:
            excluded['non_character_or_code'] += 1
            continue
        full, char = cells[:2]
        if char in non_identity_candidates and char not in allowlist:
            excluded['opencc_non_identity_not_allowlisted'] += 1
            continue
        try:
            char.encode('big5hkscs')
        except UnicodeEncodeError:
            excluded['non_hkscs'] += 1
            continue
        quick = full if len(full) == 1 else full[0] + full[-1]
        rank = priorities.get(char, 10000 + order)
        key = (quick, char)
        if key in records:
            excluded['duplicates'] += 1
            continue
        records[key] = (rank, full)
    retained_variants = {char for _, char in records if char in allowlist}
    if retained_variants != allowlist:
        raise ValueError('Reviewed traditional variant lacks an eligible upstream code')
    out = ['# quick-traditional-v1: code<TAB>character<TAB>base_rank<TAB>full_cangjie',
           '# Derived from cangjie-system/rime-cangjie-integrated (MIT), filtered with OpenCC (Apache-2.0).',
           '# Reviewed traditional variants preserved: 群 峰 晒; see traditional-variant-allowlist.json.',
           '# See THIRD_PARTY_NOTICES.md and vendor/ for sources and unmodified notices.']
    for (code, char), (rank, full) in sorted(records.items(), key=lambda item: (item[0][0], item[1][0], item[0][1])):
        out.append(f'{code}\t{char}\t{rank}\t{full}')
    target = ROOT / 'resources/quick-traditional.tsv'
    target.write_text('\n'.join(out) + '\n', encoding='utf-8')
    stats = {'entries': len(records), 'characters': len({k[1] for k in records}),
             'quick_codes': len({k[0] for k in records}), 'excluded': excluded,
             'reviewed_traditional_variants': sorted(allowlist),
             'retained_variant_entries': sum(char in allowlist for _, char in records),
             'variant_allowlist_sha256': hashlib.sha256(allowlist_source.read_bytes()).hexdigest(),
             'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
             'filter_sha256': hashlib.sha256(simp_source.read_bytes()).hexdigest(),
             'output_sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
             'output_bytes': target.stat().st_size}
    (ROOT / 'resources/dictionary-stats.json').write_text(json.dumps(stats, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(stats, indent=2))


if __name__ == '__main__':
    main()
