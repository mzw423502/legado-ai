#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Keep tracing APIs used by the Android test runner in the optimized target APK.
Keep the upstream minSdk23 and R8 optimization: HtmlUnit has API26 method-handle
branches that upstream R8 must eliminate or rewrite for older Android devices.
"""
from pathlib import Path
p = Path(__file__).resolve().parent / 'install.py'
text = p.read_text(encoding='utf-8')
old = '# MEMORY_RECOVERY_CANDIDATE_BUILD'
if old in text:
    text = text[:text.index(old)]
marker = '# MEMORY_TRACING_TEST_COMPATIBILITY'
if marker not in text:
    text += '''
# MEMORY_TRACING_TEST_COMPATIBILITY
# AndroidJUnitRunner references this shared runtime dependency, even when the
# release target itself does not call all its methods. Do not strip its API.
pro = ROOT / 'app/proguard-rules.pro'
if '# Memory runner shared tracing API' not in pro.read_text(encoding='utf-8'):
    with pro.open('a', encoding='utf-8') as f:
        f.write('\\n# Memory runner shared tracing API\\n-keep class androidx.tracing.** { *; }\\n')
'''
p.write_text(text, encoding='utf-8')
print('Retained tracing API; upstream minSdk23 and R8 optimization unchanged')
