# 字体工具脚本

针对内置像素字体 `app/src/main/assets/fonts/ArkPixel-12px.ttf`（方舟像素字体 zh_cn 子集版）的两个维护脚本。

## 背景

ArkPixel-12px 是子集字体，部分汉字没有字形，UI 上会渲染成**方块**（Android 4.4 对自定义 TTF 没有缺字回退机制）。2026-09 已一次性补齐 36 个缺字（即/执/骤/窗/然/热/滚/毁/聚/旋/遥/避/辨/概/警等），字形来源为同为 12px 像素风格的 FusionPixel-12px。

**新增文案后如果出现方块字，按下面顺序跑这两个脚本即可补齐。**

## 1. scan_missing_glyphs.py — 扫描缺字

扫描项目所有 UI 文案（`values*/strings.xml`、layout 的 `android:text`、Java 里的字符串字面量），列出 ArkPixel 中没有字形的字符及其出现位置。

```powershell
python -X utf8 scripts\scan_missing_glyphs.py
```

无输出 = 全部覆盖；有输出则逐字列出缺字位置。**新加文案后先跑这个确认是否缺字。**

## 2. merge_glyphs.py — 补齐字形

把扫描出的 UI 缺字字形从 `FusionPixel-12px.ttf` 提取并合并进 `ArkPixel-12px.ttf`：

- 复合字形自动展开为简单轮廓
- 同步 `hmtx` / 全部 3 张 cmap 子表（format 4 / 12）
- 写回前校验，写回后独立重载验证 0 缺字、0 悬空引用

```powershell
python -X utf8 scripts\merge_glyphs.py
```

**跑完后必须重新构建 APK 并装机确认显示正常**（构建期 AAPT 不校验字形）。

## 依赖

```
pip install fonttools
```

## 注意

- 两个脚本按自身位置自动定位 `app/src/main`，放在仓库 `scripts/` 下即可，无需改路径
- 补字来源限定 FusionPixel；若某字 FusionPixel 也缺，脚本会跳过并在末尾提示剩余缺字
- ArkPixel 字体文件较大（约 5MB），合并后 diff 是二进制，无法逐行审阅，务必以真机显示为准
