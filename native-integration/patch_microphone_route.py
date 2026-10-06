#!/usr/bin/env python3
"""Generate version-pinned, voice-only microphone hooks in a separate smali tree.

Does not edit decoded input, Q4, audio sources/formats, permissions, or ASR gates.
The separately compiled org.wetype.quick.audio helper must accompany these patches.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

PORT = "Lorg/wetype/quick/audio/VoiceMicrophoneRoute;"
RECORDER = "smali_classes2/l6/f.smali"
WRAPPER = "smali_classes2/o6/b.smali"
VOICE = "smali_classes3/com/tencent/wetype/plugin/hld/voice/"
PINS = {
    RECORDER: "e8e963e8431890ab3bae6052c6fd54d2e06b1bb2153b066240068584a4b466e4",
    WRAPPER: "6ddebf56d07e654de2ca9b38a4057d62ad7e611ca39026be7e57e9903c742dc1",
    VOICE + "M$b.smali": "d3958a496adc7d810409fdcce082be5b8398801f833b7decdc1027a81960ad6e",
    VOICE + "M$d.smali": "22e82fbf73f247289ee8f965e735e5256603b11f26246245cd7efcba60dad657",
}


def method(text, declaration, transform):
    matches = list(re.finditer(r"(?m)^" + re.escape(declaration) + r"\n.*?^\.end method$", text, re.S))
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one {declaration}")
    match = matches[0]
    return text[:match.start()] + transform(match.group()) + text[match.end():]


def replace(text, old, new, count=1):
    if text.count(old) != count:
        raise ValueError(f"Unexpected seam count for {old!r}: {text.count(old)}")
    return text.replace(old, new)


def recorder(text):
    text = replace(text, "# instance fields\n", "# instance fields\n.field private quickBuiltinMicrophone:Z\n\n")

    def start(body):
        if "    .locals 9\n" not in body:
            raise ValueError("Recorder start register layout changed")
        old = "    invoke-virtual {v6}, Landroid/media/AudioRecord;->startRecording()V"
        new = (
            "    # VoiceMicrophoneRoute: only explicitly marked voice-input owners.\n"
            "    iget-boolean v7, p0, Ll6/f;->quickBuiltinMicrophone:Z\n"
            "    if-eqz v7, :quick_mic_start\n"
            "    iget-object v7, p0, Ll6/f;->G:Landroid/media/AudioManager;\n"
            f"    invoke-static {{v6, v7}}, {PORT}->beforeStart(Landroid/media/AudioRecord;Landroid/media/AudioManager;)V\n"
            "    :quick_mic_start\n" + old + "\n"
            f"    invoke-static {{v6}}, {PORT}->afterStart(Landroid/media/AudioRecord;)V")
        body = replace(body, old, new)
        old = "    invoke-direct {p0}, Ll6/f;->z()V"
        cleanup = ("    iget-object v6, p0, Ll6/f;->w:Landroid/media/AudioRecord;\n"
                   f"    invoke-static {{v6}}, {PORT}->end(Landroid/media/AudioRecord;)V\n")
        body = replace(body, old, cleanup + old, 2)
        body = replace(body, "    return v0", "    if-nez v0, :quick_mic_mode_started\n"
                       + cleanup + "    :quick_mic_mode_started\n    return v0")
        # Null record-mode is a third explicit false-return path after start.
        tail = body.index('    const-string v0, "mRecordMode is null"')
        end = body.index("    :catch_0", tail)
        body = body[:tail] + replace(body[tail:end], "    return v5", cleanup + "    return v5") + body[end:]
        return body

    text = method(text, ".method private I()Z", start)

    def stop(body):
        old = "    iget-object v0, p0, Ll6/f;->w:Landroid/media/AudioRecord;"
        return replace(body, old, old + "\n\n"
                       "    # Cancel callbacks and restore before original asynchronous teardown.\n"
                       f"    invoke-static {{v0}}, {PORT}->end(Landroid/media/AudioRecord;)V")

    text = method(text, ".method private L()V", stop)
    text += ("\n\n# Marked only by the two original voice-input constructors.\n"
             ".method public final quickPreferBuiltinMicrophone()V\n"
             "    .locals 1\n"
             "    const/4 v0, 0x1\n"
             "    iput-boolean v0, p0, Ll6/f;->quickBuiltinMicrophone:Z\n"
             "    return-void\n"
             ".end method\n")
    return text


def wrapper(text):
    return method(text, ".method public release()V", lambda body: replace(
        body, "    invoke-super {p0}, Landroid/media/AudioRecord;->release()V",
        f"    invoke-static {{p0}}, {PORT}->end(Landroid/media/AudioRecord;)V\n"
        "    invoke-super {p0}, Landroid/media/AudioRecord;->release()V"))


def voice(text):
    old = "    invoke-direct {v3, v14, v15, v4}, Ll6/f;-><init>(III)V"
    return replace(text, old, old + "\n\n"
                   "    invoke-virtual {v3}, Ll6/f;->quickPreferBuiltinMicrophone()V")


def sha(content):
    return hashlib.sha256(content).hexdigest()


def generate(source, output):
    source, output = source.resolve(), output.resolve()
    if source == output or source in output.parents or output in source.parents:
        raise ValueError("Source and output trees must be separate, neither nested in the other")
    pending, manifest = [], []
    for relative, expected in PINS.items():
        before = (source / relative).read_bytes()
        if sha(before) != expected:
            raise ValueError(f"Unrecognized or already patched source: {relative}")
        transform = recorder if relative == RECORDER else wrapper if relative == WRAPPER else voice
        after = transform(before.decode("utf-8")).encode("utf-8")
        pending.append((relative, after))
        manifest.append({"path": relative, "source_sha256": sha(before), "patched_sha256": sha(after)})
    # All pins and transformations must pass before writing anything.
    output.mkdir(parents=True, exist_ok=True)
    for relative, content in pending:
        path = output / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    (output / "microphone-route-manifest.json").write_text(
        json.dumps({"format": 1, "files": manifest}, indent=2) + "\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(generate(args.source, args.output), indent=2))
