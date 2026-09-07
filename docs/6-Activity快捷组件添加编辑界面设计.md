# 6-Activity快捷组件添加/编辑界面设计

> 本文档定义 Activity 快捷组件的三步添加流程及编辑流程。
> 添加流程：选应用 → 选 Activity → 输入名称。
> 编辑流程与添加流程一致，从第 1 步重新开始（换绑 = 全部重选）。

---

## 一、界面层级关系

```
【添加模式】
S6 选择组件类型 → 选「Activity快捷」
  └─ 步骤1：选择应用 (KeydroidxWidgetAppPickerFragment, mode=ACTIVITY_ADD)
       │  复用 2 号文档宫格，但确认键行为不同：选中应用 → 进入步骤2
       └─ 步骤2：选择Activity (KeydroidxWidgetActivityPickerFragment)
            │  纵向列表，列出该应用的 exported Activity
            └─ 确认键选中 → 进入步骤3
                └─ 步骤3：输入名称 (KeydroidxWidgetActivityNameFragment)
                     │  表单页，预填 Activity label，用户可改
                     └─ 左软键保存 → addItem() → 回到 S1

【编辑模式】
S1 桌面组件设置 → 确认键（光标在 Activity快捷类组件上）
  └─ 从步骤1开始重新选 (同添加流程，最后 updateItem(editIndex, item))
```

### 添加 vs 编辑

| 维度 | 添加模式 | 编辑模式 |
|------|---------|---------|
| 入口 | S6 → 选「Activity快捷」 | S1 → 确认键（光标在 Activity快捷 上） |
| 流程 | 步骤1 → 步骤2 → 步骤3 | 步骤1 → 步骤2 → 步骤3（完全一致） |
| 步骤1标题 | 「选择应用」 | 「选择应用」 |
| 预选应用 | 无 | 无（从头开始选，不预选旧应用） |
| 步骤3名称预填 | 选中 Activity 的 label | 选中 Activity 的 label（不用旧名称） |
| 保存操作 | `storage.addItem(item)` → S1 | `storage.updateItem(editIndex, item)` → S1 |
| 右软键（步骤3） | 回到步骤2 | 回到步骤2 |

> 编辑模式不区分「当前绑定的应用」，直接从步骤1开始选。
> 用户可能想换到完全不同的应用的不同 Activity，所以不跳过步骤1。

---

## 二、步骤1：选择应用

### 界面

复用 2 号文档的宫格布局（`KeydroidxWidgetAppPickerFragment`），但通过 Bundle 参数区分：

```java
// 添加 Activity 快捷
Bundle args = new Bundle();
args.putString("mode", "ACTIVITY_ADD");  // 区别于应用组件的 "ADD"
fragment.setArguments(args);
```

### 与 2 号文档的差异

| 维度 | 应用组件选应用（2号文档） | Activity快捷选应用（本文档） |
|------|------------------------|--------------------------|
| 标题 | 「选择应用」 | 「选择应用」 |
| 宫格 | 一致 | 一致 |
| 搜索框 | 一致 | 一致 |
| 已添加标记 | 已添加为组件的应用灰色 | **不标记**（Activity 快捷可以和应用组件共存于不同应用，不存在"已占用"问题） |
| 确认键 | 选中应用 → `addItem` → 回 S1 | 选中应用 → **进入步骤2** |
| 右软键 | 回 S6 | 回 S6 |

> 关键区别：确认键不添加组件，而是带着选中的应用包名进入步骤2。
> 宫格中所有应用都可选，不做灰色标记。

### 数据传递

选中应用后，将包名传给步骤2：

```
步骤1 确认键
  │
  ├─ 取出选中应用的 packageName
  ├─ 创建 KeydroidxWidgetActivityPickerFragment
  │   args.putString("mode", "ADD")
  │   args.putString("packageName", pkg)
  │   args.putString("appLabel", appLabel)  // 应用名，用于步骤2标题
  ├─ 如果是编辑模式：args.putString("mode", "EDIT"), args.putInt("editIndex", N)
  └─ replace fragment → 进入步骤2
```

---

## 三、步骤2：选择 Activity

### 界面布局

```
┌──────────────────────────────────┐
│ 微信 - 选择Activity               │  ← 标题：应用名 + 「选择Activity」
├──────────────────────────────────┤
│                                  │
│  📋 扫一扫                       │  ← 光标在第一行
│  📋 付款                         │
│  📋 联系人                       │
│  📋 设置                         │
│  📋 聊天                         │
│  📋 朋友圈                       │
│                                  │
│         1 / 2  ← 页码             │
│                                  │
├──────────────────────────────────┤
│                                 返回│
└──────────────────────────────────┘
```

### 列表要素

| 区域 | 说明 |
|------|------|
| **标题** | `{应用名} - 选择Activity` |
| **列表** | 纵向列表，每行一个 Activity |
| **每行** | 左侧图标（应用图标缩放或通用 Activity 图标）+ Activity label 名称 |
| **页码** | 底部上方居中，`当前页 / 总页` |
| **底部软键** | 左（空）、中（空）、右「返回」 |

### 行内容

```
┌──────────────────────────────────┐
│  [图标] 扫一扫                    │  ← Activity label
│  [图标] 付款                      │
│  [图标] com.tencent.mm.ui.xxx    │  ← 无 label 时显示类名简称
└──────────────────────────────────┘
```

- **有 label**：显示 label 文字（白色，11sp，等宽字体）
- **无 label**：显示类名最后一段（去掉包名前缀，取 `.xxx` 后的简短名）
- **图标**：统一用应用图标缩放到 14×14dp，不单独为每个 Activity 取图标（Activity 通常没有独立图标）

### Activity 数据来源

```java
PackageManager pm = context.getPackageManager();
PackageInfo pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
List<ActivityInfo> activities = new ArrayList<>();
for (ActivityInfo ai : pkgInfo.activities) {
    // 过滤条件
    if (!ai.exported) continue;           // 只保留可外部启动的
    if (isLauncherActivity(ai, pm)) continue;  // 排除主入口（已在应用组件中覆盖）
    activities.add(ai);
}
// 按 label 字母排序
Collections.sort(activities, (a, b) -> {
    String la = a.loadLabel(pm).toString();
    String lb = b.loadLabel(pm).toString();
    return la.compareToIgnoreCase(lb);
});
```

### 过滤规则

| 过滤项 | 规则 | 理由 |
|--------|------|------|
| `exported=false` | 排除 | 无法通过 `startActivity` 从外部启动 |
| Launcher Activity | 排除 | 主入口已经在「应用」组件类型中覆盖，选 Activity 快捷就是想进二级页面 |
| 系统级 Activity | 排除 | 包名以 `com.android` 开头且非用户应用的 |

> 判断 Launcher Activity：检查该 Activity 是否匹配 `ACTION_MAIN` + `CATEGORY_LAUNCHER` Intent。
> 用 `pm.queryIntentActivities(new Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER), 0)` 获取该包的 launcher Activity 类名集合，排除之。

### 翻页

参考 1 号文档的纵向列表翻页逻辑：

| 按键 | 行为 |
|------|------|
| 上 | 同列上移一行；已到第一行 → 焦点移到搜索框（如有）/ 不动 |
| 下 | 同列下移一行；已到最后一行 → 翻下一页（如有） |
| 确认键 | 选中该 Activity → 进入步骤3 |
| 右软键 | 回到步骤1 |

- 每页行数复用 `KeydroidxBaseActivity` 的分辨率自适应逻辑
- 行高 22~24dp（与桌面组件行高一致）

### 无搜索框

Activity 列表不加搜索框。理由：
- 大部分应用的 exported Activity 数量在 5~20 个之间，列表足够短
- 如果个别应用 Activity 特别多（如 50+），列表滚动也能处理
- 加搜索框会增加界面复杂度，且 Activity label 通常较短，浏览比搜索更快

### 空列表情况

如果某应用过滤后没有任何可选 Activity（极少见，但理论可能）：

```
┌──────────────────────────────────┐
│ 微信 - 选择Activity               │
├──────────────────────────────────┤
│                                  │
│     该应用没有可用的Activity       │
│                                  │
├──────────────────────────────────┤
│                                 返回│
└──────────────────────────────────┘
```

- 显示提示文字，按右软键返回步骤1

---

## 四、步骤3：输入名称

### 界面布局

```
┌──────────────────────────────────┐
│ 输入名称                          │  ← 标题
├──────────────────────────────────┤
│                                  │
│  显示名称                         │  ← 字段标签
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓   │  ← 蓝色高亮边框（焦点态）
│ ▓ 扫一扫                      ▓▓   │     预填 Activity label
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓   │
│                                  │
│     ┌──────────┐                 │
│     │   保存   │                 │  ← 保存按钮
│     └──────────┘                 │
│                                  │
├──────────────────────────────────┤
│ 保存                            返回│
└──────────────────────────────────┘
```

### 与 3 号文档的关系

本页面是 3 号文档（网址编辑）的简化版：只有**一个**文本输入字段 + 保存按钮。
焦点导航和文本字段的交互逻辑完全复用 3 号文档的三态模式（焦点态 / 编辑态 / 非焦点态）。

### 布局要素

| 区域 | 设计 |
|------|------|
| **标题** | 「输入名称」 |
| **字段标签** | 灰色小字（`#AAAAAA`），如「显示名称」 |
| **字段值** | 预填 Activity 的 label（`ActivityInfo.loadLabel(pm)`），白色文字 |
| **字段焦点** | 蓝色高亮边框（复用 `bg_nokia_selected_dark`） |
| **激活态** | 确认键后 EditText 获得焦点 → 系统光标闪烁 + 软键盘弹出 |
| **保存按钮** | 居中，诺基亚风格按钮（深色底 + 蓝色文字/边框），可被方向键选中 |
| **底部软键** | 左「保存」、中（空）、右「返回」 |

### 焦点导航

2 个可聚焦目标，按上下键依次导航：

```
  名称字段     ← focusIndex=0（进入时默认焦点）
  [保存] 按钮  ← focusIndex=1
```

| 方向 | 行为 |
|------|------|
| 上 | 移到上一个焦点目标；已在第一个时不动 |
| 下 | 移到下一个焦点目标；已在最后一个时不动 |
| 左/右 | 无效果（线性纵向列表） |

### 文本字段交互

完全复用 3 号文档的三态模式：

| 状态 | 行为 |
|------|------|
| **焦点态**（蓝色高亮，EditText 未激活） | 确认键 → 激活 EditText → 编辑态；上/下 → 切换焦点目标 |
| **编辑态**（EditText 激活，软键盘弹出） | 软键盘输入文本；回车/完成 → 关闭软键盘回焦点态；物理返回键 → 关闭软键盘回焦点态 |
| **非焦点态** | 浅灰色边框（`#333333`） |

### 字段约束

| 字段 | 最大长度 | 允许为空 | 占位符 |
|------|---------|---------|--------|
| 显示名称 | 20 字符 | **否**（保存时校验） | 「输入显示名称」 |

### 保存按钮

与 3 号文档一致，保存按钮和左软键是两条等价的保存路径。

| 状态 | 行为 |
|------|------|
| 焦点态视觉 | 蓝色高亮文字 + 蓝色边框 |
| 确认键 | 校验 → 保存 → 回到 S1 |
| 左软键「保存」 | 与保存按钮确认键等同（即使光标在名称字段上也直接保存） |

### 预填值

- 进入步骤3时，从步骤2传来的 Activity label 预填名称字段
- 如果 Activity 无 label（`loadLabel` 返回 null 或空），预填类名简称
- 光标定位在名称字段上（focusIndex=0），EditText 未激活（焦点态，非编辑态）
- 用户可以直接按左软键保存（接受预填值），或确认键修改

### 保存校验

| 校验项 | 规则 | 失败提示 |
|--------|------|---------|
| 名称为空 | 去除首尾空格后长度 = 0 | 「请输入显示名称」 |

> 校验通过后：去除首尾空格，构造 `KeydroidxWidgetItem` 并写入存储。

### 保存数据

```
保存
  │
  ├─ 构造 KeydroidxWidgetItem:
  │     type = TYPE_ACTIVITY (4)
  │     label = 用户输入的名称
  │     payload = packageName + "/" + className  (序列化为 JSON 或字符串)
  │     iconPath = packageName (用于桌面加载应用图标)
  ├─ 添加模式 → storage.addItem(item)
  ├─ 编辑模式 → storage.updateItem(editIndex, item)
  └─ exitCurrent → 回到 S1
```

### 右软键

| 状态 | 行为 |
|------|------|
| 焦点态/编辑态 | 回到步骤2（不保存） |
| 步骤2 右软键 | 回到步骤1 |
| 步骤1 右软键 | 回到 S6（添加模式） / 回到 S1（编辑模式） |

> 三步流程可以逐步返回：步骤3 → 步骤2 → 步骤1 → 上级。

---

## 五、数据流

```
步骤1：选应用
  │
  ├─ 加载应用列表（安卓 + J2ME）
  ├─ 宫格展示，不做已添加标记
  └─ 确认键 → 取 packageName + appLabel → 进入步骤2

步骤2：选Activity
  │
  ├─ 接收 packageName
  ├─ PackageManager.getPackageInfo(pkg, GET_ACTIVITIES)
  ├─ 过滤：exported=true，排除 launcher，排除系统级
  ├─ 按 label 排序
  ├─ 纵向列表展示
  └─ 确认键 → 取 Activity label + className → 进入步骤3

步骤3：输入名称
  │
  ├─ 接收 packageName, className, activityLabel
  ├─ 预填 activityLabel 到名称字段
  ├─ 焦点定位在名称字段（焦点态）
  └─ 左软键/保存按钮 → 校验 → 构造 KeydroidxWidgetItem → 保存 → 回 S1
```

---

## 六、桌面显示

Activity 快捷组件在桌面 `notificationArea` 中的显示：

| 元素 | 规格 |
|------|------|
| 图标 | 应用图标（通过 `PackageManager.getApplicationIcon(packageName)` 加载），缩放到 14×14dp |
| 文字 | 用户输入的显示名称，11sp，白色 |
| 选中高亮 | 半透明蓝 `#662196F3`，圆角 4dp |
| 行布局 | `[图标 14dp] [5dp] [文字]`，行高 22~24dp |

### 点击行为

桌面选中 Activity 快捷组件 + 确认键 → 启动该 Activity：

```java
Intent intent = new Intent();
intent.setClassName(packageName, className);
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
startActivity(intent);
```

> 如果 Activity 因权限变更、应用卸载等原因无法启动，catch `ActivityNotFoundException` 并 Toast 提示「应用无法启动」。

---

## 七、S1 设置列表中的显示

| 元素 | 说明 |
|------|------|
| 左侧图标 | 应用图标缩放 14×14dp |
| 中间文字 | 用户输入的显示名称 |
| 右侧标签 | `[Activity]`，7sp，`#4DFFFFFF` |

```
┌─────────────────────────────────────────┐
│  [📱] 微信扫一扫                 [Activity] │
│  [📱] 支付宝付款码               [Activity] │
│  [🎵] 音乐播放器                   [应用]  │
└─────────────────────────────────────────┘
```

---

## 八、边界情况

| 场景 | 处理 |
|------|------|
| **应用没有任何 exported Activity**（过滤后为空） | 步骤2 显示「该应用没有可用的Activity」，右软键回步骤1 |
| **应用已卸载**（步骤2 加载时 PackageNameNotFound） | Toast「应用已卸载」+ 回到步骤1 |
| **Activity label 为空** | 预填类名简称（取 `className` 最后一个 `.` 后的部分） |
| **Activity 启动失败**（桌面点击时） | Toast「应用无法启动」 |
| **步骤1 选了 J2ME 应用** | J2ME 应用没有多 Activity 机制，步骤2 显示「该应用没有可用的Activity」并返回步骤1 |
| **搜索无结果**（步骤1） | 复用 2 号文档的「未找到匹配应用」 |

> J2ME 应用本质上是 Midlet，只有单一入口，不存在二级 Activity。
> 如果用户在步骤1 选了 J2ME 应用，步骤2 会空列表，用户自然返回。
> 可选优化：步骤1 宫格中过滤掉 J2ME 应用，只显示安卓应用。但这样会限制用户选择，暂不做过滤。

---

## 九、视觉规范

完全复用 3 号文档和 4 号文档的视觉规范，此处仅列差异：

| 元素 | 规格 | 备注 |
|------|------|------|
| 步骤2 行图标 | 14×14dp，应用图标缩放 | 不用单独的 Activity 图标 |
| 步骤2 行文字 | 11sp，白色 `#FFFFFF`，monospace | 与桌面组件行一致 |
| 步骤2 行高度 | 22~24dp | 与桌面组件行一致 |
| 步骤2 选中高亮 | `#662196F3`，圆角 4dp | 复用 `bg_nokia_selected` |
| 步骤3 表单 | 与 3 号文档完全一致 | 单字段 + 保存按钮 |

---

## 十、按键行为速查

### 步骤1（宫格选应用）

复用 2 号文档，差异：

| 按键 | 行为 |
|------|------|
| 确认键 | 选中应用 → 进入步骤2（不添加组件） |
| 右软键 | 回到 S6（添加模式）/ S1（编辑模式） |

> ⚠️ **按键配对规范（DOWN/UP 成对消费）**：步骤1 确认键进入步骤2、步骤2 确认键进入步骤3 时，若确认键 DOWN 已在 `KeydroidxDesktopActivity.dispatchKeyEvent` 本层被消费（`return true`），须同步消费对应的 UP（含 REPEAT），禁止只消费 DOWN。否则 DOWN 被吞、UP 穿透到新步骤可点击 View 时，UP 会合成 `performClick()` 触发第二次动作（例：进入步骤2后又自动选中第一项；进入步骤3后又自动激活名称字段）。实现用 `lastHandledDownKeyCode` 记录被本层消费的 keyCode，非 DOWN 分支对相同 keyCode `return true` 吞掉，未消费键 DOWN 路径复位为 `KEYCODE_UNKNOWN`。步骤3 EditText 真正激活后的按键走系统软键盘，不受此约束。

### 步骤2（纵向列表选 Activity）

| 按键 | 行为 |
|------|------|
| 上 | 移到上一个 Activity；已到第一个不动 |
| 下 | 移到下一个 Activity；已到最后一个翻页（如有） |
| 确认键 | 选中 Activity → 进入步骤3 |
| 右软键 | 回到步骤1 |

### 步骤3（名称输入）

| 按键 | 焦点态 | 编辑态 |
|------|--------|--------|
| 上/下 | 切换焦点目标（名称字段 ↔ 保存按钮） | 无效果（锁定在 EditText） |
| 确认键 | 字段上 → 激活 EditText；按钮上 → 保存 | — |
| 回车/完成 | — | 关闭软键盘 → 焦点态 |
| 物理返回键 | — | 关闭软键盘 → 焦点态 |
| 左软键 | 保存 | 保存 |
| 右软键 | 回到步骤2 | 关闭软键盘 → 焦点态 |

---

## 十一、涉及文件

| 文件 | 类型 | 说明 |
|------|------|------|
| `KeydroidxWidgetAppPickerFragment.java` | 修改 | 增加 `ACTIVITY_ADD` / `ACTIVITY_EDIT` 模式，确认键不添加而是跳步骤2 |
| `KeydroidxWidgetActivityPickerFragment.java` | 新建 | 步骤2：Activity 纵向列表选择 |
| `KeydroidxWidgetActivityNameFragment.java` | 新建 | 步骤3：名称输入表单（单字段 + 保存） |
| `fragment_nokia_widget_activity_picker.xml` | 新建 | 步骤2 布局（标题 + 列表容器 + 页码） |
| `fragment_nokia_widget_activity_name.xml` | 新建 | 步骤3 布局（标题 + 字段 + 保存按钮） |
| `KeydroidxWidgetStorage.java` | 已有 | 需提供 `addItem()`、`updateItem(index, item)` 方法 |

---

## 十二、数据模型

Activity 快捷组件存储为 `KeydroidxWidgetItem`：

```json
{
  "type": 4,
  "label": "微信扫一扫",
  "payload": "com.tencent.mm/com.tencent.mm.plugin.scanner.ui.BaseScanUI",
  "iconPath": "com.tencent.mm"
}
```

| 字段 | 说明 |
|------|------|
| `type` | `TYPE_ACTIVITY = 4` |
| `label` | 用户在步骤3输入的显示名称 |
| `payload` | `packageName/className`，用 `/` 分隔，桌面启动时解析 |
| `iconPath` | `packageName`，桌面加载应用图标用 |

> 桌面加载时：
> ```java
> String[] parts = item.getPayload().split("/", 2);
> String pkg = parts[0];
> String cls = parts[1];
> Intent intent = new Intent();
> intent.setClassName(pkg, cls);
> intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
> startActivity(intent);
> ```

---

## 十三、Android 版本兼容性

Activity 快捷组件使用的全部是 API 1+ 的基础 API，**全版本兼容，无需版本分支**。

| 功能 | API | 兼容性 |
|------|-----|--------|
| `PackageManager.getPackageInfo(pkg, GET_ACTIVITIES)` | 1 | ✅ |
| `ActivityInfo.exported` | 1 | ✅ |
| `ActivityInfo.loadLabel(pm)` | 1 | ✅ |
| `pm.queryIntentActivities()` | 1 | ✅ |
| `pm.getApplicationIcon(packageName)` | 1 | ✅ |
| `intent.setClassName(pkg, cls)` | 1 | ✅ |
| `startActivity(intent)` | 1 | ✅ |

> Android 4.4（API 19）完全兼容，无降级处理。

### 矢量图加载注意

Activity 快捷组件桌面显示用的图标是 `PackageManager.getApplicationIcon()` 返回的应用图标（BitmapDrawable / AdaptiveIconDrawable），**不是 vector drawable**，无兼容性问题。

但如果步骤2列表中使用了通用 Activity 图标（如 `ic_nokia_activity_default.xml`）作为占位图，则该图标如果是 vector drawable，需遵守：

| 场景 | 正确写法 |
|------|----------|
| 布局 XML | `app:srcCompat="@drawable/ic_nokia_xxx"` |
| 代码加载 | `ContextCompat.getDrawable()` / `AppCompatResources.getDrawable()` |

> 详见 `安卓 4.4（API 19）矢量图崩溃修复计划.md`。

---

> **编号 6。**
