# 响应式原生 DP 布局与全分辨率架构设计文档

## 一、 背景与问题剖析

### 1.1 现存架构的物理缺陷
在当前工程中，原键桌面采用了 **「240×320 dp 基准 + 运行时 GPU `setScaleX/setScaleY` 矩阵拉伸」** 体系。在 240×320（QVGA）设备上表现正常，但在 320×480（HVGA）及更高分辨率（Android 13 等现代设备）上存在两大不可克服的物理缺陷：

1. **GPU 离屏贴图二次插值导致全局模糊（Blurry Rendering）**：
   - Android 硬件加速渲染管线中，`View.setScaleX/Y` 会先将 240dp 视口内容绘制到一块低分辨率的离屏纹理（Offscreen Buffer）上。
   - GPU 将该小纹理放大 1.33~1.5 倍并应用双线性插值（Bilinear Filtering），导致所有原本清晰的矢量图标、文字边缘、点线分隔线全部发虚、产生模糊与毛刺。
2. **屏幕宽高比差异导致元素被纵向拔高拉长（Aspect Ratio Distortion）**：
   - 240×320 屏幕为 **3:4（0.75）** 比例，而 320×480 屏幕为 **2:3（0.667）** 细长比例。
   - 为避免底部露白，历史架构引入了 `fixMidContentHeight` 将高度强行撑大为 `panelH / scale`，导致中间区域图标和组件间距被强行纵向扯高，产生“图标被向上拔高拉长”的失真感。

### 1.2 历史沿革与补丁死循环
- **阶段一**：从 J2ME-Loader 移植时沿用了 Java 时代 240×320 的物理画布思维，期望用 `scaleMidContent` 整体放大应对大屏。
- **阶段二**：320×480 下部分 `match_parent` 页面被乘 `scale` 后宽度溢出（427px > 320px），历史开发者添加了**补丁一**：在文档中硬性规定「所有页面根宽必须写死 240dp」。
- **阶段三**：写死 240dp 后导致细长屏上下露缝，历史开发者又添加了**补丁二**：编写 `fixMidContentHeight` 动态改高。
- **阶段四**：顶栏开发者发现了缩放模糊问题，率先将顶栏改为「原生分辨率渲染（不做 setScaleX/Y 缩放）」，但中间内容区一直保留了历史包袱。

---

## 二、 核心目标与收益

1. **矢量级原生锐利度（100% Native Crispness）**：
   - 彻底废除 `setScaleX/setScaleY` 离屏缩放，所有视图、文字、矢量图标在屏幕物理像素上点对点光栅化渲染，彻底消除毛刺与发虚。
2. **黄金正比例，拒绝拉伸变形（Zero Distortion）**：
   - 无论 3:4 还是 2:3 屏幕，图标始终维持 1:1 正方形标准比例；多余的高度由中间壁纸与可滚动组件区自然吸收。
3. **全分辨率无缝通吃（Universal Compatibility）**：
   - 完美适配 240×320 (QVGA)、320×480 (HVGA)、480×800 (WVGA)、1080p (FHD) 等任意屏幕尺寸。

---

## 三、 方案 A（响应式原生 DP 布局）架构设计

### 3.1 核心中枢改造（`NokiaBaseActivity` / `NokiaPageFragment`）

1. **废弃全局矩阵缩放**：
   - `NokiaBaseActivity.scaleMidContent()` 改造为恒等 1:1 原生呈现，不再对 View 应用 `setScaleX/Y`。
   - `NokiaBaseActivity.getScale()` 在原生布局模式下返回 `1.0f`。
   - `fixMidContentHeight()` 废除动态高度修改（视图自然填满容器）。
2. **顶底栏与中间面板全原生对接**：
   - 顶栏 `topPanel`：保持 wrap_content 原生渲染。
   - 底栏 `bottomPanel`：高度固定 `NokiaDimens.dp(24)`，宽度 `match_parent`，文字动态自适应。
   - 中间 `midPanel`：宽度与高度均为 `match_parent`，作为标准容器承载各 Fragment。

### 3.2 布局根节点解绑（XML Layouts）

将所有 Fragment 根节点的 `android:layout_width="240dp"` 全面改为：
```xml
android:layout_width="match_parent"
android:layout_height="match_parent"
```

### 3.3 各页面子系统的响应式适配

#### 1. 诺基亚主桌面（`NokiaDesktopFragment` / `fragment_nokia_desktop.xml`）
- **顶部快捷应用栏**：
  - 宽度 `match_parent`，高度固定 `NokiaDimens.dp(38)`。
  - 单元格固定宽度 `36dp`，水平居中/水平平滑滚动。
- **中间组件区（Widget Area）**：
  - 位于快捷栏与开关栏之间，占据剩余全部高度（`layout_above="@id/quickToggleDivider"`）。
  - 内置 `ScrollView` 垂直滚动，组件单行宽度 `match_parent`，自适应展现。
- **底部便捷开关栏（Quick Toggle Bar）**：
  - 宽度 `match_parent`，高度固定 `NokiaDimens.dp(34)`，紧贴底部分隔线。
  - 单元格固定宽度 `36dp`，内置 `HorizontalScrollView` 支持多开关平滑横滚。

#### 2. 12 宫格功能表与百宝箱（`NokiaMenuFragment` / `NokiaBoxFragment`）
- 3 列网格布局采用 `layout_width="match_parent"`。
- 每列使用 `weight=1` 均分屏幕宽度（240dp 屏为 80dp/列，320dp 屏为 106.6dp/列）。
- 宫格内图标（48×48dp）和文字保持水平居中，消除边缘空隙与横向溢出。

#### 3. 垂直单列设置页面（`NokiaListPageFragment` 及所有派生列表）
- 列表项宽度 `match_parent`，左右 padding 设为 `8dp`。
- 点线分隔线（`NokiaDashedLineDrawable`）横向自然撑满整屏。
- 选中的高亮焦点框（`bg_nokia_selected`）铺满整行，视觉一致。

#### 4. 通用选项弹窗（`NokiaOptionsDialog`）
- 弹窗根布局宽度设为 `wrap_content`（最大宽度 `260dp`）并居中，或 `240dp` 优雅悬浮于屏幕中央。

---

## 四、 实施改动清单

| 模块 / 文件 | 改动内容 |
| :--- | :--- |
| `NokiaBaseActivity.java` | 移除 `scaleMidContent` 中的 `setScaleX/Y` 变换，`getScale()` 返回 1.0f，废弃 `scalePanelContent` 中的二次缩放 |
| `NokiaPageFragment.java` | 移除旧版 `scaleMidContent` 与 `fixMidContentHeight` 的样板调用 |
| `fragment_nokia_desktop.xml` | 根宽度改为 `match_parent`，各分区采用标准 Relative/Linear 响应式约束 |
| `fragment_nokia_menu.xml` | 根宽度改为 `match_parent`，12 宫格 3 列均分自适应 |
| `fragment_nokia_box.xml` | 根宽度改为 `match_parent`，百宝箱网格响应式均分 |
| `fragment_nokia_*_settings.xml` | 所有设置页根宽度统一由 `240dp` 改为 `match_parent` |
| `NOKIA_DEVELOPMENT_RULES.md` | 更新开发规范：废除“必须写死 240dp”的旧规，确立响应式原生 DP 规范 |

---

## 五、 验证与走查标准

1. **Android 4.4（240×320, 120dpi）**：
   - 界面无缝铺满，文字和图标保持原有 1:1 像素级复古质感，无错位与溢出。
2. **Android 13（320×480, 136dpi）**：
   - 所有文字、点线、快捷开关、状态栏图标均为**绝对锐利的矢量点对点渲染，零发虚、零模糊**。
   - 图标比例为标准 1:1 正方形，不再有纵向被拔高拉伸的失真感。
   - D-Pad 焦点框与滚动交互完全正常。
