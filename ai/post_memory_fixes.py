#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Small readable fixes applied after the checksum-verified memory-v2 patch."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
def replace(path, old, new):
    p = root / path
    text = p.read_text(encoding='utf-8')
    if new in text:
        return
    if text.count(old) != 1:
        raise RuntimeError('Unexpected source at ' + path)
    p.write_text(text.replace(old,new),encoding='utf-8')
replace('ai/tests/unit/MemoryV2Checks.kt',
    'val tests=MemoryV2Checks::class.java.declaredMethods.filter{it.name.startsWith("case")}.sortedBy{it.name}',
    'val tests=MemoryV2Checks::class.java.declaredMethods.filter{it.name.matches(Regex("case[0-9]{2}[A-Za-z]+")) && it.parameterCount == 0 && !it.isSynthetic}.sortedBy{it.name}\n        check(tests.size == 40) { "Expected all 40 regression scenarios, found ${tests.size}" }')
replace('ai/core/ContextComposer.kt',
    'summary 限${if (compact) 250 else 450}字；records 最多${if (compact) 8 else 20}条，每条 value 最多180字，entity/key 最多40字。\nevidence 必须逐字摘录下面正文中的4—100字，不能来自规划或旧记忆。没有证据不要建立记录。',
    'summary 限${if (compact) 180 else 450}字；records 最多${if (compact) (if (p.settings.outputTokens < 2048) 3 else 6) else 16}条，每条 value 最多${if (compact) 100 else 160}字，entity/key 最多${if (compact) 24 else 40}字。\nevidence 必须逐字摘录下面正文中的4—${if (compact) 40 else 80}字，不能来自规划或旧记忆。没有证据不要建立记录。')
replace('ai/tests/unit/MemoryV2Checks.kt', 'contains("最多8条")', 'contains("最多6条")')
print('Applied Kotlin test discovery and compact-memory fixes')
