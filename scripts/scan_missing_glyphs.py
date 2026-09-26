# -*- coding: utf-8 -*-
"""扫描所有 UI 文案，找出 ArkPixel-12px 字体缺字形的字符。
用法见同目录 README.md；按脚本位置自动定位仓库 app/src/main。"""
import os, re, sys
from fontTools.ttLib import TTFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                    '..', 'app', 'src', 'main'))
font = TTFont(os.path.join(ROOT, 'assets', 'fonts', 'ArkPixel-12px.ttf'))
cmap = font.getBestCmap()

CJK = re.compile(r'[\u3400-\u9fff\uf900-\ufaff\u3000-\u303f\uff00-\uffef\u2460-\u24ff\u2018-\u201f\u2026]')
results = {}  # char -> set of (file, kind)

def add(ch, path, kind):
    if ord(ch) not in cmap:
        results.setdefault(ch, set()).add((os.path.relpath(path, ROOT), kind))

str_re = re.compile(r'<string name="[^"]+">(.*?)</string>', re.S)
text_re = re.compile(r'android:text="([^"]*)"')
java_str_re = re.compile(r'"([^"\\]*)"', re.S if False else 0)

for dirpath, dirs, files in os.walk(ROOT):
    dirs[:] = [d for d in dirs if d not in ('build',)]
    for fn in files:
        p = os.path.join(dirpath, fn)
        try:
            s = open(p, encoding='utf-8', errors='ignore').read()
        except Exception:
            continue
        if fn.endswith('.xml') and 'values' in dirpath and fn == 'strings.xml':
            for m in str_re.finditer(s):
                for ch in m.group(1):
                    if CJK.match(ch):
                        add(ch, p, 'strings')
        elif fn.endswith('.xml') and 'layout' in dirpath:
            for m in text_re.finditer(s):
                for ch in m.group(1):
                    if CJK.match(ch):
                        add(ch, p, 'layout-text')
        elif fn.endswith('.java'):
            for m in re.finditer(r'"((?:[^"\\]|\\.)*)"', s):
                lit = m.group(1)
                if '\\n' in lit or '\\u' in lit:
                    lit = lit.replace('\\n', '\n').replace('\\"', '"')
                for ch in lit:
                    if CJK.match(ch):
                        add(ch, p, 'java')

for ch in sorted(results):
    files = sorted(results[ch])
    print(f'U+{ord(ch):04X} {ch}  ({len(files)} 处)')
    for f, kind in files:
        print(f'    [{kind}] {f}')
