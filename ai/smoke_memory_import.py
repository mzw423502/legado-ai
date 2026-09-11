#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Black-box UI verification of an already-built APK. Synthetic fiction only; no API key."""
import hashlib, json, re, subprocess, time, traceback, uuid
from pathlib import Path
import xml.etree.ElementTree as ET

OUT=Path('smoke-results'); OUT.mkdir(exist_ok=True)
PKG='com.mzw.legado.ai.memory.release'
checks=[]
def adb(*args, binary=False, check=True, timeout=45):
    r=subprocess.run(['adb',*args],capture_output=True,timeout=timeout,check=check)
    return r.stdout if binary else r.stdout.decode('utf-8',errors='replace')
def dump():
    for attempt in range(4):
        try:
            adb('shell','uiautomator','dump','/sdcard/memory-window.xml')
            data=adb('exec-out','cat','/sdcard/memory-window.xml')
            start=data.find('<?xml')
            if start>=0:
                (OUT/'last-window.xml').write_text(data[start:],encoding='utf-8')
                return ET.fromstring(data[start:])
        except Exception:
            time.sleep(1)
    raise RuntimeError('Could not read Android UI hierarchy')
def node(root, labels, contains=False):
    if isinstance(labels,str): labels=[labels]
    for n in root.iter('node'):
        values=[n.get('text',''),n.get('content-desc',''),n.get('resource-id','')]
        if any((label in v if contains else label==v) for label in labels for v in values):
            return n
    return None
def tap(n):
    b=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
    if len(b)!=4: raise RuntimeError('Missing tap coordinates')
    adb('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2));time.sleep(.7)
def click(labels,scroll=False,contains=False):
    for attempt in range(12 if scroll else 5):
        root=dump(); n=node(root,labels,contains)
        if n is not None:
            tap(n);return
        if scroll: adb('shell','input','swipe','540','1580','540','530','300')
        time.sleep(.8)
    raise RuntimeError('Missing control: '+str(labels))
def wait(labels,contains=False):
    for attempt in range(12):
        root=dump()
        if node(root,labels,contains) is not None:return root
        time.sleep(1)
    raise RuntimeError('Missing screen: '+str(labels))
def back():
    adb('shell','input','keyevent','4');time.sleep(.8)
def screenshot(name):
    (OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',binary=True))
def passed(name):
    checks.append(name); print('PASS '+name,flush=True)
def fixture():
    uid=lambda:str(uuid.uuid4())
    chapters=[{'id':uid(),'ordinal':i,'title':f'第{i}章 旧稿','content':f'旧稿第{i}章：林砚在码头核实旧信。\n'*160,
        'memoryAfter':f'截至第{i}章的旧台账：线索尚待核实。','createdAt':i} for i in range(1,13)]
    body=('草稿保留核验：林砚收起铜扣，准备前往灯塔。\n'*300)[:5476]
    return {'schema':1,'id':uid(),'title':'记忆升级验收','createdAt':1,'updatedAt':1,'revision':0,
        'settings':{'bible':'总设定保持原样。'*600,'outline':'调查旧信，不提前揭晓灯塔秘密。','director':'核实线索，不重写旧章。',
            'targetChars':5000,'contextTokens':128000,'outputTokens':16384,'recentChapters':3,'maxParts':8},
        'chapters':chapters,'seedMemory':'','draft':{'chapterId':uid(),'ordinal':13,'stage':'MEMORY','plan':'前往灯塔核实旧信。',
            'body':body,'memoryAfter':'旧版截断的记忆结果','completedParts':2,'needsReview':False,'note':'保留草稿'},
        'archives':[{'id':uid(),'fromOrdinal':13,'prefixIds':[c['id'] for c in chapters], 'chapters':[],
            'draft':{'chapterId':uid(),'ordinal':13,'stage':'BODY','plan':'旧计划','body':'旧版本草稿也要保留。',
            'memoryAfter':'','completedParts':1,'needsReview':True,'note':''},'createdAt':2}],
        'receipts':[],'activeRun':None,'pendingPublish':True,'deletedAt':None,'status':'长期记忆被截断，正文已保留'}

try:
    apk=Path('tested-build/app-app-release.apk')
    assert apk.is_file()
    (OUT/'tested-apk-sha256.txt').write_text(hashlib.sha256(apk.read_bytes()).hexdigest())
    result=adb('install','-r',str(apk),timeout=120)
    assert 'Success' in result,result
    passed('release_apk_installs')
    adb('shell','wm','size','1080x1920');adb('shell','wm','density','320')
    original=fixture(); src=OUT/'legacy_fixture.json';src.write_text(json.dumps(original,ensure_ascii=False),encoding='utf-8')
    adb('push',str(src),'/sdcard/Download/legacy_fixture.json')
    adb('logcat','-c')
    adb('shell','am','start','-n',PKG+'/io.legado.app.ui.welcome.WelcomeActivity')
    time.sleep(4)
    for attempt in range(10):
        root=dump()
        ai=node(root,'AI 创作')
        if ai is not None:break
        ok=node(root,['同意','Agree','AGREE','确认','确定','OK','知道了','我知道了','允许','Allow','Close','关闭'])
        if ok is not None:tap(ok)
        else:time.sleep(1)
    click('AI 创作');wait('恢复 AI 小说备份');screenshot('01_ai_home')
    passed('native_bookshelf_ai_entry_opens')
    click('恢复 AI 小说备份')
    time.sleep(2); root=dump()
    if node(root,'legacy_fixture.json') is None:
        menu=node(root,['Show roots','显示根目录','导航抽屉','Navigate up','向上导航'])
        if menu is not None:tap(menu)
        else:adb('shell','input','tap','65','110')
        click(['Downloads','下载','Download'])
    click('legacy_fixture.json');wait('只整理记忆，收录本章');screenshot('02_imported_memory_pending')
    passed('legacy_json_imports_with_memory_pending_draft')
    click('只读本章草稿（不调用 API）');root=wait('第13章 · 已保存草稿');screenshot('03_preserved_draft')
    texts=[n.get('text','') for n in root.iter('node')]
    assert original['draft']['body'] in texts,'Draft text changed in UI'
    passed('draft_5476_characters_retained_exactly')
    back();wait('只整理记忆，收录本章')
    click('导出与备份',scroll=True);click('完整 AI 小说备份 · JSON')
    time.sleep(2);click(['SAVE','Save','保存'])
    time.sleep(2)
    root=dump();overwrite=node(root,['Replace','替换'])
    if overwrite is not None:tap(overwrite)
    wait('已保存到你选择的位置',contains=True);click('知道了')
    downloads=OUT/'downloads'
    adb('pull','/sdcard/Download',str(downloads),timeout=90)
    exported=list(downloads.rglob('*.legado-ai.json'))
    assert len(exported)==1,[str(p) for p in exported]
    restored=json.loads(exported[0].read_text(encoding='utf-8'))
    for before,after in zip(original['chapters'],restored['chapters']):
        for key,val in before.items():assert after[key]==val,('chapter changed',key)
    assert len(restored['chapters'])==12
    assert restored['settings']==original['settings']
    for key,val in original['draft'].items():assert restored['draft'][key]==val,('draft changed',key)
    assert restored['archives'][0]['draft']['body']==original['archives'][0]['draft']['body']
    assert restored['receipts']==[]
    passed('android_import_export_retains_12_chapters_settings_draft_archive_without_api')
    back();wait('只整理记忆，收录本章');click('阅读')
    time.sleep(4);screenshot('04_native_reader')
    assert PKG in adb('shell','dumpsys','activity','activities')
    passed('original_native_reader_launches_for_imported_book')
    result={'status':'PASS','checks':checks,'scope':'Release APK, API35 emulator, native UI import/export, synthetic data, no API key',
        'not_tested':['real DeepSeek requests','user actual backup','physical phone','old APK overlay upgrade']}
except Exception as exc:
    traceback.print_exc()
    try:screenshot('failure')
    except Exception:pass
    result={'status':'FAIL','checks':checks,'error':str(exc)}
finally:
    (OUT/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    (OUT/'logcat.txt').write_text(adb('logcat','-d',check=False),encoding='utf-8')
print(json.dumps(result,ensure_ascii=False,indent=2),flush=True)
raise SystemExit(0 if result['status']=='PASS' else 1)
