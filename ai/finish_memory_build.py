#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
from pathlib import Path
root = Path(__file__).resolve().parents[1]

def patch(path, old, new):
    p = root / path
    text = p.read_text(encoding='utf-8')
    if new in text:
        return
    if text.count(old) != 1:
        raise RuntimeError('Unexpected source: ' + path)
    p.write_text(text.replace(old, new), encoding='utf-8')

patch('ai/core/ContextComposer.kt',
      'val maxOut = min(p.settings.outputTokens, if (purpose == Purpose.PLAN) 4096 else 32768)',
      'val maxOut = if (purpose == Purpose.PLAN) p.settings.outputTokens else min(p.settings.outputTokens, 32768)')
patch('ai/tests/unit/MemoryV2Checks.kt',
      '    @JvmStatic fun main(args: Array<String>) { runAll() }',
      '''    fun case41PlanningRetainsConfiguredOutputBudget() {
        val f=F(); f.readyBody()
        check(ContextComposer.build(f.get(),Purpose.PLAN).maxTokens == f.get().settings.outputTokens)
        check(ContextComposer.build(f.get(),Purpose.MEMORY).maxTokens <= 4096)
    }
    @JvmStatic fun main(args: Array<String>) { runAll() }''')
patch('ai/tests/unit/MemoryV2Checks.kt',
      'check(tests.size == 40) { "Expected all 40 regression scenarios, found ${tests.size}" }',
      'check(tests.size == 41) { "Expected all 41 regression scenarios, found ${tests.size}" }')
p = root / 'ai/install.py'
text = p.read_text(encoding='utf-8')
if '# MEMORY_NATIVE_BUILD_FIXES' not in text:
    text += '''
# MEMORY_NATIVE_BUILD_FIXES
# Instrumentation and target share a class loader. Their independently shrunk j$
# libraries must not shadow incompatible constructors or obfuscated class names.
build = ROOT / 'app/build.gradle'
text = build.read_text(encoding='utf-8')
if '// Shared complete desugared runtime' not in text:
    build.write_text(text + """
// Shared complete desugared runtime for release/instrumentation class-loader compatibility.
tasks.matching { it.name.startsWith('l8DexDesugarLib') }.configureEach { task ->
    task.keepRulesConfigurations.addAll(['-dontobfuscate', '-dontoptimize', '-keep class ** { *; }'])
}
""", encoding='utf-8')
manifest_record = ROOT / 'ai/INTEGRATION_APPLIED.json'
record = json.loads(manifest_record.read_text(encoding='utf-8'))
record['package'] = package_base + '.release'
record['memory_version'] = 2
record['planner_output_budget'] = 'author setting; not constrained by the extraction cap'
manifest_record.write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
'''
    p.write_text(text, encoding='utf-8')
print('Applied planner-budget and shared-desugared-runtime fixes')
