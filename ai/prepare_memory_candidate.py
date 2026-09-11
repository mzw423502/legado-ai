#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Use a non-debuggable, non-minified recovery candidate with test-compatible libraries.
Release shrinking had removed androidx.tracing.Trace required by AndroidJUnitRunner.
Disabling shrinking here preserves all shared dependency APIs; tests remain mandatory.
The reader source code and persisted user-data schema are not modified by this setting.
"""
from pathlib import Path
p=Path(__file__).resolve().parent / 'install.py'
text=p.read_text(encoding='utf-8')
marker='# MEMORY_RECOVERY_CANDIDATE_BUILD'
if marker not in text:
    text += '''
# MEMORY_RECOVERY_CANDIDATE_BUILD
# Release APK stays non-debuggable. Retain shared dependency APIs for instrumentation
# instead of accidentally testing an APK with libraries removed under its runner.
build = ROOT / 'app/build.gradle'
text = build.read_text(encoding='utf-8')
if '// Memory recovery candidate: retain shared libraries' not in text:
    build.write_text(text + """
// Memory recovery candidate: retain shared libraries, do not change reader behavior.
android.buildTypes.release {
    minifyEnabled false
    shrinkResources false
    debuggable false
}
""", encoding='utf-8')
'''
    p.write_text(text,encoding='utf-8')
print('Configured non-debuggable recovery candidate without release shrinking')
