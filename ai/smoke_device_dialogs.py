#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
from pathlib import Path
p=Path(__file__).with_name('smoke_memory_import.py')
s=p.read_text(encoding='utf-8')
s=s.replace("                return ET.fromstring(data[start:])", """                root=ET.fromstring(data[start:])
                labels=' '.join(n.get('text','') for n in root.iter('node'))
                if 'Pixel Launcher' in labels and (\"isn't responding\" in labels or '无响应' in labels):
                    for n in root.iter('node'):
                        if n.get('text') in ['Close app','关闭应用']:
                            b=list(map(int,re.findall(r'\\d+',n.get('bounds',''))))
                            adb('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2))
                            time.sleep(2)
                            break
                    continue
                return root""")
s=s.replace('    time.sleep(4)\n    for attempt in range(10):','    time.sleep(10)\n    for attempt in range(16):')
s=s.replace("        else:time.sleep(1)\n    click('AI 创作')", """        else:
            if node(root,['Help','帮助','Update log','更新日志','Cancel','取消']) is not None:back()
            else:
                positive=node(root,'android:id/button1')
                if positive is not None:tap(positive)
                else:time.sleep(1)
    click('AI 创作')""")
p.write_text(s,encoding='utf-8')
print('Prepared black-box UI fixture for first-launch system dialogs')
