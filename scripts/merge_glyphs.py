# -*- coding: utf-8 -*-
"""把 UI 文案中 ArkPixel-12px 缺失的字形从 FusionPixel-12px 合并进来（干净版）。
注意：glyph 名必须从 cmap 表查映射（getGlyphName 是按 glyph 索引取名，不是码点！）"""
import os, re
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.pens.recordingPen import DecomposingRecordingPen
from fontTools.ttLib import TTFont

ROOT = r'd:\project\keydroidx_ecosystem\keydroidx-launcher\app\src\main'
ARK = os.path.join(ROOT, 'assets', 'fonts', 'ArkPixel-12px.ttf')
FUS = os.path.join(ROOT, 'assets', 'fonts', 'FusionPixel-12px.ttf')

CHAR = re.compile(r'[\u2e80-\u9fff\uf900-\ufaff\uff00-\uffef\u3000-\u30ff'
                  r'\u2460-\u24ff\u2018-\u201f\u2026\u00b7\u30fb]')

ui_chars = set()
str_re = re.compile(r'<string name="[^"]+">(.*?)</string>', re.S)
text_re = re.compile(r'android:text="([^"]*)"')

for dirpath, dirs, files in os.walk(ROOT):
    dirs[:] = [d for d in dirs if d != 'build']
    for fn in files:
        p = os.path.join(dirpath, fn)
        try:
            s = open(p, encoding='utf-8', errors='ignore').read()
        except Exception:
            continue
        if fn.endswith('.xml') and 'values' in dirpath and fn == 'strings.xml':
            for m in str_re.finditer(s):
                ui_chars |= {c for c in m.group(1) if CHAR.match(c)}
        elif fn.endswith('.xml') and 'layout' in dirpath:
            for m in text_re.finditer(s):
                ui_chars |= {c for c in m.group(1) if CHAR.match(c)}
        elif fn.endswith('.java'):
            for m in re.finditer(r'"((?:[^"\\]|\\.)*)"', s):
                lit = m.group(1).replace('\\n', '\n').replace('\\"', '"')
                ui_chars |= {c for c in lit if CHAR.match(c)}

ark = TTFont(ARK)
fus = TTFont(FUS)
ark_cmap_tbl = None
for t in ark['cmap'].tables:
    if t.platformID == 3 and t.platEncID in (1, 10):
        ark_cmap_tbl = t
        break
ark_best = ark.getBestCmap()
fus_best = fus.getBestCmap()

missing = sorted({c for c in ui_chars if ord(c) not in ark_best and ord(c) in fus_best})
print('待合并 %d 字: %s' % (len(missing), ''.join(missing)))

glyph_order = ark.getGlyphOrder()
existing = set(glyph_order)
glyf = ark['glyf']
hmtx = ark['hmtx']
fus_glyphs = fus.getGlyphSet()
added = []

for ch in missing:
    src = fus_best[ord(ch)]           # Fusion cmap -> glyph 名
    dst, i = src, 0
    while dst in existing:            # 防 Ark 内名字冲突
        i += 1
        dst = '%s.m%d' % (src, i)
    rec = DecomposingRecordingPen(fus_glyphs)   # 复合字形展开为简单轮廓
    fus_glyphs[src].draw(rec)
    pen = TTGlyphPen(None)
    rec.replay(pen)
    glyf[dst] = pen.glyph()
    # 注意：glyf.__setitem__ 会自动把 dst append 进 glyphOrder（同一 list 对象），勿再手动 append
    existing.add(dst)
    hmtx[dst] = (fus['hmtx'][src] if src in fus['hmtx'].metrics
                 else fus['hmtx'][fus.getGlyphOrder()[0]])
    ark_cmap_tbl.cmap[ord(ch)] = dst
    added.append(dst)

print('新增 %d 个字形，如: %s ...' % (len(added), added[:5]))
ark.setGlyphOrder(glyph_order)
glyf.glyphOrder = glyph_order
ark.save(ARK)
print('已写回', ARK)

# 独立重载验证
ark2 = TTFont(ARK)
best2 = ark2.getBestCmap()
left = sorted(c for c in ui_chars if ord(c) not in best2)
print('合并后 UI 缺字: %s' % (''.join(left) if left else '(无，全覆盖)'))
# 验证 cmap 指向的 glyph 均真实存在
glyf2 = ark2['glyf'].glyphs
bad = [c for c in ui_chars if ord(c) in best2 and best2[ord(c)] not in glyf2]
print('cmap 悬空引用:', ''.join(bad) if bad else '(无)')
