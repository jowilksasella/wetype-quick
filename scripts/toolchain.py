#!/usr/bin/env python3
"""Fetch or verify hash-pinned tools. Never fetches an original APK or credentials."""
import argparse, hashlib, json, os, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
TOOLS = Path(os.environ.get('WETYPE_TOOLS', str(ROOT / '.local/tools'))).resolve()
def verify(path, expected):
    if not path.is_file(): raise SystemExit('Missing tool: ' + path.name + '; run scripts/toolchain.py fetch')
    h = hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda:f.read(1048576), b''): h.update(b)
    if h.hexdigest() != expected: raise SystemExit('SHA-256 mismatch: ' + path.name)
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['fetch','verify'])
    args=parser.parse_args(); TOOLS.mkdir(parents=True,exist_ok=True)
    for name, spec in json.loads((ROOT/'toolchain.json').read_text()).items():
        path=TOOLS/name
        if args.action=='fetch' and not path.exists():
            tmp=path.with_suffix('.download')
            subprocess.run(['curl','--fail','--location','--proto','=https','--proto-redir','=https',
                            '--max-time','600',spec['url'],'-o',str(tmp)],check=True)
            verify(tmp,spec['sha256']);tmp.replace(path)
        verify(path,spec['sha256']);print('Verified:',name)
if __name__=='__main__':main()
