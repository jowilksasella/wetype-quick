#!/usr/bin/env python3
"""Local user-supplied-APK build with fail-closed identity and structural checks."""
import argparse
import os
import copy
import hashlib
import importlib.util
import json
import re
import shutil
import struct
import subprocess
import sys
import zipfile
import zlib
from pathlib import Path

BASE = Path(__file__).resolve().parents[1]
BUILD = BASE / '.local/build'
TOOLS = Path(os.environ.get('WETYPE_TOOLS', str(BASE / '.local/tools'))).resolve()
ORIGINAL = Path(os.environ.get('WETYPE_ORIGINAL_APK', str(BASE / '.local/original.apk'))).resolve()
APK_SHA = 'c036f49444b3230fc2fc7c119ae79f32b33766374fd5dc6d48246dea378ff889'
PREFIX = 'wetype-quick-0.1.0-local'
RELEASE = 'local'
SERVICE = 'smali_classes2/com/tencent/wetype/plugin/hld/WxHldService.smali'
MODEL = 'smali_classes3/com/tencent/wetype/plugin/hld/model/i0.smali'
GATES = [
    'smali_classes3/com/tencent/wetype/plugin/hld/utils/A0.smali',
    'smali_classes3/com/tencent/wetype/plugin/hld/rescenter/o.smali',
]
KEYBOARD_PINS = {
    'smali_classes2/com/tencent/wetype/plugin/hld/keyboard/selfdraw/drawmethod/e.smali': 'fb3887a7788674ffc6a45a5dfa02041f0a251d35c7c198cfcc2e8e4fa9a96237',
    'smali_classes2/com/tencent/wetype/plugin/hld/keyboard/selfdraw/drawmethod/i.smali': 'a61cfb8c887401a4f335049d549fd8cc0bf238bdd682ba139c2a9c9cd938ea34',
    'smali_classes3/com/tencent/wetype/plugin/hld/view/settingkeyboard/S10SettingKeyboardTypeView.smali': 'efe800f661f310ef90115d6ca412d10b6a32b525770626683a79db1644113c51',
}
ASSETS = {
    'quick-phrases.tsv': 'quick-engine/resources/quick-phrases.tsv',
    'quick-traditional.tsv': 'quick-engine/resources/quick-traditional.tsv',
    'LICENSE-quick-engine-MIT.txt': 'quick-engine/LICENSE',
    'NOTICE.txt': 'quick-engine/THIRD_PARTY_NOTICES.md',
    'LICENSE-cangjie-MIT.txt': 'quick-engine/vendor/cangjie-system/LICENSE',
    'LICENSE-rime-cantonese-CC-BY-4.0.txt': 'quick-engine/vendor/rime-cantonese/LICENSE-CC-BY',
    'LICENSE-mcbopomofo-MIT.txt': 'quick-engine/vendor/mcbopomofo/LICENSE.txt',
    'LICENSE-opencc-Apache-2.0.txt': 'quick-engine/vendor/opencc/LICENSE',
}
GENERATORS = ['patch_keyboard_port.py', 'patch_controller_host.py',
              'patch_candidate_controller.py', 'patch_microphone_route.py']


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def file_sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def save(name, value):
    (BUILD / name).write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + '\n')


def run(*args):
    subprocess.run([str(a) for a in args], cwd=BASE, check=True)


def hashes(root):
    return {p.relative_to(root).as_posix(): file_sha(p) for p in sorted(root.rglob('*.smali'))}


def tree_hash(mapping):
    return sha(json.dumps(mapping, sort_keys=True, separators=(',', ':')).encode())


def clean(path):
    path = path.resolve()
    require(path.is_relative_to(BUILD) and path != BUILD, 'Unsafe build cleanup target')
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True)


def validate_input_location():
    build = BUILD.resolve()
    require(build.is_relative_to(BASE) and build != BASE, 'Build output must remain inside this source folder')
    require(not ORIGINAL.resolve().is_relative_to(build),
            'Original APK must be outside the build output directory; move your input elsewhere')


def preflight():
    validate_input_location()
    require(ORIGINAL.is_file(), 'Original APK not found; supply the exact supported original')
    require(file_sha(ORIGINAL) == APK_SHA, 'Unrecognized original APK')
    require(ORIGINAL.stat().st_size == 224668031, 'Original APK size mismatch')
    BUILD.mkdir(parents=True, exist_ok=True)


def input_manifest():
    paths = set()
    for folder in ['native-integration/src', 'native-integration/stubs',
                   'native-integration/keyboard-stubs', 'native-integration/controller-stubs',
                   'quick-engine/src/main/java']:
        paths.update((BASE / folder).rglob('*.java'))
    paths.update((BASE / 'native-integration').glob('*.py'))
    paths.update((BASE / 'native-integration/patches').glob('*.py'))
    paths.update((BASE / 'native-integration/patches').glob('*.smali'))
    paths.update((BASE / 'scripts').glob('*'))
    paths.update(BASE / p for p in ASSETS.values())
    paths.update(BASE / p for p in ['patch/mark_manifest.py', 'patch/tools/AssembleDex.java', 'toolchain.json'])
    result = {p.relative_to(BASE).as_posix(): file_sha(p) for p in sorted(paths) if p.is_file()}
    dependencies = {p.name: file_sha(p) for p in sorted(TOOLS.glob('*.jar'))}
    return {'format': 1, 'original_apk_sha256': file_sha(ORIGINAL), 'inputs': result,
            'toolchain': dependencies}


def require_same_sources(expected, actual, message):
    if expected == actual:
        return
    before, after = expected.get('inputs', {}), actual.get('inputs', {})
    changed = [name for name in sorted(before.keys() | after.keys()) if before.get(name) != after.get(name)]
    changed += [name for name in ('original_apk_sha256', 'toolchain')
                if expected.get(name) != actual.get(name)]
    raise RuntimeError(message + ': ' + ', '.join(changed))


def check_sources():
    expected = json.loads((BUILD / 'source-hashes.json').read_text())
    require_same_sources(expected, input_manifest(), 'Build inputs changed after staging; rebuild unsigned before signing')
    stage = json.loads((BUILD / 'stage-validation.json').read_text())
    require(hashes(BUILD / 'stage') == stage['staged_class_hashes'], 'Staged smali changed after validation')


def original_baseline():
    root = BUILD / 'original-decoded'
    record = BUILD / 'original-decode-hashes.json'
    if root.exists() and record.exists():
        data = json.loads(record.read_text())
        if (data['original_apk_sha256'] == APK_SHA
                and data['apktool_sha256'] == file_sha(TOOLS / 'apktool.jar')
                and data['classes'] == hashes(root)):
            return root
    # Apktool expects a classes.dex entry before it will process additional dex files.
    # This intermediate is local build material only, never a deliverable.
    dex_input = BUILD / 'original-dex-input.apk'
    with zipfile.ZipFile(ORIGINAL) as old, zipfile.ZipFile(dex_input, 'w') as new:
        for name in ['AndroidManifest.xml', 'classes.dex', 'classes2.dex', 'classes3.dex']:
            new.writestr(name, old.read(name))
    run('java', '-jar', TOOLS / 'apktool.jar', 'd', '-r', '--no-assets',
        '-p', BUILD / 'framework', '-f', '-o', root, dex_input)
    require(file_sha(root / SERVICE) == 'b557ad541adfb8cadf5fb2e379cb2caf12704e44653312a3a45c54cad9ba12c4',
            'Independent original decode changed the pinned service')
    save(record.name, {'original_apk_sha256': APK_SHA,
                      'apktool_sha256': file_sha(TOOLS / 'apktool.jar'), 'classes': hashes(root)})
    return root


def stage():
    snapshot = input_manifest()
    baseline = original_baseline()
    original_hashes = {n: h for n, h in hashes(baseline).items()
                       if n.startswith(('smali_classes2/', 'smali_classes3/'))}
    current_hashes = original_hashes
    for name, expected in KEYBOARD_PINS.items():
        require(current_hashes[name] == expected, 'Keyboard input pin mismatch: ' + name)
    output = BUILD / 'stage'
    generated = BUILD / 'generated'
    clean(output)
    clean(generated)
    # Copy only smali, excluding original source backups and every Q4 build artifact.
    for name in current_hashes:
        target = output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(baseline / name, target)
    changes = {}
    for generator in GENERATORS:
        target = generated / Path(generator).stem
        run(sys.executable, BASE / 'native-integration' / generator,
            '--source', baseline, '--output', target)
        for name, digest in hashes(target).items():
            require(name not in changes, 'Unexpected generator overlap: ' + name)
            require(name in current_hashes, 'Generator introduced unreviewed original class: ' + name)
            changes[name] = {'generator': generator, 'generated_sha256': digest,
                             'source_sha256': current_hashes[name], 'original_sha256': original_hashes[name]}
            shutil.copyfile(target / name, output / name)
    # One intentional composition: native controller hooks first, publication API second.
    merged = generated / 'candidate-port' / MODEL
    controller_model = generated / 'patch_candidate_controller' / MODEL
    run(sys.executable, BASE / 'native-integration/patches/apply_candidate_port.py', controller_model, merged)
    before = controller_model.read_text().rstrip()
    after = merged.read_text()
    require(after.startswith(before + '\n\n'), 'Candidate append discarded native controller hooks')
    for method in ['quickPublish(', 'quickInvalidate(']:
        require(after.count('.method public final ' + method) == 1, 'Missing or duplicate candidate publication method')
    shutil.copyfile(merged, output / MODEL)
    changes[MODEL]['appended_generator'] = 'patches/apply_candidate_port.py'
    changes[MODEL]['merged_sha256'] = file_sha(merged)
    staged = hashes(output)
    require(staged.keys() == original_hashes.keys(), 'Staged original class set changed')
    changed = [n for n in staged if staged[n] != original_hashes[n]]
    require(set(changed) == set(changes), 'Unaccounted staged class changes')
    require('Lorg/wetype/quick/integration/QuickBridge;' not in (output / SERVICE).read_text(),
            'Legacy overlay hooks survived')
    require('QuickBridge$HostState' not in (output / SERVICE).read_text(), 'Legacy HostState survived')
    gates = sorted(n for n in original_hashes if any(
        n == p or (n.startswith(p[:-6] + '$') and n.endswith('.smali')) for p in GATES))
    require(set(GATES).issubset(gates), 'Required ASR gate class missing')
    for path in gates:
        require(staged[path] == original_hashes[path], 'ASR gate changed: ' + path)
    require_same_sources(snapshot, input_manifest(), 'Sources changed while staging; run again')
    save('source-hashes.json', snapshot)
    save('stage-validation.json', {
        'format': 1, 'changed_original_classes': sorted(changed), 'changes': changes,
        'unchanged_original_classes': len(staged) - len(changed),
        'staged_class_hashes': staged, 'original_classes2_3': original_hashes,
        'original_apk_unchanged': True,
        'candidate_controller_and_model_port_both_retained': True,
        'ASR_signer_gates': {p: staged[p] for p in gates},
    })
    save('stage-summary.json', {
        'format': 1, 'original_apk_sha256': APK_SHA,
        'original_classes2_3': len(staged), 'changed_original_classes': sorted(changed),
        'unchanged_original_class_count': len(staged) - len(changed),
        'ASR_signer_gates': {p: staged[p] for p in gates},
        'candidate_controller_and_model_port_both_retained': True,
        'stage_manifest_sha256': file_sha(BUILD / 'stage-validation.json'),
        'source_manifest_sha256': file_sha(BUILD / 'source-hashes.json'),
    })
    print('Staged %d classes; %d reviewed class changes; all other original classes preserved.' % (len(staged), len(changed)))


def marker():
    spec = importlib.util.spec_from_file_location('q5_marker', BASE / 'patch/mark_manifest.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.VERSION = '3.5.4-quick.0.1.0'
    module.VERSION_CODE = 57207
    module.LABEL = 'WeType Quick · 非官方'
    return module


def write_entry(archive, info, data):
    info = copy.copy(info)
    if info.compress_type == zipfile.ZIP_STORED:
        offset = archive.fp.tell() + 30 + len(info.filename.encode('utf-8')) + len(info.extra)
        alignment = 16384 if info.filename.endswith('.so') else 4
        if offset % alignment:
            count = -(offset + 4) % alignment
            info.extra += struct.pack('<HH', 0xffff, count) + bytes(count)
    archive.writestr(info, data)


def is_signature(name):
    return name == 'META-INF/MANIFEST.MF' or re.fullmatch(r'META-INF/[^/]+\.(?:RSA|DSA|EC|SF)', name, re.I)


def package():
    check_sources()
    m = marker()
    with zipfile.ZipFile(ORIGINAL) as old:
        manifest, edits = m.mark(old.read('AndroidManifest.xml'))
    (BUILD / ('AndroidManifest-' + RELEASE + '.xml')).write_bytes(manifest)
    save('manifest-marker.json', {'versionName': m.VERSION, 'versionCode': m.VERSION_CODE,
                                 'label': m.LABEL, 'changed_attributes': edits})
    require(sorted(p.name for p in (BUILD / 'dex').glob('*.dex')) == ['classes.dex'], 'Expected one new classes5 dex')
    replacements = {'AndroidManifest.xml': manifest,
                    'classes2.dex': (BUILD / 'classes2.dex').read_bytes(),
                    'classes3.dex': (BUILD / 'classes3.dex').read_bytes(),
                    'classes5.dex': (BUILD / 'dex/classes.dex').read_bytes()}
    replacements.update({'assets/quick/' + n: (BASE / p).read_bytes() for n, p in ASSETS.items()})
    with zipfile.ZipFile(ORIGINAL) as old, zipfile.ZipFile(BUILD / (PREFIX + '-unsigned.apk'), 'w') as new:
        require('classes5.dex' not in old.namelist(), 'Original already has classes5')
        for info in old.infolist():
            if is_signature(info.filename):
                continue
            write_entry(new, info, replacements.pop(info.filename) if info.filename in replacements else old.read(info.filename))
        for name, data in replacements.items():
            info = zipfile.ZipInfo(name, (2026, 10, 5, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            write_entry(new, info, data)


def dex_classes(data):
    require(data[:8] == b'dex\n037\0', 'All modules must use dex 037 / API24')
    require(struct.unpack_from('<I', data, 36)[0] == 112, 'DEX header size mismatch')
    require(struct.unpack_from('<I', data, 8)[0] == zlib.adler32(data[12:]) & 0xffffffff, 'DEX checksum mismatch')
    require(data[12:32] == hashlib.sha1(data[32:]).digest(), 'DEX content digest mismatch')
    strings_count, strings_offset = struct.unpack_from('<II', data, 56)
    types_count, types_offset = struct.unpack_from('<II', data, 64)
    count, offset = struct.unpack_from('<II', data, 96)
    result = set()
    for i in range(count):
        type_id = struct.unpack_from('<I', data, offset + 32 * i)[0]
        require(type_id < types_count, 'Invalid type index')
        string_id = struct.unpack_from('<I', data, types_offset + 4 * type_id)[0]
        require(string_id < strings_count, 'Invalid string index')
        pos = struct.unpack_from('<I', data, strings_offset + 4 * string_id)[0]
        while data[pos] & 128:
            pos += 1
        pos += 1
        descriptor = data[pos:data.index(b'\0', pos)].decode('utf-8')
        require(descriptor not in result, 'Duplicate class within DEX')
        result.add(descriptor)
    return result


def verify_quick_abi(data):
    """Resolve injected calls/field access against actual classes5 definitions."""
    def uleb(pos):
        value, shift = 0, 0
        while True:
            byte = data[pos]
            pos += 1
            value |= (byte & 127) << shift
            if not byte & 128:
                return value, pos
            shift += 7
            require(shift < 35, 'Invalid DEX ULEB128')

    def section(offset):
        return struct.unpack_from('<II', data, offset)

    count, start = section(56)
    strings = []
    for i in range(count):
        pos = struct.unpack_from('<I', data, start + 4 * i)[0]
        _, pos = uleb(pos)
        # JVM names/ABIs here are ASCII; non-ABI text need not decode as MUTF8.
        strings.append(data[pos:data.index(b'\0', pos)])
    count, start = section(64)
    types = [strings[struct.unpack_from('<I', data, start + 4 * i)[0]] for i in range(count)]
    count, start = section(72)
    prototypes = []
    for i in range(count):
        _, result, parameters = struct.unpack_from('<III', data, start + 12 * i)
        args = b''
        if parameters:
            length = struct.unpack_from('<I', data, parameters)[0]
            args = b''.join(types[struct.unpack_from('<H', data, parameters + 4 + 2 * j)[0]] for j in range(length))
        prototypes.append(b'(' + args + b')' + types[result])
    count, start = section(80)
    fields = []
    for i in range(count):
        owner, kind, name = struct.unpack_from('<HHI', data, start + 8 * i)
        fields.append(types[owner] + b'->' + strings[name] + b':' + types[kind])
    count, start = section(88)
    methods = []
    for i in range(count):
        owner, prototype, name = struct.unpack_from('<HHI', data, start + 8 * i)
        methods.append(types[owner] + b'->' + strings[name] + prototypes[prototype])
    method_defs, field_defs = {}, {}
    count, start = section(96)
    for i in range(count):
        pos = struct.unpack_from('<I', data, start + 32 * i + 24)[0]
        if not pos:
            continue
        counts = []
        for _ in range(4):
            value, pos = uleb(pos)
            counts.append(value)
        for length in counts[:2]:
            index = 0
            for _ in range(length):
                delta, pos = uleb(pos)
                flags, pos = uleb(pos)
                index += delta
                field_defs[fields[index].decode('ascii')] = flags
        for length in counts[2:]:
            index = 0
            for _ in range(length):
                delta, pos = uleb(pos)
                flags, pos = uleb(pos)
                _, pos = uleb(pos)
                index += delta
                method_defs[methods[index].decode('ascii')] = flags
    calls, accesses = {}, {}
    for path in (BUILD / 'stage').rglob('*.smali'):
        text = path.read_text()
        for match in re.finditer(r'(?m)^\s+(invoke-[\w/-]+)\s+[^\n]*?,\s*(Lorg/wetype/quick/[^\s]+;->[^\s]+)', text):
            op, signature = match.groups()
            require(signature in method_defs, 'Injected method missing from actual classes5: ' + signature)
            flags = method_defs[signature]
            require(bool(flags & 8) == op.startswith('invoke-static'), 'Injected method invocation kind mismatch: ' + signature)
            require(flags & 1, 'Injected cross-module method must be public: ' + signature)
            calls[signature] = calls.get(signature, 0) + 1
        for match in re.finditer(r'(?m)^\s+([is](?:get|put)[\w-]*)\s+[^\n]*?,\s*(Lorg/wetype/quick/[^\s]+;->[^\s]+)', text):
            op, signature = match.groups()
            require(signature in field_defs, 'Injected field missing from actual classes5: ' + signature)
            flags = field_defs[signature]
            require(bool(flags & 8) == op.startswith('s'), 'Injected field access kind mismatch: ' + signature)
            require(flags & 1, 'Injected cross-module field must be public: ' + signature)
            accesses[signature] = accesses.get(signature, 0) + 1
    require(calls and accesses, 'Expected injected Quick ABI references')
    report = {'format': 1, 'classes5_sha256': sha(data), 'method_references': calls,
              'field_references': accesses, 'all_references_resolve': True,
              'static_instance_and_public_access_match': True}
    save('quick-abi-validation.json', report)
    return report


def verify_packaged_gates(apk):
    """Disassemble actual packaged modules; compare exact gate smali to original."""
    expected = json.loads((BUILD / 'stage-validation.json').read_text())['ASR_signer_gates']
    output = BUILD / 'verified-gates-decoded'
    cache = BUILD / 'packaged-gate-validation.json'
    with zipfile.ZipFile(apk) as artifact:
        modules = {n: artifact.read(n) for n in ('classes2.dex', 'classes3.dex')}
    identity = {'modules': {n: sha(data) for n, data in modules.items()},
                'apktool_sha256': file_sha(TOOLS / 'apktool.jar'), 'original_gate_hashes': expected}
    if cache.exists() and output.exists():
        saved = json.loads(cache.read_text())
        if saved.get('inputs') == identity and all((output / p).is_file() and file_sha(output / p) == h
                                                   for p, h in expected.items()):
            return saved
    # Provide classes.dex only to make Apktool enumerate the other modules. Its
    # original bytes are retained; no original app code is loaded or executed.
    container = BUILD / 'gate-verification-input.apk'
    with zipfile.ZipFile(ORIGINAL) as original, zipfile.ZipFile(container, 'w') as target:
        for name in ('AndroidManifest.xml', 'classes.dex'):
            target.writestr(name, original.read(name))
        for name, data in modules.items():
            target.writestr(name, data)
    run('java', '-jar', TOOLS / 'apktool.jar', 'd', '-r', '--no-assets',
        '-p', BUILD / 'framework', '-f', '-o', output, container)
    actual = {p: file_sha(output / p) for p in expected}
    require(actual == expected, 'Packaged ASR gate disassembly differs from original')
    report = {'format': 1, 'inputs': identity, 'packaged_gate_hashes': actual,
              'all_explicit_ASR_gate_classes_identical_to_original': True,
              'class_count': len(actual), 'APK_code_executed': False}
    save(cache.name, report)
    return report


def verify(signed):
    check_sources()
    path = BUILD / (PREFIX + ('.apk' if signed else '-unsigned.apk'))
    raw = path.read_bytes()
    m = marker()
    all_classes = set()
    dex_reports = []
    native_count = 0
    with zipfile.ZipFile(ORIGINAL) as old, zipfile.ZipFile(path) as new:
        require(new.testzip() is None, 'APK CRC failure')
        require(len(new.namelist()) == len(set(new.namelist())), 'Duplicate ZIP entry')
        manifest, edits = m.mark(old.read('AndroidManifest.xml'))
        require(new.read('AndroidManifest.xml') == manifest, 'Unexpected manifest changes')
        changed = []
        for info in old.infolist():
            name = info.filename
            if is_signature(name):
                continue
            require(name in new.namelist(), 'Original entry removed: ' + name)
            require(new.getinfo(name).compress_type == info.compress_type, 'Compression method changed: ' + name)
            if old.read(name) != new.read(name):
                changed.append(name)
            if name.startswith('lib/'):
                native_count += 1
                require(old.read(name) == new.read(name), 'Native bytes changed: ' + name)
        require(set(changed) == {'AndroidManifest.xml', 'classes2.dex', 'classes3.dex'}, 'Unexpected original entry changes')
        added = set(new.namelist()) - set(old.namelist())
        allowed = {'classes5.dex'} | {'assets/quick/' + n for n in ASSETS}
        require({n for n in added if not is_signature(n)} == allowed, 'Unexpected added APK entries')
        for name, source in ASSETS.items():
            require(new.read('assets/quick/' + name) == (BASE / source).read_bytes(), 'Asset mismatch: ' + name)
        for info in new.infolist():
            name = info.filename
            if info.compress_type == zipfile.ZIP_STORED:
                fn, extra = struct.unpack_from('<HH', raw, info.header_offset + 26)
                data_offset = info.header_offset + 30 + fn + extra
                require(data_offset % 4 == 0, 'Unaligned stored entry: ' + name)
                if name.endswith('.so'):
                    require(data_offset % 16384 == 0, 'Native library lacks 16 KiB alignment: ' + name)
            if re.fullmatch(r'classes\d*\.dex', name):
                data = new.read(name)
                found = dex_classes(data)
                if name in ('classes2.dex', 'classes3.dex'):
                    require(found == dex_classes(old.read(name)), 'Original module class set changed: ' + name)
                    require(data == (BUILD / name).read_bytes(), 'Packaged module differs from assembled output: ' + name)
                require(not (found & all_classes), 'Duplicate classes between DEX modules')
                all_classes.update(found)
                if name == 'classes5.dex':
                    require(data == (BUILD / 'dex/classes.dex').read_bytes(), 'Packaged new code differs from D8 output')
                    require(all(n.startswith('Lorg/wetype/quick/') for n in found), 'Compile-only stubs leaked into classes5')
                    require('Lorg/wetype/quick/audio/VoiceMicrophoneRoute;' in found, 'Audio route missing')
                    require('Lorg/wetype/quick/integration/NativeQuickBridge;' in found, 'Native controller missing')
                    require('Lorg/wetype/quick/integration/QuickBridge;' not in found, 'Legacy overlay included')
                    quick_abi = verify_quick_abi(data)
                dex_reports.append({'name': name, 'version': '037', 'classes': len(found), 'sha256': sha(data)})
        require(len(dex_reports) == 5, 'Expected five DEX modules')
    gates = verify_packaged_gates(path)
    report = {'format': 1, 'artifact': path.name, 'sha256': sha(raw), 'bytes': len(raw),
              'original_apk_sha256': APK_SHA, 'package': 'com.tencent.wetype',
              'versionName': m.VERSION, 'versionCode': m.VERSION_CODE, 'label': m.LABEL,
              'manifest_changed_attributes': edits, 'changed_original_entries': changed,
              'dex': sorted(dex_reports, key=lambda d: d['name']),
              'native_entries_preserved': native_count, 'native_bytes_unchanged': True,
              'resources_bytes_unchanged': True, 'original_compression_methods_unchanged': True,
              'stored_entry_4byte_alignment': True, 'stored_native_16KiB_alignment': True,
              'assets_match_sources': True, 'no_duplicate_classes': True,
              'source_manifest_sha256': file_sha(BUILD / 'source-hashes.json'),
              'stage_manifest_sha256': file_sha(BUILD / 'stage-validation.json'),
              'packaged_ASR_gate_classes_identical_to_original': gates['class_count'],
              'packaged_gate_report_sha256': file_sha(BUILD / 'packaged-gate-validation.json'),
              'injected_Quick_methods_resolved': len(quick_abi['method_references']),
              'injected_Quick_fields_resolved': len(quick_abi['field_references']),
              'quick_abi_report_sha256': file_sha(BUILD / 'quick-abi-validation.json'),
              'signed': signed, 'APK_installed_or_executed': False}
    save(RELEASE + ('-validation.json' if signed else '-unsigned-validation.json'), report)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['preflight', 'stage', 'check-sources', 'package', 'verify-unsigned'])
    args = parser.parse_args()
    {'preflight': preflight, 'stage': stage, 'check-sources': check_sources,
     'package': package, 'verify-unsigned': lambda: verify(False)}[args.action]()
