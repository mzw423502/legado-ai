#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Materialize a checksum-verified source patch transported in four text parts.
No application data, credentials or signing keys are included in this patch.
The CI job commits the resulting readable ai/ source files after verification.
"""
from pathlib import Path
import base64
import hashlib
import lzma
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
parts = root / 'ai/memory-v2-delivery'
encoded = ''.join(''.join((parts / f'part{i}.b64').read_text().split()) for i in range(1, 5))
patch = lzma.decompress(base64.b64decode(encoded, validate=True))
expected = '75012a546aa96cf252820dbf794939d87cb96186cfb742eb6f42993f42173d66'
if hashlib.sha256(patch).hexdigest() != expected:
    raise RuntimeError('Source patch checksum mismatch; refusing to apply')
for line in patch.decode('utf-8').splitlines():
    if line.startswith(('--- ', '+++ ')):
        name = line[4:]
        if name != '/dev/null' and (not name.startswith(('a/ai/', 'b/ai/')) or '..' in Path(name).parts):
            raise RuntimeError(f'Unexpected patch target: {name}')
with tempfile.TemporaryDirectory() as directory:
    path = Path(directory) / 'memory-v2.patch'
    path.write_bytes(patch)
    check = subprocess.run(['git', 'apply', '--check', str(path)], cwd=root, capture_output=True)
    if check.returncode == 0:
        subprocess.run(['git', 'apply', str(path)], cwd=root, check=True)
        print('Verified and applied memory v2 source patch')
    else:
        reverse = subprocess.run(['git', 'apply', '--reverse', '--check', str(path)], cwd=root, capture_output=True)
        if reverse.returncode != 0:
            raise RuntimeError(check.stderr.decode('utf-8', errors='replace'))
        print('Memory v2 source is already materialized')
