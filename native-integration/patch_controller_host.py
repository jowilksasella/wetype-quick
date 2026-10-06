#!/usr/bin/env python3
"""Generate pinned native controller/service hooks without writing the decoded input.

Input is the pinned unmodified APK, decoded locally by the user.
Only additive interoperability hooks are generated.
The original IME view, native selection handler and native key cleanup remain.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

BRIDGE = "Lorg/wetype/quick/integration/NativeQuickBridge;"
OLD = "Lorg/wetype/quick/integration/QuickBridge;"
SERVICE = "Lcom/tencent/wetype/plugin/hld/WxHldService;"
SERVICE_PATH = "smali_classes2/com/tencent/wetype/plugin/hld/WxHldService.smali"
KEY_PATH = "smali_classes2/com/tencent/wetype/plugin/hld/key/d.smali"
DELETE_PATH = "smali_classes2/com/tencent/wetype/plugin/hld/delete/d.smali"
MODEL_PATH = "smali_classes3/com/tencent/wetype/plugin/hld/model/N.smali"
ENUM_PATH = "smali_classes2/com/tencent/wetype/plugin/hld/f.smali"
PINS = {
    SERVICE_PATH: "b557ad541adfb8cadf5fb2e379cb2caf12704e44653312a3a45c54cad9ba12c4",
    KEY_PATH: "28e7c49db46569849e98e250f20697def9614aff1c8de94b7f2336e5fddd5cd3",
    DELETE_PATH: "fb82567c8bed272588f5197f9fc3c77c399fae81b8b984059744d3886cf6aabe",
    MODEL_PATH: "5560f2a218bd3829978de7ec9cf35b4a32f379454e48fc3fb725eb258bb7190d",
    ENUM_PATH: "fc85d98a58c641244554c63e5088dc95efb63654b91f91036d2f0013218a879a",
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def method(text, declaration, transform):
    pattern = re.compile(r"(?m)^" + re.escape(declaration) + r"\n.*?^\.end method$", re.S)
    matches = list(pattern.finditer(text))
    if len(matches) != 1:
        raise ValueError(f"Expected one {declaration}, got {len(matches)}")
    match = matches[0]
    return text[:match.start()] + transform(match.group()) + text[match.end():]


def entry(body, code, expected_locals):
    declaration = f"    .locals {expected_locals}\n"
    if body.count(declaration) != 1:
        raise ValueError("Unexpected register layout at hook entry")
    return body.replace(declaration, declaration + "\n" + code + "\n", 1)


def at_return(body, code, count=1):
    if body.count("    return-void") != count:
        raise ValueError("Unexpected native return count")
    return body.replace("    return-void", code + "    return-void")


COMMIT_WRAPPERS = r'''

# Native controller: cache reset preserves the editor's composing span until commit.
.method public final quickNativeCommit(Ljava/lang/CharSequence;)Z
    .locals 5
    const/4 v0, 0x0
    if-eqz p1, :quick_no_commit
    invoke-interface {p1}, Ljava/lang/CharSequence;->length()I
    move-result v1
    if-lez v1, :quick_no_commit
    invoke-virtual {p0, v0}, Lcom/tencent/wetype/plugin/hld/WxHldService;->B(Z)Landroid/view/inputmethod/InputConnection;
    move-result-object v1
    if-eqz v1, :quick_no_commit
    const-string v2, "quickNativeCommit"
    invoke-direct {p0, v2}, Lcom/tencent/wetype/plugin/hld/WxHldService;->r2(Ljava/lang/String;)V
    const/4 v2, 0x1
    invoke-interface {v1, p1, v2}, Landroid/view/inputmethod/InputConnection;->commitText(Ljava/lang/CharSequence;I)Z
    move-result v3
    if-eqz v3, :quick_commit_result
    const/4 v4, 0x0
    sget-object v2, Lcom/tencent/wetype/plugin/hld/f;->b:Lcom/tencent/wetype/plugin/hld/f;
    invoke-direct {p0, v4, v2, v0}, Lcom/tencent/wetype/plugin/hld/WxHldService;->j2(Lcom/tencent/wetype/plugin/hld/api/api/ImeAroundText;Lcom/tencent/wetype/plugin/hld/f;I)V
    :quick_commit_result
    return v3
    :quick_no_commit
    return v0
.end method

# No editor write: caller must separately own cancellation/composing-span policy.
.method public final quickNativeResetCompositionCache()V
    .locals 1
    const-string v0, "quickNativeResetCompositionCache"
    invoke-direct {p0, v0}, Lcom/tencent/wetype/plugin/hld/WxHldService;->r2(Ljava/lang/String;)V
    return-void
.end method
'''


def service(text):
    # The pinned user-supplied original is patched directly. No intermediate mod is needed.
    if BRIDGE in text or OLD in text:
        raise ValueError("Expected the unmodified original service")
    def attach(body):
        if body.count("    return-object v0") != 1:
            raise ValueError("Original input view return changed")
        return body.replace("    return-object v0",
            f"    invoke-static {{p0, v0}}, {BRIDGE}->attach({SERVICE}Landroid/view/View;)V\n"
            "    return-object v0", 1)
    text = method(text, ".method public onCreateInputView()Landroid/view/View;", attach)

    for name, count_locals in (("onFinishInput()V", 6), ("onDestroy()V", 6)):
        hook = f"    invoke-static/range {{p0 .. p0}}, {BRIDGE}->finish({SERVICE})V\n"
        text = method(text, ".method public " + name,
                      lambda body, hook=hook, n=count_locals: entry(body, hook, n))
    text = method(text, ".method public onFinishInputView(Z)V",
                  lambda body: entry(body,
                      f"    invoke-static/range {{p0 .. p1}}, {BRIDGE}->viewFinished({SERVICE}Z)V\n", 4))
    text = method(text, ".method public onStartInput(Landroid/view/inputmethod/EditorInfo;Z)V",
                  lambda body: at_return(entry(body,
                      f"    invoke-static/range {{p0 .. p0}}, {BRIDGE}->willStart({SERVICE})V\n", 7),
                      f"    invoke-static/range {{p0 .. p0}}, {BRIDGE}->start({SERVICE})V\n"))
    text = method(text, ".method public onStartInputView(Landroid/view/inputmethod/EditorInfo;Z)V",
                  lambda body: at_return(body,
                      f"    invoke-static/range {{p0 .. p0}}, {BRIDGE}->viewStarted({SERVICE})V\n"))
    text = method(text, ".method public onUpdateSelection(IIIIII)V",
                  lambda body: entry(body,
                      f"    invoke-static/range {{p0 .. p6}}, {BRIDGE}->selection({SERVICE}IIIIII)V\n", 17))
    text = method(text, ".method public h0(Ljava/lang/CharSequence;ZILwa/c;)V",
                  lambda body: entry(body,
                      f"    invoke-static/range {{p1 .. p1}}, {BRIDGE}->allowComposingText(Ljava/lang/CharSequence;)Z\n"
                      "    move-result v0\n"
                      "    if-nez v0, :quick_allow_composing_text\n"
                      "    return-void\n"
                      "    :quick_allow_composing_text\n", 8))
    if "quickNativeCommit(" in text or "quickNativeResetCompositionCache(" in text:
        raise ValueError("Native controller service wrappers already exist")
    for contract in (
        ".method private final r2(Ljava/lang/String;)V",
        ".method private final j2(Lcom/tencent/wetype/plugin/hld/api/api/ImeAroundText;Lcom/tencent/wetype/plugin/hld/f;I)V",
        ".method public B(Z)Landroid/view/inputmethod/InputConnection;",
    ):
        if text.count(contract) != 1:
            raise ValueError(f"Missing original service contract: {contract}")
    return text + COMMIT_WRAPPERS


def keys(text):
    text = method(text, ".method public P(Lwa/h;)V", lambda body: entry(body,
        f"    invoke-static/range {{p1 .. p1}}, {BRIDGE}->prepress(Lwa/h;)Z\n"
        "    move-result v0\n"
        "    if-eqz v0, :quick_native_prepress\n"
        "    const/4 v0, 0x0\n"
        "    sput-object v0, Lcom/tencent/wetype/plugin/hld/key/d;->b:Lwa/h;\n"
        "    return-void\n"
        "    :quick_native_prepress\n", 14))

    def release(body):
        if body.count("    :goto_2\n") != 1:
            raise ValueError("Native accepted-key cleanup label changed")
        tail = body[body.index("    :goto_2\n"):]
        if "Lcom/tencent/wetype/plugin/hld/utils/S;->q(" not in tail or "->b:Lwa/h;" not in tail:
            raise ValueError("Native accepted-key cleanup contract changed")
        return entry(body,
            f"    invoke-static/range {{p1 .. p1}}, {BRIDGE}->key(Lwa/h;)Z\n"
            "    move-result v0\n"
            "    if-eqz v0, :quick_native_key\n"
            "    goto/32 :goto_2\n"
            "    :quick_native_key\n", 10)
    text = method(text, ".method public R(Lwa/h;Z)V", release)
    text = method(text, ".method public O(ILjava/lang/Object;)V", lambda body: entry(body,
        f"    invoke-static/range {{p1 .. p2}}, {BRIDGE}->function(ILjava/lang/Object;)Z\n"
        "    move-result v0\n"
        "    if-eqz v0, :quick_native_function\n"
        "    return-void\n"
        "    :quick_native_function\n", 13))
    return text


def mode(text):
    return method(text, ".method public final o2(Ljava/lang/Integer;Ljava/lang/Integer;Landroid/os/Bundle;)V",
                  lambda body: at_return(entry(body,
                      f"    invoke-static {{}}, {BRIDGE}->modeChanged()V\n", 9),
                      f"    invoke-static {{}}, {BRIDGE}->modeChanged()V\n"))


def completed_clear(text):
    # Native delete/c and its float-view action still decide when this completes.
    # Do not intercept an ordinary pending-delete count as an editor-wide action.
    def after_polish_cleanup(body):
        if body.count("    .locals 18\n") != 1:
            raise ValueError("Unexpected native clear register layout")
        cleanup = "    invoke-virtual {v0}, Ltb/b;->I()V\n"
        if body.count(cleanup) != 1:
            raise ValueError("Original text-polish cancellation seam changed")
        hook = (
            f"\n    invoke-static {{}}, {BRIDGE}->nativeClearBeforeCursor()Z\n"
            "    move-result v0\n"
            "    if-eqz v0, :quick_native_clear_before_cursor\n"
            "    return-void\n"
            "    :quick_native_clear_before_cursor\n\n")
        return body.replace(cleanup, cleanup + hook, 1)
    return method(text, ".method public final q()V", after_polish_cleanup)


def generate(source, output):
    source, output = source.resolve(), output.resolve()
    if source == output or source in output.parents or output in source.parents:
        raise ValueError("Source and output must be separate trees without overlap")
    originals = {}
    for relative, pin in PINS.items():
        data = (source / relative).read_bytes()
        if sha(data) != pin:
            raise ValueError(f"Source hash mismatch: {relative}; refusing an unreviewed host version")
        originals[relative] = data
    changes = {SERVICE_PATH: service, KEY_PATH: keys, DELETE_PATH: completed_clear, MODEL_PATH: mode}
    pending, manifest = [], []
    for relative, transform in changes.items():
        original = originals[relative]
        text = original.decode("utf-8")
        if BRIDGE in text:
            raise ValueError(f"Already patched: {relative}")
        patched = transform(text).encode("utf-8")
        pending.append((relative, patched))
        manifest.append({"path": relative, "source_sha256": sha(original), "patched_sha256": sha(patched)})
    output.mkdir(parents=True, exist_ok=True)
    for relative, data in pending:
        path = output / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    document = {"format": 1, "api_level": 24, "files": manifest,
                "verified_emit_enum": {"path": ENUM_PATH, "sha256": PINS[ENUM_PATH], "field": "f.b = EmitByClient"},
                "cancellation": "cache-reset-only; never commits an unverified empty span"}
    (output / "controller-host-manifest.json").write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    return document


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(generate(args.source, args.output), indent=2))
