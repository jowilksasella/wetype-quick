#!/usr/bin/env python3
"""Generate only the three version-pinned native keyboard UI patches in a separate tree.

Never writes the source decoded tree. Copy the reviewed output files into the packaging
stage later. No keyboard models, layouts, engine code, services or Q4 files are changed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

PORT = "Lorg/wetype/quick/integration/NativeKeyboardPort;"
KEY = "Lcom/tencent/wetype/plugin/hld/keyboard/selfdraw/j;"
PICKER = "Lcom/tencent/wetype/plugin/hld/view/settingkeyboard/S10SettingKeyboardTypeView;"
BASE = "Lcom/tencent/wetype/plugin/hld/view/settingkeyboard/b;"
DRAW_PATH = "smali_classes2/com/tencent/wetype/plugin/hld/keyboard/selfdraw/drawmethod/"
PICKER_PATH = "smali_classes3/com/tencent/wetype/plugin/hld/view/settingkeyboard/S10SettingKeyboardTypeView.smali"


def method(text, declaration, transform):
    pattern = re.compile(r"(?m)^" + re.escape(declaration) + r"\n.*?^\.end method$", re.S)
    matches = list(pattern.finditer(text))
    if len(matches) != 1:
        raise ValueError(f"Expected one {declaration}, got {len(matches)}")
    match = matches[0]
    return text[:match.start()] + transform(match.group()) + text[match.end():]


def drawer(text, locals_count, getter_count):
    declaration = ".method public b(Landroid/graphics/Canvas;" + KEY + ")V"

    def transform(body):
        old = f"    .locals {locals_count}\n"
        if body.count(old) != 1:
            raise ValueError("Unexpected drawer register layout")
        saved = f"v{locals_count}"
        new = (f"    .locals {locals_count + 1}\n\n"
               "    # NativeKeyboardPort: resolve the displayed glyph before native placement.\n"
               f"    invoke-static {{p2}}, {PORT}->displayMainText({KEY})Ljava/lang/String;\n"
               f"    move-result-object {saved}\n")
        body = body.replace(old, new, 1)
        getter = re.compile(
            r"    invoke-virtual \{p2\}, " + re.escape(KEY) + r"->R\(\)Ljava/lang/String;\n"
            r"(?:(?:[ \t]*\.line[^\n]*|[ \t]*)\n)*"
            r"    move-result-object ([vp]\d+)")
        body, count = getter.subn(lambda m: f"    move-object {m.group(1)}, {saved}", body)
        if count != getter_count:
            raise ValueError(f"Expected {getter_count} draw text reads, got {count}")
        return body
    return method(text, declaration, transform)


def picker(text):
    refresh = ("    # NativeKeyboardPort: use the original native tile layout and selection.\n"
               f"    invoke-static {{p0}}, {PORT}->refreshPicker({PICKER})V\n")

    def before_returns(body):
        count = body.count("    return-void")
        if count < 1:
            raise ValueError("Picker method has no return")
        return body.replace("    return-void", refresh + "    return-void")

    text = method(text, ".method public k(Z)V", before_returns)
    text = method(text, ".method public h()V", before_returns)

    def original_choice(body):
        pattern = re.compile(
            r"(    invoke-virtual \{p1\}, Lcom/tencent/wetype/plugin/hld/view/settingkeyboard/a;->c\(\)I\n"
            r"(?:(?:[ \t]*\.line[^\n]*|[ \t]*)\n)*    move-result p1)")
        body, count = pattern.subn(
            lambda m: m.group(1) + "\n\n    # NativeKeyboardPort: the item is validated before changing backend.\n"
            + f"    invoke-static {{p0, p1}}, {PORT}->onOriginalChoice({PICKER}I)V", body)
        if count != 1:
            raise ValueError(f"Expected one resolved native choice, got {count}")
        return body

    text = method(text, ".method public onClick(Landroid/view/View;)V", original_choice)
    if "quickNativeScene()I" in text:
        raise ValueError("Scene accessor already present")
    text += ("\n\n# NativeKeyboardPort: expose the inherited protected scene without reflection.\n"
             ".method public final quickNativeScene()I\n"
             "    .locals 1\n"
             f"    invoke-virtual {{p0}}, {BASE}->getFromSceneByCreate()I\n"
             "    move-result v0\n"
             "    return v0\n"
             ".end method\n")
    return text


def sha(data):
    return hashlib.sha256(data).hexdigest()


def generate(source, output):
    source, output = source.resolve(), output.resolve()
    if source == output or source in output.parents or output in source.parents:
        raise ValueError("Source and output trees must be separate, neither nested in the other")
    transforms = {
        DRAW_PATH + "e.smali": lambda text: drawer(text, 8, 3),
        DRAW_PATH + "i.smali": lambda text: drawer(text, 6, 1),
        PICKER_PATH: picker,
    }
    pending, manifest = [], []
    for relative, transform in transforms.items():
        path = source / relative
        before = path.read_bytes()
        original = before.decode("utf-8")
        if PORT in original:
            raise ValueError(f"Already patched source: {relative}")
        after = transform(original).encode("utf-8")
        pending.append((relative, after))
        manifest.append({"path": relative, "source_sha256": sha(before), "patched_sha256": sha(after)})
    # Validate every transformation before creating any output files.
    output.mkdir(parents=True, exist_ok=True)
    for relative, content in pending:
        path = output / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    (output / "keyboard-port-manifest.json").write_text(
        json.dumps({"format": 1, "files": manifest}, indent=2) + "\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(generate(args.source, args.output), indent=2))
