package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import androidx.annotation.DrawableRes;
import io.github.cctyl.nokia.common.log.NokiaLog;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import ru.playsoftware.j2meloader.R;

/**
 * 诺基亚 S60 风格图标映射。
 *
 * <p><b>确定性匹配（纯函数）</b>：结果只由 (包名, 应用名 label) 决定，与历史缓存 / 已装应用集合无关。
 * 同一应用（名称与包名不变）每次匹配结果 100% 一致，绝无随机性。</p>
 *
 * <p>匹配优先级（从高到低）：</p>
 * <ol>
 *   <li><b>精确包名表</b> — 知名应用直接锁定图标，与 ROM / 缓存 / 安装集合无关。</li>
 *   <li><b>应用名 label 关键词表</b> — 常见应用名（如"浏览器"→ 浏览器）写死在代码中；
 *       label 由应用自身在 Manifest 声明，普通用户无法修改，故结果恒定。</li>
 *   <li><b>意图探测</b> — 仅覆盖前两层未命中的长尾应用；对同一 ROM 结果恒定。</li>
 * </ol>
 *
 * <p>历史缺陷修复说明：</p>
 * <ul>
 *   <li>已删除「包名关键词模糊匹配」（FUZZY_FALLBACK）：关键词过宽 / 非互斥是 g+ 图标（s60_app）错配的根源。</li>
 *   <li>已删除 init() 第 3 层「无条件保留旧缓存」：历史错误匹配不再固化写盘，新增应用不影响已有应用结果。</li>
 *   <li>精确包名优先级提到最高：即使意图探测（如 CATEGORY_APP_MARKET）把某包错分到 s60_app，精确表命中后永远返回正确图标。</li>
 * </ul>
 */
public class NokiaS60IconMap {

	/** IntentFilter 探测项：图标资源 + 探测 Intent */
	private static class Probe {
		final int iconResId;
		final Intent intent;
		Probe(int iconResId, Intent intent) { this.iconResId = iconResId; this.intent = intent; }
	}

	/** 按优先级排列的探测列表：越靠前优先级越高（先命中先得），顺序固定 → 对同一 ROM 结果恒定 */
	private static final Probe[] PROBES = {
			// ── 高优先级：功能明确的定向意图 ──
			new Probe(R.drawable.s60_camera,       intent(MediaStore.ACTION_IMAGE_CAPTURE)),
			new Probe(R.drawable.s60_calendar,     appCategory(Intent.CATEGORY_APP_CALENDAR)),
			new Probe(R.drawable.s60_call_log,     intent(Intent.ACTION_DIAL)),
			new Probe(R.drawable.s60_contacts,     appCategory(Intent.CATEGORY_APP_CONTACTS)),
			new Probe(R.drawable.s60_mms,          appCategory(Intent.CATEGORY_APP_MESSAGING)),
			new Probe(R.drawable.s60_calculator,   appCategory(Intent.CATEGORY_APP_CALCULATOR)),
			new Probe(R.drawable.s60_clock,        intent(AlarmClock.ACTION_SET_ALARM)),
			new Probe(R.drawable.s60_email,        appCategory(Intent.CATEGORY_APP_EMAIL)),
			new Probe(R.drawable.s60_weather,      appCategory(Intent.CATEGORY_APP_WEATHER)),
			new Probe(R.drawable.s60_music,        appCategory(Intent.CATEGORY_APP_MUSIC)),
			new Probe(R.drawable.s60_navigator,    appCategory(Intent.CATEGORY_APP_MAPS)),
			new Probe(R.drawable.s60_gallery,      appCategory(Intent.CATEGORY_APP_GALLERY)),
			new Probe(R.drawable.s60_notepad,      intent("android.intent.action.CREATE_NOTE")),
			new Probe(R.drawable.s60_app,          appCategory(Intent.CATEGORY_APP_MARKET)),
			new Probe(R.drawable.s60_settings,     intent(Settings.ACTION_SETTINGS)),
			new Probe(R.drawable.s60_fm_radio,     intent("android.intent.action.FM_RADIO")),

			// ── 中优先级：较通用但仍具辨别力 ──
			new Probe(R.drawable.s60_files,        appCategory(Intent.CATEGORY_APP_FILES)),
			new Probe(R.drawable.s60_video_player, new Intent(Intent.ACTION_VIEW).setType("video/*").addCategory(Intent.CATEGORY_DEFAULT)),

			// ── 低优先级：泛用意图，靠后避免误匹配 ──
			new Probe(R.drawable.s60_browser,      appCategory(Intent.CATEGORY_APP_BROWSER)),
			new Probe(R.drawable.s60_sound_recorder, intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)),
			new Probe(R.drawable.s60_search,       intent(Intent.ACTION_SEARCH)),
	};

	// ── 精确包名表（优先级 1，最高）：包名 → 图标资源 ──
	// 与 ROM / 缓存 / 已装应用集合无关，命中即锁定。
	private static final Object[][] EXACT_FALLBACK = {
			// 系统功能类
			{R.drawable.s60_calendar,  "com.android.calendar", "com.google.android.calendar", "com.miui.calendar"},
			{R.drawable.s60_contacts,  "com.android.contacts", "com.google.android.contacts", "com.samsung.android.app.contacts"},
			{R.drawable.s60_call_log,  "com.android.dialer", "com.google.android.dialer"},
			{R.drawable.s60_mms,       "com.android.mms", "com.google.android.apps.messaging", "com.samsung.android.messaging"},
			{R.drawable.s60_camera,    "com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.huawei.camera"},
			{R.drawable.s60_settings,  "com.android.settings"},
			{R.drawable.s60_calculator,"com.android.calculator2", "com.miui.calculator", "com.huawei.calculator"},
			{R.drawable.s60_clock,     "com.android.deskclock", "com.google.android.deskclock", "com.huawei.deskclock"},
			{R.drawable.s60_sound_recorder, "com.android.soundrecorder", "com.miui.soundrecorder"},
			{R.drawable.s60_fm_radio,  "com.android.fmradio", "com.miui.fmradio", "com.miui.fm", "com.miui.fmservice"},
			{R.drawable.s60_downloads, "com.android.providers.downloads.ui"},
			{R.drawable.s60_search,    "com.xiaomi.scanner"},

			// 浏览器
			{R.drawable.s60_browser,   "com.android.browser", "com.android.chrome", "com.mi.globalbrowser",
					"com.UCMobile", "com.tencent.mtt", "org.mozilla.firefox", "alook.browser"},

			// 图库 / 相册
			{R.drawable.s60_gallery,   "com.android.gallery3d", "com.miui.gallery", "com.google.android.apps.photos"},

			// 文件管理
			{R.drawable.s60_files,     "com.android.fileexplorer", "com.mi.android.globalFileexplorer",
					"com.android.documentsui", "com.huawei.hidisk", "com.estrongs.android.pop", "com.rarlab.rar"},

			// 应用商店 / 电商 / 支付
			{R.drawable.s60_app,       "com.android.vending", "com.xiaomi.market", "com.huawei.appmarket",
					"com.tencent.android.qqdownloader", "com.coolapk.market",
					"com.taobao.taobao", "com.jingdong.app.mall", "com.xunmeng.pinduoduo",
					"com.eg.android.AlipayGphone", "com.xiachufang", "com.xiaomi.smarthome",
					"com.deepseek.chat", "com.chinamworld.main", "com.ct.client", "com.MobileTicket"},

			// 邮件
			{R.drawable.s60_email,     "com.android.email", "com.google.android.gm",
					"com.microsoft.office.outlook", "com.tencent.androidqqmail"},

			// 音乐
			{R.drawable.s60_music,     "com.android.music", "com.miui.player", "com.tencent.qqmusic",
					"com.netease.cloudmusic", "com.kugou.android", "com.spotify.music",
					"com.xiaomi.miplay_client"},

			// 天气
			{R.drawable.s60_weather,   "com.miui.weather2", "com.huawei.weather"},

			// 导航 / 地图 / 出行
			{R.drawable.s60_navigator, "com.google.android.apps.maps", "com.baidu.BaiduMap",
					"com.autonavi.minimap", "com.tencent.map", "com.miui.smarttravel"},

			// 笔记 / 办公
			{R.drawable.s60_notepad,   "com.miui.notes", "com.huawei.notepad",
					"com.google.android.apps.docs", "com.evernote",
					"cn.wps.moffice_eng.xiaomi.lite", "cn.wps.moffice_eng",
					"com.fenbi.android.servant"},

			// 视频播放
			{R.drawable.s60_video_player, "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro",
					"com.ss.android.ugc.aweme.mobile", "com.ss.android.ugc.livelite",
					"com.bilibili.app.in", "air.tv.douyu.android"},

			// 社交通讯
			{R.drawable.s60_mms,       "com.tencent.mm", "com.tencent.tim", "io.github.cctyl.wechat"},
			{R.drawable.s60_whatsapp,  "com.whatsapp", "com.whatsapp.w4b"},
			{R.drawable.s60_youtube,   "com.google.android.youtube"},
			{R.drawable.s60_skype,     "com.skype.raider", "com.skype.android"},

			// 阅读
			{R.drawable.s60_books,     "com.google.android.apps.books", "com.amazon.kindle",
					"io.legado.app.release", "com.iflytek.readassistant"},

			// 扫描 / 相机工具
			{R.drawable.s60_camera,    "com.intsig.camscanner"},

			// 远程控制
			{R.drawable.s60_remote_control, "com.duokan.phone.remotecontroller", "com.microsoft.rdc.androidx"},

			// 指南针 → 导航图标兜底
			{R.drawable.s60_navigator, "com.miui.compass"},
	};

	/** 精确包名 → 图标的 O(1) 查找表（由 EXACT_FALLBACK 在类加载时构建） */
	private static final Map<String, Integer> EXACT_MAP = new HashMap<>();
	static {
		for (Object[] row : EXACT_FALLBACK) {
			int resId = (int) row[0];
			for (int i = 1; i < row.length; i++) {
				EXACT_MAP.put((String) row[i], resId);
			}
		}
	}

	// ── 应用名 label 关键词表（优先级 2）：label 包含任一关键词即命中 ──
	// label 由应用自身在 Manifest 声明，普通用户不可修改，故对固定应用结果恒定。
	// 注意：同一 label 只可能命中一行（顺序固定、先命中先得），关键词需保持互斥；英文统一小写。
	private static final Object[][] LABEL_FALLBACK = {
			// 通讯（"信息"/"消息"过宽易误伤设备信息类应用，短信类有精确包名表+意图层兜底，故不收）
			{R.drawable.s60_mms, "微信", "短信", "飞信", "米聊"},
			{R.drawable.s60_contacts, "联系人", "通讯录", "电话簿", "通信录"},
			{R.drawable.s60_call_log, "电话", "拨号", "通话记录"},

			// 浏览器
			{R.drawable.s60_browser, "浏览器", "uc", "chrome", "firefox", "火狐", "夸克", "edge", "safari", "alook"},

			// 音乐
			{R.drawable.s60_music, "音乐", "网易云", "qq音乐", "酷狗", "酷我", "虾米", "spotify", "咪咕",
					"汽水音乐", "全民k歌", "k歌", "唱吧"},

			// 视频
			{R.drawable.s60_video_player, "视频", "爱奇艺", "优酷", "哔哩哔哩", "bilibili", "抖音", "快手",
					"斗鱼", "虎牙", "芒果tv", "西瓜视频", "腾讯视频", "搜狐视频", "乐视", "播放器", "mx player"},

			// 相册
			{R.drawable.s60_gallery, "相册", "图库", "照片", "相片", "gallery", "photos"},

			// 相机
			{R.drawable.s60_camera, "相机", "拍照", "摄像机", "camera", "美颜", "扫一扫", "扫码"},

			// 文件
			{R.drawable.s60_files, "文件", "文档", "es文件", "rar", "压缩", "解压", "explorer"},

			// 邮件
			{R.drawable.s60_email, "邮件", "邮箱", "gmail", "outlook", "电子邮件"},

			// 天气
			{R.drawable.s60_weather, "天气", "weather", "墨迹", "彩云"},

			// 时钟
			{R.drawable.s60_clock, "时钟", "闹钟", "秒表", "计时", "clock", "alarm"},

			// 日历
			{R.drawable.s60_calendar, "日历", "日程", "万年历", "calendar", "农历"},

			// 计算器
			{R.drawable.s60_calculator, "计算器", "calculator"},

			// 设置
			{R.drawable.s60_settings, "设置", "设定", "settings"},

			// 笔记
			{R.drawable.s60_notepad, "笔记", "便签", "记事本", "备忘录", "notepad", "notes", "云笔记", "印象"},

			// 词典
			{R.drawable.s60_dictionary, "词典", "字典", "翻译", "translate", "有道", "金山词霸"},

			// 阅读
			{R.drawable.s60_books, "阅读", "小说", "读书", "kindle", "reader", "起点", "番茄", "书城", "书架"},

			// 应用商店
			{R.drawable.s60_app, "应用商店", "应用市场", "应用中心", "软件商店", "应用宝", "豌豆荚",
					"商店", "市场", "store", "market", "appstore"},

			// 电商 / 支付
			{R.drawable.s60_app, "淘宝", "天猫", "京东", "拼多多", "闲鱼", "支付宝", "唯品会",
					"苏宁", "国美", "云闪付", "一淘", "聚划算"},

			// 地图 / 导航（英文 "map" 过宽易误伤 Key Mapper 等，去掉；品牌词 baidumap/autonavi 等靠精确表兜底）
			{R.drawable.s60_navigator, "地图", "导航", "高德", "百度地图", "腾讯地图", "谷歌地图",
					"navigator", "gps", "位置", "指南针", "出行", "北斗", "baidumap", "autonavi"},

			// 搜索 / 扫描（"搜狗"过宽易误伤搜狗输入法，去掉）
			{R.drawable.s60_search, "搜索", "扫描", "助手", "问问", "scan", "search"},

			// 收音机
			{R.drawable.s60_fm_radio, "收音机", "广播", "radio", "fm"},

			// 录音
			{R.drawable.s60_sound_recorder, "录音", "录音机", "录音笔", "recorder", "语音备忘录"},

			// 下载
			{R.drawable.s60_downloads, "下载", "download"},

			// 主题 / 壁纸
			{R.drawable.s60_themes, "主题", "壁纸", "美化", "launcher", "桌面", "图标", "字体"},

			// 同步 / 云盘
			{R.drawable.s60_sync, "备份", "云盘", "同步", "网盘", "百度网盘", "夸克网盘", "阿里云盘", "微云", "坚果云"},

			// 国际通讯
			{R.drawable.s60_whatsapp, "whatsapp"},
			{R.drawable.s60_youtube, "youtube"},
			{R.drawable.s60_skype, "skype"},

			// 遥控
			{R.drawable.s60_remote_control, "遥控", "远程", "米家", "智慧生活", "小爱"},

			// 存储卡
			{R.drawable.s60_sdcard, "sd卡", "内存卡", "存储"},
	};

	// ── 意图探测缓存（优先级 3，最低）：仅由 init() 意图探测构建，只存意图命中的结果 ──
	private static volatile Map<String, Integer> intentCache = new HashMap<>();
	/** 上次扫描时的启动器包名集合，用于检测应用安装/卸载变化 */
	private static Set<String> lastKnownPackages = null;

	// ── 磁盘持久化（冷启动免全量重扫，仅加速意图层；精确表 / label 表永远优先，不受缓存影响）──
	private static final String PREFS_NAME = "nokia_s60_icon_cache";
	private static final String KEY_CACHE = "icon_cache";
	private static final String KEY_LAST_PKGS = "last_packages";
	private static Context appContext;
	/** 后台扫描进行中标记：防止多个调用方（桌面/功能表）重复启动扫描线程 */
	private static volatile boolean scanStarted = false;

	// ── 工厂方法 ──
	private static Intent intent(String action) {
		Intent i = new Intent(action);
		// 部分 ROM 的意图匹配需要添加 CATEGORY_DEFAULT
		i.addCategory(Intent.CATEGORY_DEFAULT);
		return i;
	}
	private static Intent appCategory(String category) {
		Intent i = new Intent(Intent.ACTION_MAIN);
		i.addCategory(category);
		return i;
	}

	/**
	 * 从磁盘读取上次持久化的意图缓存与包名集合（毫秒级，纯内存/SharedPreferences 操作）。
	 * 冷启动时应先调用本方法，使 {@link #getIcon} 无需扫描即可返回上次意图层结果。
	 * 注意：精确包名表 / label 表不依赖本缓存，优先级更高，结果恒不受缓存影响。
	 */
	public static void loadFromDisk(Context context) {
		appContext = context.getApplicationContext();
		long start = System.currentTimeMillis();
		try {
			SharedPreferences sp = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
			String raw = sp.getString(KEY_CACHE, null);
			if (raw != null && !raw.isEmpty()) {
				Map<String, Integer> loaded = new HashMap<>();
				String[] lines = raw.split("\n");
				for (String line : lines) {
					int eq = line.indexOf('=');
					if (eq <= 0 || eq >= line.length() - 1) continue;
					try {
						loaded.put(line.substring(0, eq),
								Integer.valueOf(line.substring(eq + 1)));
					} catch (NumberFormatException ignore) {
					}
				}
				if (!loaded.isEmpty()) {
					intentCache = loaded;
				}
			}
			Set<String> pkgs = sp.getStringSet(KEY_LAST_PKGS, null);
			if (pkgs != null && !pkgs.isEmpty()) {
				lastKnownPackages = new HashSet<>(pkgs);
			}
		} catch (Exception e) {
			NokiaLog.w("S60IconMap", "loadFromDisk 失败: " + e.getMessage());
		}
		long elapsed = System.currentTimeMillis() - start;
		NokiaLog.i("S60IconMap", "loadFromDisk 完成：intentCache=" + intentCache.size() + " 项, lastPkgs="
				+ (lastKnownPackages != null ? lastKnownPackages.size() : 0) + ", 耗时 " + elapsed + "ms");
	}

	/**
	 * 后台线程异步执行意图扫描，完成后在主线程回调。
	 * 包集合未变化时直接完成（不重扫）；变化时重新扫描意图并写回磁盘。
	 * 应配合 {@link #loadFromDisk} 使用：冷启动先读盘秒出首帧，后台再异步刷新。
	 *
	 * @param onComplete 主线程回调，可为 null
	 */
	public static void initAsync(Context context, final Runnable onComplete) {
		if (appContext == null) {
			loadFromDisk(context);
		}
		final Handler mainHandler = new Handler(Looper.getMainLooper());
		synchronized (NokiaS60IconMap.class) {
			if (scanStarted) {
				// 扫描已在后台进行，不重复启动线程；回调直接派发（用当前内存缓存即可）
				NokiaLog.d("S60IconMap", "initAsync: 扫描进行中，跳过重复启动");
				if (onComplete != null) {
					mainHandler.post(onComplete);
				}
				return;
			}
			scanStarted = true;
		}
		new Thread(new Runnable() {
			@Override
			public void run() {
				long start = System.currentTimeMillis();
				try {
					init(appContext.getPackageManager());
				} catch (Exception e) {
					NokiaLog.w("S60IconMap", "initAsync 扫描异常: " + e.getMessage());
				} finally {
					scanStarted = false;
				}
				long elapsed = System.currentTimeMillis() - start;
				NokiaLog.i("S60IconMap", "initAsync 后台扫描结束，耗时 " + elapsed + "ms");
				if (onComplete != null) {
					mainHandler.post(onComplete);
				}
			}
		}, "s60-icon-scan").start();
	}

	/**
	 * 将当前意图缓存与包名集合写入 SharedPreferences（含 commit 写盘，应在后台线程调用）。
	 */
	private static void persistToDisk() {
		if (appContext == null) {
			return;
		}
		try {
			SharedPreferences.Editor ed = appContext
					.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
			StringBuilder sb = new StringBuilder(intentCache.size() * 32);
			for (Map.Entry<String, Integer> e : intentCache.entrySet()) {
				if (sb.length() > 0) sb.append('\n');
				sb.append(e.getKey()).append('=').append(e.getValue());
			}
			ed.putString(KEY_CACHE, sb.toString());
			if (lastKnownPackages != null) {
				ed.putStringSet(KEY_LAST_PKGS, new HashSet<>(lastKnownPackages));
			}
			ed.commit();
			NokiaLog.i("S60IconMap", "persistToDisk: 已写入 " + intentCache.size() + " 项意图缓存");
		} catch (Exception e) {
			NokiaLog.w("S60IconMap", "persistToDisk 失败: " + e.getMessage());
		}
	}

	/**
	 * 同步扫描 / 刷新意图缓存。仅在应用列表发生变化时才重新扫描意图。
	 * 注意：本方法包含 PackageManager 批量查询，必须在后台线程调用（见 {@link #initAsync}）。
	 * 扫描在局部 Map 上构建，完成后一次性原子替换，避免主线程读到半成品缓存。
	 *
	 * <p>与旧实现不同：不再把历史缓存无脑保留（第 3 层已删除），缓存只由本次意图探测构成；
	 * 同一包名在同一 ROM 下意图探测结果恒定，故新增应用不会改变已有应用的结果。</p>
	 */
	public static void init(PackageManager pm) {
		// 获取当前所有启动器应用的包名集合
		Intent launcher = new Intent(Intent.ACTION_MAIN);
		launcher.addCategory(Intent.CATEGORY_LAUNCHER);
		List<ResolveInfo> allApps = pm.queryIntentActivities(launcher, 0);
		Set<String> currentPkgs = new HashSet<>();
		for (ResolveInfo ri : allApps) {
			if (ri.activityInfo != null) currentPkgs.add(ri.activityInfo.packageName);
		}

		// 包名集合未变则直接复用缓存（性能优化；结果确定性不受影响）
		if (lastKnownPackages != null && lastKnownPackages.equals(currentPkgs)) {
			NokiaLog.d("S60IconMap", "init: 应用列表未变化，复用意图缓存 (" + intentCache.size() + " 项)");
			return;
		}

		long start = System.currentTimeMillis();
		Map<String, Integer> newCache = new HashMap<>();

		// 意图探测（优先级从高到低，先命中先得；对同一 ROM 结果恒定）
		for (Probe probe : PROBES) {
			List<ResolveInfo> hits = pm.queryIntentActivities(probe.intent, 0);
			for (ResolveInfo ri : hits) {
				if (ri.activityInfo == null) continue;
				String pkg = ri.activityInfo.packageName;
				if (!newCache.containsKey(pkg)) {
					newCache.put(pkg, probe.iconResId);
				}
			}
		}

		lastKnownPackages = currentPkgs;
		intentCache = newCache; // 原子替换
		persistToDisk();
		long elapsed = System.currentTimeMillis() - start;
		NokiaLog.i("S60IconMap", "init: 意图扫描完成，缓存 " + intentCache.size() + " 项，耗时 " + elapsed + "ms");
	}

	/**
	 * 根据包名查找对应的 S60 图标资源 ID（兼容旧调用，label 为空时跳过 label 层）。
	 *
	 * @param packageName 应用包名
	 * @return S60 图标资源 ID；0 = 未匹配
	 */
	@DrawableRes
	public static int getIcon(String packageName) {
		return getIcon(packageName, null);
	}

	/**
	 * 根据 (包名, 应用名) 查找对应的 S60 图标资源 ID。
	 * 确定性纯函数，结果只由参数决定，与历史缓存 / 已装应用集合无关：
	 * <ol>
	 *   <li>精确包名表（最高，恒确定）</li>
	 *   <li>应用名 label 关键词表（恒确定，label 由应用声明不可改）</li>
	 *   <li>意图探测缓存（仅长尾兜底，对同一 ROM 恒确定）</li>
	 * </ol>
	 *
	 * @param packageName 应用包名
	 * @param label       应用显示名（可 null；null 时跳过 label 层）
	 * @return S60 图标资源 ID；0 = 未匹配
	 */
	@DrawableRes
	public static int getIcon(String packageName, String label) {
		if (packageName == null) return 0;

		// 第 1 层：精确包名表（最高优先级，与 ROM / 缓存无关）
		Integer exact = EXACT_MAP.get(packageName);
		if (exact != null) {
			return exact;
		}

		// 第 2 层：应用名 label 关键词表（label 由应用声明，用户不可改 → 结果恒定）
		if (label != null && !label.isEmpty()) {
			Integer labelRes = matchLabel(label);
			if (labelRes != null) {
				return labelRes;
			}
		}

		// 第 3 层：意图探测缓存（仅长尾兜底；对同一 ROM 恒定）
		Integer intentRes = intentCache.get(packageName);
		if (intentRes != null) {
			return intentRes;
		}

		return 0;
	}

	/** label 关键词匹配：label 小写后包含任一关键词即命中（顺序固定，先命中先得 → 结果确定） */
	private static Integer matchLabel(String label) {
		String lower = label.toLowerCase(Locale.ROOT);
		for (Object[] entry : LABEL_FALLBACK) {
			int iconRes = (int) entry[0];
			for (int i = 1; i < entry.length; i++) {
				String keyword = (String) entry[i];
				if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
					NokiaLog.d("S60IconMap", "label 命中: '" + label + "' 含关键词 '" + keyword
							+ "' → resId=" + iconRes);
					return iconRes;
				}
			}
		}
		return null;
	}

	/**
	 * 根据 NokiaAppItem 中的包名反查是否匹配 S60 图标。
	 * 用于排序：能匹配到图标的排在前面。
	 */
	@DrawableRes
	public static int getIconForItem(NokiaAppItem item) {
		if (item == null || item.launchIntent == null
				|| item.launchIntent.getComponent() == null) return 0;
		return getIcon(item.launchIntent.getComponent().getPackageName(), item.label);
	}
}
