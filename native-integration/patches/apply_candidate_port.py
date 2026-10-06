#!/usr/bin/env python3
"""Append the verified model port to a NEW i0.smali output; never edit the input."""
import argparse
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("source", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
source = args.source.resolve()
output = args.output.resolve()
root = Path(__file__).resolve().parents[2]
if source == output or output.is_relative_to(root / "decoded"):
    parser.error("output must be a new copy outside the original decoded tree")
if output.exists():
    parser.error("refusing to replace an existing output; use a fresh staging path")
text = source.read_text()
required = [
    ".class public final Lcom/tencent/wetype/plugin/hld/model/i0;",
    ".field private static p:Ljava/util/List;",
    ".field private static final q:Ljava/lang/Object;",
    ".field private static volatile B:I",
    ".field private static V:Z",
    ".field private static D:Lcom/tencent/wetype/plugin/hld/model/Z;",
    ".field private static final r:Ljava/util/ArrayList;",
    ".field private static final s:Ljava/util/List;",
    ".field private static final u:Ljava/util/ArrayList;",
    ".field private static final v:Ljava/util/ArrayList;",
    ".field private static final g:Ljava/util/ArrayList;",
    ".field private static m:Ljava/util/ArrayList;",
    ".method private final N5(Ljava/util/ArrayList;IZZLcom/tencent/wetype/plugin/hld/candidate/w;Z)V",
]
for expected in required:
    if expected not in text:
        parser.error("original ABI does not match: " + expected)
if ".method public final quickPublish(" in text or ".method public final quickInvalidate(" in text:
    parser.error("Quick publication methods are already present")
fragment = Path(__file__).with_name("i0.quickPublish.smali").read_text()
fragment += "\n" + Path(__file__).with_name("i0.quickInvalidate.smali").read_text()
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(text.rstrip() + "\n\n" + fragment)
print(output)
