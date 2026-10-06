#!/usr/bin/env python3
"""Computer-only local patch workflow. Requires Python 3.10+, Java 21 and curl."""
import argparse, os, re, shutil, subprocess, sys, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def run(*args):
    subprocess.run([str(a) for a in args],cwd=ROOT,check=True)
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('original',type=Path,help='Exact supported original 3.5.4 APK, supplied by you')
    args=parser.parse_args()
    os.environ['PYTHONUTF8']='1'
    os.environ['WETYPE_ORIGINAL_APK']=str(args.original.resolve())
    tools=Path(os.environ.get('WETYPE_TOOLS',str(ROOT/'.local/tools'))).resolve()
    os.environ['WETYPE_TOOLS']=str(tools)
    # Fail closed on a wrong original before downloading tools.
    native=ROOT/'native-integration/native_build.py'
    run(sys.executable,'-X','utf8',native,'preflight')
    run(sys.executable,'-X','utf8',ROOT/'scripts/toolchain.py','fetch')
    run(sys.executable,'-X','utf8',ROOT/'scripts/test_local.py')
    run(sys.executable,'-X','utf8',native,'stage')
    b=ROOT/'.local/build'
    # These are dedicated generated output paths. Never delete user input or keys.
    for name in ['tools','compile/stubs','compile/classes','dex']:
        p=b/name
        if p.exists():shutil.rmtree(p)
        p.mkdir(parents=True)
    ecj=['java','-jar',tools/'ecj.jar','-encoding','UTF-8','-source','8','-target','8']
    run(*ecj,'-cp',tools/'apktool.jar','-d',b/'tools',ROOT/'patch/tools/AssembleDex.java')
    for module in ['2','3']:
        run('java','-cp',os.pathsep.join(map(str,[tools/'apktool.jar',b/'tools'])),'AssembleDex',b/f'stage/smali_classes{module}',b/f'classes{module}.dex')
    stubs=[]
    for folder in ['stubs','keyboard-stubs','controller-stubs']:
        stubs.extend((ROOT/'native-integration'/folder).rglob('*.java'))
    run(*ecj,'-cp',tools/'android-all.jar','-d',b/'compile/stubs',*sorted(stubs))
    sources=list((ROOT/'native-integration/src').rglob('*.java'))+list((ROOT/'quick-engine/src/main/java').rglob('*.java'))
    run(*ecj,'-cp',os.pathsep.join(map(str,[tools/'android-all.jar',b/'compile/stubs'])),'-d',b/'compile/classes',*sorted(sources))
    with zipfile.ZipFile(b/'compile/native-quick.jar','w') as z:
        for p in sorted((b/'compile/classes').rglob('*.class')):
            info=zipfile.ZipInfo(p.relative_to(b/'compile/classes').as_posix(),(2026,10,5,0,0,0));info.external_attr=0o100644<<16
            z.writestr(info,p.read_bytes())
    result=subprocess.run(['java','-XshowSettings:properties','-version'],capture_output=True,text=True,check=True)
    match=re.search(r'^\s*java.home = (.+)$',result.stdout+result.stderr,re.M)
    if not match:raise RuntimeError('Cannot determine the Java runtime home')
    run('java','-cp',tools/'r8.jar','com.android.tools.r8.D8','--release','--min-api','24','--lib',tools/'android-all.jar','--lib',match.group(1).strip(),'--output',b/'dex',b/'compile/native-quick.jar')
    for action in ['check-sources','package','verify-unsigned']:
        run(sys.executable,'-X','utf8',native,action)
    print('Verified local unsigned output:',b/'wetype-quick-0.1.0-local-unsigned.apk')
    print('No signing, installation, app execution or upload was performed.')
if __name__=='__main__':main()
