#!/usr/bin/env python3
"""Generate version-pinned candidate/backend hooks into a NEW staging tree.

The original decoded tree and Q4 are read-only inputs. Whole engine-result
callbacks are retained so native iterator lifetime and coroutine cleanup survive.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

BRIDGE = 'Lorg/wetype/quick/integration/NativeQuickBridge;'
MODEL = 'Lcom/tencent/wetype/plugin/hld/model/i0;'
STATE = 'Lcom/tencent/wetype/plugin/hld/model/Z;'
CANDIDATE = 'Lcom/tencent/wetype/plugin/hld/candidate/d;'
REPORT = 'Lcom/tencent/wetype/plugin/hld/model/report/p;'
BASE2 = 'smali_classes2/com/tencent/wetype/plugin/hld/'
BASE3 = 'smali_classes3/com/tencent/wetype/plugin/hld/'
# Filled from the independently preserved original APK decode, never from output.
EXPECTED_HASHES = {'smali_classes2/com/tencent/wetype/plugin/hld/alternative/p.smali': '971a8160c56bd5f9d943100bd14abb8eca7c1cddcc0a87316ce775a5b7369f79',
 'smali_classes2/com/tencent/wetype/plugin/hld/candidate/Z.smali': '6d3b0356acfed576f18c56b1e744bf82be05dec0370a9b16233c4b3cc84b30ff',
 'smali_classes2/com/tencent/wetype/plugin/hld/candidate/a.smali': '1d816217d7d1048ffaf521baa95732c58df258ba1d309d5fef8ebfbb6915f701',
 'smali_classes3/com/tencent/wetype/plugin/hld/model/i0$K1.smali': '2350c86bfe1b5d38ff23f751702c7bd54c5e743d6f5b7f04e127195e3929404d',
 'smali_classes3/com/tencent/wetype/plugin/hld/model/i0$f0.smali': 'dfccdb0ff1b6a064d2f402e7281e0e7b611a2de6e56c85424d948701f0e7aa84',
 'smali_classes3/com/tencent/wetype/plugin/hld/model/i0$i2.smali': '2bd4ad3a777e59e8048fb49e7e2c692ac46c5ddd0e9fb1dfc7654d8e8cc9037c',
 'smali_classes3/com/tencent/wetype/plugin/hld/model/i0$j2.smali': 'c6450018d020975095ec7b179e0b8f3d133a691db8afa6471c4be49b0e3d6b70',
 'smali_classes3/com/tencent/wetype/plugin/hld/model/i0$k2.smali': '8715a448d3bac45e6556914f5a3353a79cfe9d3c5e9fa19c553a7c65daa6e443',
 'smali_classes3/com/tencent/wetype/plugin/hld/model/i0$l2.smali': '7a8cfd9566e10515800e303bd4e75de7efd09afefa6b39171c3e6f5bf56ad705',
 'smali_classes3/com/tencent/wetype/plugin/hld/model/i0$m1.smali': 'f07cadf1193ff659d459754891640adb2d79b9e35bd4affadbfd3f95f37acc71',
 'smali_classes3/com/tencent/wetype/plugin/hld/model/i0$n0.smali': '92cec135f77818eb848af4cba6f957fc27a7436b3a65e7774ca56eb54fe1a5c7',
 'smali_classes3/com/tencent/wetype/plugin/hld/model/i0.smali': 'fa4a9525d451f2b7a71b85eb996aedd3ae4a24c0382f9376a89484fc5cabe23b',
 'smali_classes3/com/tencent/wetype/plugin/hld/model/report/p.smali': '5b8d786434a7165dece1839d7d411da92f6e13f6c985a205773c309ac643f741'}
EXPECTED_HASHES[BASE3 + 'model/T.smali'] = 'a1756c28c76d19fe88dbb735941cefff8994f58167aaab220f86b774af33156e'
EXPECTED_HASHES[BASE2 + 'candidate/ImeCandidateView.smali'] = '8a2b0e69d148e214582a97fb8d33efe84e901c20a3b3e8b54deb221642b7d03c'
COUNTS = {}

def counted(label, count, expected=1):
    if count != expected:
        raise ValueError('%s: expected %d changes, found %d' % (label, expected, count))
    COUNTS[label] = count

def one(text, old, new, label):
    counted(label, text.count(old))
    return text.replace(old, new, 1)

def method(text, name, transform):
    pattern = re.compile(r'(?m)^\.method [^\n]* ' + re.escape(name) + r'\([^\n]*\n.*?^\.end method$', re.S)
    hits = list(pattern.finditer(text))
    counted('method:' + name + ':' + str(len(COUNTS)), len(hits))
    hit = hits[0]
    body = transform(hit.group())
    return text[:hit.start()] + body + text[hit.end():]

def prefix(body, code):
    match = re.search(r'(?m)^    \.locals (\d+)\n', body)
    if not match or int(match.group(1)) < 1:
        raise ValueError('Hook needs an existing low scratch register: ' + body.splitlines()[0])
    return body[:match.end()] + '\n    # Quick native controller boundary.\n' + code + '\n' + body[match.end():]

def bool_consumed(call, arg=None):
    invoke = '    invoke-static {}, ' if arg is None else '    invoke-static/range {%s .. %s}, ' % (arg, arg)
    return invoke + BRIDGE + '->' + call + '\n    move-result v0\n    if-eqz v0, :quick_original\n    return-void\n    :quick_original\n'

def ownership_return(name, descriptor, object_result=False):
    return ('    invoke-static {}, ' + BRIDGE + '->ownsPending()Z\n'
            '    move-result v0\n    if-eqz v0, :quick_original\n'
            '    invoke-static {}, ' + BRIDGE + '->' + name + '()' + descriptor + '\n'
            '    move-result' + ('-object' if object_result else '') + ' v0\n'
            '    return' + ('-object' if object_result else '') + ' v0\n    :quick_original\n')

def write_helper(name, arguments, operation):
    return ('\n.method public static ' + name + '(' + arguments + ')V\n'
            '    .locals 2\n'
            '    sget-object v0, ' + MODEL + '->q:Ljava/lang/Object;\n'
            '    monitor-enter v0\n    :quick_try\n'
            '    invoke-static {}, ' + BRIDGE + '->ownsPending()Z\n'
            '    move-result v1\n    if-nez v1, :quick_skip\n' + operation +
            '    :quick_skip\n    monitor-exit v0\n    :quick_end\n'
            '    .catchall {:quick_try .. :quick_end} :quick_catch\n'
            '    return-void\n    :quick_catch\n    move-exception v1\n'
            '    monitor-exit v0\n    throw v1\n.end method\n')

SETTERS = {'g':'', 'h':'Z', 'i':'Ljava/lang/CharSequence;', 'j':'Ljava/lang/CharSequence;', 'k':'I', 'l':'I', 'm':'[Lcom/tencent/wxhld/info/PendingInput;'}
SETTER_COUNTS = {'smali_classes3/com/tencent/wetype/plugin/hld/model/i0$K1.smali': 1,
 'smali_classes3/com/tencent/wetype/plugin/hld/model/i0$f0.smali': 1,
 'smali_classes3/com/tencent/wetype/plugin/hld/model/i0$m1.smali': 3,
 'smali_classes3/com/tencent/wetype/plugin/hld/model/i0$n0.smali': 2,
 'smali_classes3/com/tencent/wetype/plugin/hld/model/i0.smali': 9}

def guard_pending_setters(text, relative):
    pattern = re.compile(r'(?m)^    invoke-virtual(?P<range>/range)? \{(?P<regs>[^}]+)\}, ' + re.escape(STATE) + r'->(?P<name>[ghijklm])\((?P<args>[^)]*)\)V$')
    def replace(match):
        if SETTERS[match['name']] != match['args']:
            raise ValueError('Pending setter ABI changed')
        return '    invoke-static' + (match['range'] or '') + ' {' + match['regs'] + '}, ' + MODEL + '->quickNativePending_' + match['name'] + '(' + STATE + match['args'] + ')V'
    text, count = pattern.subn(replace, text)
    counted('pending-setters:' + relative, count, SETTER_COUNTS[relative])
    return text

def model(text):
    text = method(text, 'P3', lambda b: prefix(b, bool_consumed('choose(' + CANDIDATE + ')Z', 'p1')))
    text = method(text, 'U1', lambda b: prefix(b, bool_consumed('loadMore()Z')))
    for name in ('G2', 'D2'):
        text = method(text, name, lambda b: prefix(b, ownership_return('pendingText', 'Ljava/lang/String;', True)))
    text = method(text, 'F2', lambda b: prefix(b, ownership_return('pendingLength', 'I')))
    text = method(text, 'A3', lambda b: prefix(b, ownership_return('pendingEmpty', 'Z')))
    # z2 is PREVIOUS pending text, not current roots. Preserve original meaning.
    text = method(text, 'z2', lambda b: prefix(b,
        '    invoke-static {}, ' + BRIDGE + '->ownsPending()Z\n    move-result v0\n'
        '    if-eqz v0, :quick_original\n    sget-object v0, ' + MODEL + '->D:' + STATE + '\n'
        '    invoke-virtual {v0}, ' + STATE + '->b()Ljava/lang/CharSequence;\n'
        '    move-result-object v0\n    return-object v0\n    :quick_original\n'))
    for name in ('t1', 'H4'):
        text = method(text, name, lambda b: prefix(b, '    invoke-static {}, ' + BRIDGE + '->clearHistory()V\n'))
    def n5(body):
        body = prefix(body, '    invoke-static/range {p1 .. p1}, ' + BRIDGE + '->allowNativeCandidates(Ljava/util/ArrayList;)Z\n    move-result v0\n    if-nez v0, :quick_original\n    return-void\n    :quick_original\n')
        return one(body, '    :goto_0\n', '    :goto_0\n    invoke-static/range {p1 .. p1}, ' + BRIDGE + '->allowNativeCandidates(Ljava/util/ArrayList;)Z\n    move-result v0\n    if-eqz v0, :cond_3\n', 'N5-loop')
    text = method(text, 'N5', n5)
    # Retain O3's session validation and engine-process completion tail. The skipped
    # block only converts/publishes/logs pending text and fires an empty-input action.
    text = method(text, 'O3', lambda b: one(b, '    :cond_0\n', '    :cond_0\n    invoke-static {}, ' + BRIDGE + '->ownsPending()Z\n    move-result v0\n    if-nez v0, :cond_2\n', 'O3-state-boundary'))
    # V3 timing reports remain; the owned pending observer fanout is suppressed.
    text = method(text, 'V3', lambda b: one(b, '    :goto_0\n', '    :goto_0\n    invoke-static {}, ' + BRIDGE + '->ownsPending()Z\n    move-result v1\n    if-nez v1, :cond_0\n', 'V3-observers'))
    # These methods contain only cache mutation (and exposure reset), no iterator cleanup.
    for name in ('p1', 'q1'):
        def cache(body, name=name):
            body = one(body, '    :try_start_0\n', '    :try_start_0\n    invoke-static {}, ' + BRIDGE + '->ownsPending()Z\n    move-result v1\n    if-nez v1, :quick_skip_cache\n', name + '-under-q')
            match = re.search(r'(?m)^    sget-object [pv]\d+, Lnc/F;->a:Lnc/F;$', body)
            if not match: raise ValueError('Cache unlock anchor missing')
            return body[:match.start()] + '    :quick_skip_cache\n' + body[match.start():]
        text = method(text, name, cache)
    # Protect scalar writes without skipping any surrounding coroutine/resource work.
    for field, desc, opname, count in [('B','I','sput',3), ('V','Z','sput-boolean',3)]:
        total = [0]
        pattern = re.compile(r'(?m)^    ' + opname + r' ([pv]\d+), ' + re.escape(MODEL) + '->' + field + ':' + desc + '$')
        def each_method(match):
            body = match.group()
            if body.startswith('.method static constructor <clinit>'): return body
            def scalar(m):
                total[0] += 1
                return '    invoke-static/range {' + m[1] + ' .. ' + m[1] + '}, ' + MODEL + '->quickNativeScalar_' + field + '(' + desc + ')V'
            return pattern.sub(scalar, body)
        text = re.sub(r'(?m)^\.method [^\n]*\n.*?^\.end method$', each_method, text, flags=re.S)
        counted('scalar:' + field, total[0], count)
    text = guard_pending_setters(text, BASE3 + 'model/i0.smali')
    # Cloud coroutines retain ownership/cleanup. Suppress only their UI dispatch.
    for name in ('J5', 'L5', 'M5', 'L1', 'l5', 'L3', 'Q5'):
        text = method(text, name, lambda b: prefix(b, bool_consumed('ownsPending()Z')))
    text = method(text, 'J5', lambda b: one(b, '    :goto_0\n', '    :goto_0\n    invoke-static {}, ' + BRIDGE + '->ownsPending()Z\n    move-result v1\n    if-nez v1, :cond_3\n', 'J5-loop'))
    def i3(body):
        for reg, field in [('v1','r'),('v2','v')]:
            old = '    sget-object ' + reg + ', ' + MODEL + '->' + field + ':Ljava/util/ArrayList;'
            new = '    invoke-static {}, ' + MODEL + '->quickCloudForUi_' + field + '()Ljava/util/ArrayList;\n    move-result-object ' + reg
            body = one(body, old, new, 'I3-cloud:' + field)
        return body
    text = method(text, 'I3', i3)
    text += write_helper('quickNativeScalar_B', 'I', '    sput p0, ' + MODEL + '->B:I\n')
    text += write_helper('quickNativeScalar_V', 'Z', '    sput-boolean p0, ' + MODEL + '->V:Z\n')
    for name, args in SETTERS.items():
        regs = 'p0, p1' if args else 'p0'
        text += write_helper('quickNativePending_' + name, STATE + args, '    invoke-virtual {' + regs + '}, ' + STATE + '->' + name + '(' + args + ')V\n')
    for field in ('r','v'):
        text += ('\n.method private static quickCloudForUi_' + field + '()Ljava/util/ArrayList;\n    .locals 1\n'
                 '    invoke-static {}, ' + BRIDGE + '->ownsPending()Z\n    move-result v0\n'
                 '    if-eqz v0, :quick_native\n    new-instance v0, Ljava/util/ArrayList;\n'
                 '    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V\n    return-object v0\n'
                 '    :quick_native\n    sget-object v0, ' + MODEL + '->' + field + ':Ljava/util/ArrayList;\n    return-object v0\n.end method\n')
    return text

def candidate_actions(text):
    return method(text, 'e', lambda b: prefix(b,
        '    invoke-static/range {p1 .. p1}, ' + BRIDGE + '->candidateActions(' + CANDIDATE + ')Ljava/util/List;\n'
        '    move-result-object v0\n    if-eqz v0, :quick_original\n    return-object v0\n    :quick_original\n'))

def report(text):
    for name in ('n','p','q','r','s','x','y'):
        unit = name in ('n','p','s')
        code = ('    invoke-static {}, ' + BRIDGE + '->ownsPending()Z\n    move-result v0\n    if-nez v0, :quick_suppress\n'
                '    invoke-static/range {p1 .. p1}, ' + BRIDGE + '->isQuickCandidate(' + CANDIDATE + ')Z\n'
                '    move-result v0\n    if-eqz v0, :quick_original\n    :quick_suppress\n')
        code += '    sget-object v0, Lnc/F;->a:Lnc/F;\n    return-object v0\n' if unit else '    return-void\n'
        text = method(text, name, lambda b, code=code: prefix(b, code + '    :quick_original\n'))
    for name, arg, unit in [('v','p1',False),('w','p2',False),('o','p1',True),('z','p2',False)]:
        code = '    invoke-static {}, ' + BRIDGE + '->ownsPending()Z\n    move-result v0\n    if-nez v0, :quick_suppress\n    invoke-static/range {' + arg + ' .. ' + arg + '}, ' + REPORT + '->quickContainsMarker(Ljava/util/List;)Z\n    move-result v0\n    if-eqz v0, :quick_original\n    :quick_suppress\n'
        code += '    sget-object v0, Lnc/F;->a:Lnc/F;\n    return-object v0\n' if unit else '    return-void\n'
        text = method(text, name, lambda b, code=code: prefix(b, code + '    :quick_original\n'))
    text += ('\n.method private static quickContainsMarker(Ljava/util/List;)Z\n    .locals 3\n'
             '    if-eqz p0, :quick_none\n    invoke-interface {p0}, Ljava/util/List;->iterator()Ljava/util/Iterator;\n'
             '    move-result-object v0\n    :quick_next\n    invoke-interface {v0}, Ljava/util/Iterator;->hasNext()Z\n'
             '    move-result v1\n    if-eqz v1, :quick_none\n    invoke-interface {v0}, Ljava/util/Iterator;->next()Ljava/lang/Object;\n'
             '    move-result-object v1\n    instance-of v2, v1, Lnc/n;\n    if-eqz v2, :quick_candidate\n'
             '    check-cast v1, Lnc/n;\n    invoke-virtual {v1}, Lnc/n;->g()Ljava/lang/Object;\n    move-result-object v1\n'
             '    :quick_candidate\n    instance-of v2, v1, ' + CANDIDATE + '\n    if-eqz v2, :quick_next\n'
             '    check-cast v1, ' + CANDIDATE + '\n    invoke-static {v1}, ' + BRIDGE + '->isQuickCandidate(' + CANDIDATE + ')Z\n'
             '    move-result v1\n    if-eqz v1, :quick_next\n    const/4 v0, 0x1\n    return v0\n'
             '    :quick_none\n    const/4 v0, 0x0\n    return v0\n.end method\n')
    return text

def dispatch_coroutine(text, name):
    def update(body):
        if name == 'l2':
            code = '    invoke-static {v1}, ' + BRIDGE + '->allowNativeCandidates(Ljava/util/ArrayList;)Z\n    move-result v5\n    if-eqz v5, :cond_1\n'
        else:
            scratch, done = ('v1',':cond_1') if name == 'i2' else ('v0',':cond_0')
            code = '    invoke-static {}, ' + BRIDGE + '->ownsPending()Z\n    move-result ' + scratch + '\n    if-nez ' + scratch + ', ' + done + '\n'
        return one(body, '    :goto_0\n', '    :goto_0\n' + code, 'dispatch-loop:' + name)
    return method(text, 'invokeSuspend', update)

def single_words(text):
    def update(body):
        pattern = re.compile(r'    invoke-virtual \{v5\}, ' + re.escape(CANDIDATE) + r'->s\(\)Ljava/lang/String;\n(?:(?:[ \t]*\.line[^\n]*|[ \t]*)\n)*    move-result-object v5\n(?:(?:[ \t]*\.line[^\n]*|[ \t]*)\n)*    invoke-virtual \{v5\}, Ljava/lang/String;->length\(\)I\n(?:(?:[ \t]*\.line[^\n]*|[ \t]*)\n)*    move-result v5')
        body, count = pattern.subn('    invoke-static {v5}, Lcom/tencent/wetype/plugin/hld/alternative/p;->quickSingleWordLength(' + CANDIDATE + ')I\n    move-result v5', body)
        counted('single-word-codepoints', count)
        return body
    text = method(text, 'b', update)
    text += ('\n.method private static quickSingleWordLength(' + CANDIDATE + ')I\n    .locals 3\n'
             '    invoke-static {p0}, ' + BRIDGE + '->isQuickCandidate(' + CANDIDATE + ')Z\n'
             '    move-result v0\n    if-eqz v0, :quick_native\n'
             '    check-cast p0, Lorg/wetype/quick/integration/QuickNativeCandidate;\n'
             '    iget v0, p0, Lorg/wetype/quick/integration/QuickNativeCandidate;->action:I\n'
             '    if-eqz v0, :quick_text\n    const/4 v0, 0x1\n    return v0\n'
             '    :quick_text\n    iget-object v0, p0, Lorg/wetype/quick/integration/QuickNativeCandidate;->commitText:Ljava/lang/String;\n'
             '    const/4 v1, 0x0\n    invoke-virtual {v0}, Ljava/lang/String;->length()I\n'
             '    move-result v2\n    invoke-virtual {v0, v1, v2}, Ljava/lang/String;->codePointCount(II)I\n'
             '    move-result v0\n    return v0\n'
             '    :quick_native\n    invoke-virtual {p0}, ' + CANDIDATE + '->s()Ljava/lang/String;\n'
             '    move-result-object v0\n    invoke-virtual {v0}, Ljava/lang/String;->length()I\n'
             '    move-result v0\n    return v0\n.end method\n')
    return text

def pending_placement(text):
    def update(body):
        calls = [
            r'invoke-virtual \{p3\}, Lcom/tencent/wetype/plugin/hld/pendinginput/a;->t\(\)Z',
            r'invoke-static \{p3, p5, p2, v2\}, Lcom/tencent/wetype/plugin/hld/pendinginput/a;->v\(Lcom/tencent/wetype/plugin/hld/pendinginput/a;IILjava/lang/Object;\)Z',
        ]
        for index, call in enumerate(calls):
            pattern = re.compile(r'(    ' + call + r'\n(?:(?:[ \t]*\.line[^\n]*|[ \t]*)\n)*    move-result (?P<reg>[pv]\d+))')
            def gate(match):
                reg = match['reg']
                return match[1] + '\n    invoke-static/range {' + reg + ' .. ' + reg + '}, ' + BRIDGE + '->useInlinePending(Z)Z\n    move-result ' + reg
            body, count = pattern.subn(gate, body)
            counted('pending-placement:' + str(index), count)
        return body
    return method(text, 't', update)


def generate(source, output):
    source, output = source.resolve(), output.resolve()
    if source == output or source in output.parents or output in source.parents:
        raise ValueError('Source/output trees must be separate and not nested')
    transforms = {
      BASE3+'model/i0.smali': model,
      BASE3+'model/T.smali': lambda t: method(t, 'updateAroundText', lambda b: prefix(b, bool_consumed('ownsPending()Z'))),
      BASE2+'candidate/Z.smali': lambda t: method(t, 'a', lambda b: prefix(b, bool_consumed('choose('+CANDIDATE+')Z','p0'))),
      BASE2+'candidate/a.smali': candidate_actions,
      BASE2+'candidate/ImeCandidateView.smali': pending_placement,
      BASE3+'model/report/p.smali': report,
      BASE2+'alternative/p.smali': single_words,
    }
    for name in ('i2','j2','k2','l2'):
        transforms[BASE3+'model/i0$'+name+'.smali'] = lambda t,name=name: dispatch_coroutine(t,name)
    for name in ('n0','m1','K1','f0'):
        relative = BASE3+'model/i0$'+name+'.smali'
        transforms[relative] = lambda t,relative=relative: guard_pending_setters(t,relative)
    staged, manifest = [], []
    COUNTS.clear()
    for relative, transform in transforms.items():
        before = (source/relative).read_bytes()
        digest = hashlib.sha256(before).hexdigest()
        if digest != EXPECTED_HASHES[relative]: raise ValueError('Original source hash mismatch: '+relative)
        text = before.decode()
        if BRIDGE in text: raise ValueError('Already patched input: '+relative)
        after = transform(text).encode()
        if (output/relative).exists(): raise ValueError('Refusing to overwrite staged file: '+relative)
        staged.append((relative,after))
        manifest.append({'path':relative,'source_sha256':digest,'patched_sha256':hashlib.sha256(after).hexdigest()})
    output.mkdir(parents=True,exist_ok=True)
    for relative,data in staged:
        p=output/relative;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
    report_data={'format':1,'files':manifest,'verified_change_counts':COUNTS,'thread_contract':'ownsPending/isQuickCandidate/allowNativeCandidates must be nonblocking and engine-thread-safe; ownsPending runs under original q','preserved':'original OnCloudEngineResult, iterator wrapper/deletion methods, and surrounding engine coroutine cleanup; cloud cache lifecycle remains native but UI consumers ignore it while owned'}
    (output/'candidate-controller-manifest.json').write_text(json.dumps(report_data,indent=2)+'\n')
    return report_data

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();print(json.dumps(generate(a.source,a.output),indent=2))
