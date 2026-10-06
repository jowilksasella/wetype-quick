#!/usr/bin/env python3
"""Create an allowlisted source ZIP. Never recursively archives the workspace."""
import argparse, hashlib, json, re, zipfile
from pathlib import Path, PurePosixPath
ROOT=Path(__file__).resolve().parents[1]
FORBIDDEN={'.apk','.apkm','.apks','.dex','.jar','.aar','.class','.so','.jks','.keystore','.key','.pem','.p12','.pfx','.pyc','.oat','.art','.vdex','.odex'}
BAD_PARTS={'.git','.local','build','decoded','private','__pycache__','dependencies','original-decoded'}
# Only these independently reviewed original media bytes may be binary.
MEDIA_HASHES={'docs/media/intro.zh-Hant.mp4': 'ced1ac8799362ebd6e1875f13471cdceb3db4228edb0de99a786205226f09082', 'docs/media/poster.jpg': 'adc10d81d92baaeab616bd7dd8a6c1fb1bef421ef56943dde6c50817ac7be0f0'}
PRIVATE_PATTERNS=[rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',rb'https?://drive\.google\.com/',rb'/workspace/',rb'/root/',rb'/home/[^\s]+/',rb'/Users/[^\s]+/',rb'gh[pousr]_[A-Za-z0-9]{30,}',rb'github_pat_[A-Za-z0-9_]{40,}']
def select():
    lines=(ROOT/'public-files.txt').read_text().splitlines()
    if len(lines)!=len(set(lines)):raise ValueError('Duplicate allowlist entry')
    selected={}
    for name in lines:
        p=PurePosixPath(name)
        if not name or p.is_absolute() or '..' in p.parts or any(x in BAD_PARTS for x in p.parts):raise ValueError('Unsafe public path: '+name)
        f=ROOT/name
        if f.is_symlink() or not f.is_file() or f.resolve()!=f.absolute():raise ValueError('Missing or indirect source: '+name)
        if f.suffix.lower() in FORBIDDEN:raise ValueError('Binary/private type in public list: '+name)
        data=f.read_bytes()
        # The scanner's own literal detection patterns are not private data.
        if name!='scripts/package_release.py':
            for rule in PRIVATE_PATTERNS:
                if re.search(rule,data):raise ValueError('Private-data marker in '+name)
        if name in MEDIA_HASHES:
            if hashlib.sha256(data).hexdigest()!=MEDIA_HASHES[name]:raise ValueError('Unreviewed media bytes: '+name)
        else:
            if b'\0' in data:raise ValueError('Unexpected binary file: '+name)
            data.decode('utf-8')
        selected[name]=data
    required={'README.md','docs/README.zh-CN.md','docs/README.en.md','LICENSE','NOTICE.md','public-files.txt'}
    if not required<=selected.keys():raise ValueError('Required source/document missing')
    return selected

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=ROOT/'.local/release')
    parser.add_argument('--check-only',action='store_true')
    args=parser.parse_args();files=select()
    if args.check_only:print('PASS:',len(files),'allowlisted source/media files; only hash-reviewed MP4/JPEG binaries permitted');return
    out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
    name='wetype-quick-v0.1.0-source.zip';prefix='wetype-quick-0.1.0/'
    hashes={n:hashlib.sha256(b).hexdigest() for n,b in sorted(files.items())}
    files['SOURCE_SHA256.json']=(json.dumps(hashes,ensure_ascii=False,indent=2)+'\n').encode()
    with zipfile.ZipFile(out/name,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
        for n,b in sorted(files.items()):
            info=zipfile.ZipInfo(prefix+n,(2026,10,5,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
            executable=n.endswith(('.sh','.py')) and b.startswith(b'#!')
            info.external_attr=(0o100755 if executable else 0o100644)<<16
            archive.writestr(info,b)
    with zipfile.ZipFile(out/name) as archive:
        if archive.testzip() is not None:raise ValueError('ZIP integrity failure')
        if set(archive.namelist())!={prefix+n for n in files}:raise ValueError('Archive entry mismatch')
        for n,b in files.items():
            if archive.read(prefix+n)!=b:raise ValueError('Archived source changed: '+n)
    digest=hashlib.sha256((out/name).read_bytes()).hexdigest()
    (out/'SHA256SUMS').write_text(digest+'  '+name+'\n')
    print(json.dumps({'archive':str(out/name),'bytes':(out/name).stat().st_size,'sha256':digest,'entries':len(files)},indent=2))
if __name__=='__main__':main()
