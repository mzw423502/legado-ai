#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Apply the AI extension to the pinned Legado source. No network or credentials."""
from pathlib import Path
import json
import shutil
import os

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main'
JAVA = APP / 'java/io/legado/app'
PATCHES = []

def replace(path, old, new, count=1):
    path = Path(path)
    text = path.read_text(encoding='utf-8')
    if old not in text and new in text:
        return
    found = text.count(old)
    if found != count:
        raise RuntimeError(f'Pinned source mismatch: {path.relative_to(ROOT)} expected {count}, got {found}')
    path.write_text(text.replace(old, new), encoding='utf-8')
    PATCHES.append(str(path.relative_to(ROOT)))

for group, target in [('core', JAVA / 'ai/core'), ('android', JAVA / 'ai')]:
    target.mkdir(parents=True, exist_ok=True)
    for src in (ROOT / 'ai' / group).glob('*.kt'):
        shutil.copy2(src, target / src.name)
for src in (ROOT / 'ai/assets').glob('*'):
    (APP / 'assets').mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, APP / 'assets' / src.name)

# Android API-level and UI integration guards.
reader = JAVA / 'ai/AiReader.kt'
replace(reader, 'postEvent(EventBus.UP_BOOKSHELF, true)', 'postEvent(EventBus.UP_BOOKSHELF, bookUrl)', 2)
replace(reader, 'require(appDb.bookDao.getBook(bookUrl)?.totalChapterNum ?: 0 > 0)',
    'require((appDb.bookDao.getBook(bookUrl)?.totalChapterNum ?: 0) > 0)')
service = JAVA / 'ai/AiWritingService.kt'
replace(service, 'stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()',
    'if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true); stopSelf()')
activity = JAVA / 'ai/AiActivity.kt'
replace(activity, 'private var stopped = false', 'private var stopped = false\n    private var pageEpoch = 0')
replace(activity, 'page("AI 创作"); label', 'page("AI 创作", settings = false); label')
replace(activity, 'persistForm?.invoke(); persistForm = null; watchId = null; pageId = null',
    'persistForm?.invoke(); persistForm = null; watchId = null; pageId = null; pageEpoch++')
replace(activity, 'setOnClickListener { action() }',
    'setOnClickListener { runCatching { action() }.onFailure { info(it.message ?: "操作未完成") } }')
replace(activity, '.setPositiveButton(positive) { _, _ -> action() }.show()',
    '.setPositiveButton(positive) { _, _ -> runCatching { action() }.onFailure { info(it.message ?: "操作未完成") } }.show()')
replace(activity, 'fun <T> work(block: () -> T, done: (T) -> Unit) {\n        AiRuntime.io.execute {',
    'fun <T> work(block: () -> T, done: (T) -> Unit) {\n        val epoch = pageEpoch\n        AiRuntime.io.execute {')
replace(activity, 'if (!isFinishing && !isDestroyed) done(result)',
    'if (!isFinishing && !isDestroyed && pageEpoch == epoch) runCatching { done(result) }.onFailure { info(it.message ?: "操作未完成") }')
replace(activity, 'gravity = Gravity.TOP or Gravity.START; setText(value); isSaveEnabled = false',
    'gravity = Gravity.TOP or Gravity.START; setSingleLine(lines == 1); setText(value); isSaveEnabled = false')
# An imported summary is valid only through the copied final chapter, not before chapter one.
replace(JAVA / 'ai/core/ProjectActions.kt', 'pendingPublish = true, seedMemory = authorMemory,',
    'pendingPublish = true, seedMemory = "",')


# Add visible entry points while preserving the original reading UI.
# The bookshelf entry is a static ALWAYS action: it must be visible without opening the overflow menu.
res = APP / 'res/values/ai_ids.xml'
res.write_text('<resources><item type="id" name="menu_ai_creation" /></resources>\n', encoding='utf-8')
shelf_menu = APP / 'res/menu/main_bookshelf.xml'
replace(shelf_menu, '    <item\n        android:id="@+id/menu_search"',
    '    <item\n        android:id="@id/menu_ai_creation"\n        android:icon="@drawable/ic_edit"\n        android:title="AI 创作"\n        app:showAsAction="always|withText" />\n\n    <item\n        android:id="@+id/menu_search"')
shelf = JAVA / 'ui/main/bookshelf/BaseBookshelfFragment.kt'
replace(shelf, 'R.id.menu_remote -> startActivity<RemoteBookActivity>()',
    'R.id.menu_ai_creation -> io.legado.app.ai.AiActivity.open(requireContext())\n            R.id.menu_remote -> startActivity<RemoteBookActivity>()')
read = JAVA / 'ui/book/read/ReadBookActivity.kt'
replace(read, 'menuInflater.inflate(R.menu.book_read, menu)',
    'menuInflater.inflate(R.menu.book_read, menu)\n        menu.add(0, R.id.menu_ai_creation, 0, "AI 创作／续写副本").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)')
replace(read, 'override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {\n        when (item.itemId) {',
    'override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {\n        if (item.itemId == R.id.menu_ai_creation) { io.legado.app.ai.AiActivity.open(this, ReadBook.book?.bookUrl); return true }\n        when (item.itemId) {')
my = JAVA / 'ui/main/my/MyFragment.kt'
replace(my, 'menuInflater.inflate(R.menu.main_my, menu)',
    'menuInflater.inflate(R.menu.main_my, menu)\n        menu.add(0, R.id.menu_ai_creation, 0, "AI 创作").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)')
replace(my, 'R.id.menu_help -> showHelp("appHelp")',
    'R.id.menu_ai_creation -> io.legado.app.ai.AiActivity.open(requireContext())\n            R.id.menu_help -> showHelp("appHelp")')

# Normal books are unaffected. The hook returns immediately for non-managed paths.
replace(JAVA / 'data/entities/Book.kt', '    fun delete() {\n',
    '    fun delete() {\n        io.legado.app.ai.AiRuntime.beforeDelete(bookUrl)\n')
replace(JAVA / 'ui/book/manage/BookshelfManageViewModel.kt', '            appDb.bookDao.delete(*books.toTypedArray())',
    '            books.forEach { io.legado.app.ai.AiRuntime.beforeDelete(it.bookUrl) }\n            appDb.bookDao.delete(*books.toTypedArray())')
replace(JAVA / 'model/localBook/LocalBook.kt', '    fun getChapterList(book: Book): ArrayList<BookChapter> {\n',
    '    fun getChapterList(book: Book): ArrayList<BookChapter> {\n        if (book.bookUrl.contains("/ai_reading/")) {\n            io.legado.app.ai.AiRuntime.init(appCtx)\n            io.legado.app.ai.AiRuntime.reader.managedChapters(book)?.let { return it }\n        }\n')
replace(JAVA / 'help/update/AppUpdate.kt', '        AppUpdateGitHub\n', '        io.legado.app.ai.AiAppUpdate\n')
replace(JAVA / 'help/update/AppUpdate.kt', '        AppUpdateGitHub.checkBeta(scope)', '        io.legado.app.ai.AiAppUpdate.check(scope)')

manifest = APP / 'AndroidManifest.xml'
replace(manifest, '        <!-- 主入口 -->', '''        <!-- Independent AI module; never exported to other applications. -->
        <activity android:name=".ai.AiActivity" android:exported="false"
            android:windowSoftInputMode="adjustResize" />
        <service android:name=".ai.AiWritingService" android:exported="false"
            android:foregroundServiceType="dataSync" android:stopWithTask="false" />
        <meta-data android:name="firebase_analytics_collection_deactivated" android:value="true" />
        <meta-data android:name="firebase_crashlytics_collection_enabled" android:value="false" />
        <!-- 主入口 -->''')
build = ROOT / 'app/build.gradle'
replace(build, 'applicationId "com.legado.app"', 'applicationId "com.mzw.legado.ai"')
replace(build, 'versionCode = versionCodeValue', 'versionCode = 10003')
replace(build, 'versionName version', 'versionName "3.26082823-ai.3"')
text = build.read_text(encoding='utf-8')
if '// AI extension build settings' not in text:
    build.write_text(text + '''\n// AI extension build settings
android {
    testBuildType "release"
    buildTypes.all { manifestPlaceholders.put("app_name", "阅读 · AI") }
}
''', encoding='utf-8')
services = ROOT / 'app/google-services.json'
services.write_text(services.read_text(encoding='utf-8').replace('com.legado.app', 'com.mzw.legado.ai'), encoding='utf-8')
pro = ROOT / 'app/proguard-rules.pro'
if '# AI extension' not in pro.read_text(encoding='utf-8'):
    with pro.open('a', encoding='utf-8') as f:
        f.write('\n# AI extension: keep explicit entry points for on-device verification.\n-keep class io.legado.app.ai.** { *; }\n')
for group, target in [('unit', ROOT / 'app/src/test/java/io/legado/app/ai'),
                      ('device', ROOT / 'app/src/androidTest/java/io/legado/app/ai')]:
    target.mkdir(parents=True, exist_ok=True)
    for src in (ROOT / 'ai/tests' / group).glob('*.kt'):
        shutil.copy2(src, target / src.name)
(ROOT / 'ai/INTEGRATION_APPLIED.json').write_text(json.dumps({
    'upstream_commit': '3046111c1718e7b67c2ad468e54edf295ec05820',
    'package': 'com.mzw.legado.ai.release', 'patched_files': sorted(set(PATCHES)),
    'reader': 'original Legado native reader', 'source_engine': 'unchanged',
    'key_storage': 'Android Keystore; encrypted value in noBackupFilesDir',
    'visible_entry': 'bookshelf toolbar: AI 创作 (always)',
}, ensure_ascii=False, indent=2), encoding='utf-8')
print('Applied AI extension; original reading layout and source engine retained.')

# Set only in the recovery/coexistence build; default retains the exact original app ID.
package_base = os.environ.get("AI_PACKAGE_BASE", "com.mzw.legado.ai")
if package_base != "com.mzw.legado.ai":
    if package_base != "com.mzw.legado.ai.memory":
        raise RuntimeError("Unexpected recovery package")
    for path in [ROOT / "app/build.gradle", ROOT / "app/google-services.json"]:
        path.write_text(path.read_text(encoding="utf-8").replace("com.mzw.legado.ai", package_base), encoding="utf-8")
    path = ROOT / "app/build.gradle"
    path.write_text(path.read_text(encoding="utf-8").replace("阅读 · AI", "阅读 · AI 记忆版"), encoding="utf-8")

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
