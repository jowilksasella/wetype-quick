#!/usr/bin/env python3
"""Add an explicit experimental revision label without recompiling resources.

Only app/IME labels and versionName/versionCode change. Existing string indices,
resource IDs, permissions, package name, SDK declarations and components remain.
"""
from pathlib import Path
import hashlib
import json
import struct
import zipfile

BASE = Path(__file__).resolve().parents[1]
EXPECTED = 'c036f49444b3230fc2fc7c119ae79f32b33766374fd5dc6d48246dea378ff889'
VERSION = '3.5.4-quick.4'
LABEL = '微信输入法·速成 Q4'
VERSION_CODE = 57204

def n8(data, pos):
    first = data[pos]
    return (((first & 127) << 8) | data[pos+1], pos+2) if first & 128 else (first, pos+1)

def n16(data, pos):
    first = struct.unpack_from('<H', data, pos)[0]
    return (((first & 32767) << 16) | struct.unpack_from('<H', data, pos+2)[0], pos+4) if first & 32768 else (first, pos+2)

def p8(value):
    return bytes([(value >> 8) | 128, value & 255]) if value > 127 else bytes([value])

def p16(value):
    return struct.pack('<HH', (value >> 16) | 32768, value & 65535) if value > 32767 else struct.pack('<H', value)

def decode_pool(chunk):
    _, hs, size, count, styles, flags, start, style_start = struct.unpack_from('<HHIIIIII', chunk)
    values = []
    for i in range(count):
        pos = start + struct.unpack_from('<I', chunk, hs + i*4)[0]
        if flags & 256:
            _, pos = n8(chunk, pos)
            length, pos = n8(chunk, pos)
            values.append(chunk[pos:pos+length].decode('utf-8'))
        else:
            length, pos = n16(chunk, pos)
            values.append(chunk[pos:pos+length*2].decode('utf-16le'))
    return values, (hs, styles, flags, chunk[hs+count*4:hs+(count+styles)*4], chunk[style_start:] if style_start else b'')

def encode_pool(values, extra):
    hs, styles, flags, style_offsets, style_data = extra
    offsets, payload = [], bytearray()
    for value in values:
        offsets.append(len(payload))
        utf16 = value.encode('utf-16le')
        if flags & 256:
            utf8 = value.encode('utf-8')
            payload += p8(len(utf16)//2) + p8(len(utf8)) + utf8 + b'\0'
        else:
            payload += p16(len(utf16)//2) + utf16 + b'\0\0'
    payload += bytes(-len(payload) % 4)
    start = hs + 4*len(values) + len(style_offsets)
    style_start = start + len(payload) if styles else 0
    size = start + len(payload) + len(style_data)
    assert hs == 28
    return (struct.pack('<HHIIIIII', 1, hs, size, len(values), styles, flags & ~1, start, style_start)
            + b''.join(struct.pack('<I', x) for x in offsets) + style_offsets + payload + style_data)

def mark(data):
    chunks, pos, values, extra = [], 8, None, None
    while pos < len(data):
        kind, hs, size = struct.unpack_from('<HHI', data, pos)
        assert size >= hs >= 8 and pos+size <= len(data)
        chunk = bytearray(data[pos:pos+size])
        if kind == 1:
            assert values is None
            values, extra = decode_pool(chunk)
        chunks.append((kind, chunk)); pos += size
    assert pos == len(data) and values is not None
    version_index, label_index = len(values), len(values)+1
    values.extend([VERSION, LABEL])
    edits = []
    for kind, chunk in chunks:
        if kind != 0x102:
            continue
        element = values[struct.unpack_from('<I', chunk, 20)[0]]
        attr_start, attr_size, attr_count = struct.unpack_from('<HHH', chunk, 24)
        assert attr_size == 20
        attrs = {}
        for i in range(attr_count):
            offset = 16+attr_start+i*attr_size
            name = values[struct.unpack_from('<I', chunk, offset+4)[0]]
            attrs[name] = offset
        def string_value(name):
            offset = attrs[name]
            index = struct.unpack_from('<I', chunk, offset+16)[0]
            return values[index] if chunk[offset+15] == 3 else None
        def set_string(name, index):
            offset = attrs[name]
            struct.pack_into('<I', chunk, offset+8, index)
            chunk[offset+15] = 3
            struct.pack_into('<I', chunk, offset+16, index)
            edits.append(element + '.' + name)
        if element == 'manifest':
            assert string_value('package') == 'com.tencent.wetype'
            assert string_value('versionName') == '3.5.4'
            set_string('versionName', version_index)
            offset = attrs['versionCode']
            assert struct.unpack_from('<I', chunk, offset+16)[0] == 57201
            struct.pack_into('<I', chunk, offset+8, 0xffffffff)
            chunk[offset+15] = 0x10
            struct.pack_into('<I', chunk, offset+16, VERSION_CODE)
            edits.append('manifest.versionCode')
        elif element == 'application':
            set_string('label', label_index)
        elif element == 'service' and string_value('name') == 'com.tencent.wetype.plugin.hld.WxHldService':
            set_string('label', label_index)
    assert sorted(edits) == ['application.label', 'manifest.versionCode', 'manifest.versionName', 'service.label']
    body = b''.join(encode_pool(values, extra) if kind == 1 else bytes(chunk) for kind, chunk in chunks)
    return struct.pack('<HHI', 3, 8, 8+len(body))+body, edits

