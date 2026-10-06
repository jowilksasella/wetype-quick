#!/usr/bin/env python3
"""Portable source-only core/storage/audio tests; no host APK is needed."""
import os, shutil, subprocess, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
TOOLS=Path(os.environ.get('WETYPE_TOOLS', str(ROOT/'.local/tools'))).resolve()
os.environ['PYTHONUTF8']='1'
def run(*args,cwd=ROOT):
    subprocess.run([str(a) for a in args],cwd=cwd,check=True)
def compile(sources,out,classpath=None,cwd=ROOT):
    if out.exists(): shutil.rmtree(out)
    out.mkdir(parents=True,exist_ok=True)
    args=['java','-jar',TOOLS/'ecj.jar','-encoding','UTF-8','-source','8','-target','8']
    if classpath:args+=['-cp',classpath]
    # Path objects are passed as arguments, not shell text. Spaces are supported.
    run(*args,'-d',out,*sorted(sources),cwd=cwd)
def main():
    engine=ROOT/'quick-engine';build=ROOT/'.local/tests'
    compile(list((engine/'src').rglob('*.java')),build/'core')
    run('java','-cp',build/'core','org.wetype.quick.QuickEngineTest',cwd=engine)
    run('java','-cp',build/'core','org.wetype.quick.CompositionEngineTest',cwd=engine)
    before=(engine/'resources/quick-traditional.tsv').read_bytes()
    run(sys.executable,'-X','utf8','scripts/build_dictionary.py',cwd=engine)
    if (engine/'resources/quick-traditional.tsv').read_bytes()!=before:raise RuntimeError('Character dictionary is not reproducible')
    run(sys.executable,'-X','utf8','scripts/build_phrases.py','--check',cwd=engine)
    sources=list((engine/'src/main/java').rglob('*.java'))+list((ROOT/'native-integration/local-action-tests/src').rglob('*.java'))
    sources.append(ROOT/'native-integration/src/org/wetype/quick/integration/QuickLocalActions.java')
    compile(sources,build/'local/classes')
    run('java','-cp',build/'local/classes','org.wetype.quick.integration.QuickLocalActionsTest')
    compile([ROOT/'native-integration/src/org/wetype/quick/audio/VoiceMicrophoneRoute.java'],build/'audio/production',str(TOOLS/'android-all.jar'))
    compile(list((ROOT/'native-integration/audio-tests/src').rglob('*.java')),build/'audio/tests',str(build/'audio/production'))
    run('java','-cp',os.pathsep.join(str(build/x) for x in ['audio/tests','audio/production']),'org.wetype.quick.audio.VoiceMicrophoneRouteTest')
    run(sys.executable,'-X','utf8','-m','unittest','discover','-s','tests','-p','test_*.py')
if __name__=='__main__':main()
