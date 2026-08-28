package ru.playsoftware.j2meloader.nokia;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.sqlite.db.SupportSQLiteProgram;
import androidx.sqlite.db.SupportSQLiteQuery;

import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.ui.focus.NokiaFocusHost;
import io.github.cctyl.nokia.common.util.NokiaDimens;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.applist.AppItem;
import ru.playsoftware.j2meloader.appsdb.AppDatabase;
import ru.playsoftware.j2meloader.appsdb.AppItemDao;
import ru.playsoftware.j2meloader.config.Config;

/**
 * 快捷栏应用选择界面。展示所有可选应用（安卓 + J2ME），多选后保存。
 * 支持方向键导航，SELECT 切换选中状态，左软键保存，右软键返回。
 */
public class NokiaShortcutSettingsFragment extends NokiaListPageFragment {

	private LinearLayout appListLayout;
	private final List<NokiaAppItem> allApps = new ArrayList<>();
	// 已选应用：key("type:appKey") -> ShortcutApp。以已保存列表为基准做增删，
	// 避免"取消一个"时基于 allApps 重建导致未匹配项（J2ME / 已卸载应用）被静默丢弃。
	private final Map<String, ShortcutApp> selectedMap = new LinkedHashMap<>();
	private NokiaSettingsStorage settingsStorage;
	private TextView tvSelectedCount;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_shortcut_settings;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		settingsStorage = new NokiaSettingsStorage(requireContext());
		appListLayout = view.findViewById(R.id.appListLayout);
		listScroll = view.findViewById(R.id.appScroll);
		tvSelectedCount = view.findViewById(R.id.tvSelectedCount);
		constrainScrollHeight(view, listScroll);

		// 加载已选中的应用（以已保存列表为基准，避免构建列表不完整时丢失）
		// 安卓应用统一以"包名"作为 key（而非 pkg/className），因为默认快捷栏生成
		// 短信/拨号等 action_ 应用时解析出的 Activity，与设置页 queryIntentActivities
		// 遍历出的 LAUNCHER Activity 常常不一致（如短信：默认 LaunchConversationActivity，
		// 列表 ConversationListActivity）。若用完整 className 作 key，取消勾选会误判"未选中"
		// 反而把应用重新加回，表现为"设置页显示未勾选但桌面仍展示"。统一包名即可消歧。
		List<ShortcutApp> current = settingsStorage.getShortcutApps();
		for (ShortcutApp app : current) {
			String key = app.type == ShortcutApp.TYPE_ANDROID
					? makeKey(ShortcutApp.TYPE_ANDROID, pkgOfAppKey(app.appKey))
					: makeKey(app.type, app.appKey);
			selectedMap.put(key, app);
		}
		NokiaLog.i("ShortcutSettings", "已加载 " + selectedMap.size() + " 个已选应用");

		// 异步加载应用列表
		loadAppsAsync();

		NokiaLog.i("ShortcutSettings", "快捷栏设置初始化完成");
	}

	// ---- 异步加载应用 ----

	private void loadAppsAsync() {
		Single.fromCallable(() -> {
					List<NokiaAppItem> result = new ArrayList<>();
					loadAndroidApps(result);
					loadJ2meApps(result);
					return result;
				})
				.subscribeOn(Schedulers.io())
				.observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
				.subscribe(
						apps -> {
							allApps.clear();
							allApps.addAll(apps);
							buildAppList();
							NokiaLog.i("ShortcutSettings", "应用列表加载完成：共 " + apps.size() + " 个（安卓 + J2ME）");
						},
						error -> {
							NokiaLog.e("ShortcutSettings", "加载应用列表失败", error);
							// 降级：至少加载安卓应用
							allApps.clear();
							loadAndroidApps(allApps);
							buildAppList();
						}
				);
	}

	/** 加载安卓可启动应用 */
	private void loadAndroidApps(List<NokiaAppItem> out) {
		PackageManager pm = requireActivity().getPackageManager();
		Intent main = new Intent(Intent.ACTION_MAIN, null);
		main.addCategory(Intent.CATEGORY_LAUNCHER);
		// flags=0：只枚举已安装且已启用的可启动组件，不混入停用组件/卸载残留包
		List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
		String selfPkg = requireActivity().getPackageName();
		// 同包多 launcher 入口（如系统相机/短信注册了多个 Activity）按包名去重，只保留第一个，
		// 避免同一个应用在快捷栏列表里出现多项、被重复加入快捷栏
		Set<String> seenPkgs = new HashSet<>();

		for (ResolveInfo ri : list) {
			ActivityInfo ai = ri.activityInfo;
			if (ai == null) continue;
			boolean appEnabled = ai.applicationInfo == null || ai.applicationInfo.enabled;
			boolean compEnabled = ai.enabled && appEnabled;
			NokiaLog.d("ShortcutSettings", "枚举入口: " + ai.packageName + "/" + ai.name
					+ " enabled=" + compEnabled
					+ " (componentEnabled=" + ai.enabled
					+ ", appEnabled=" + appEnabled + ")");
			if (ai.packageName.equals(selfPkg)) continue;
			if (!seenPkgs.add(ai.packageName)) {
				NokiaLog.d("ShortcutSettings", "同包多入口去重: " + ai.packageName + "/" + ai.name
						+ " (首个入口已选中)");
				continue;
			}
			NokiaLog.d("ShortcutSettings", "选中入口: " + ai.packageName + "/" + ai.name
					+ " enabled=" + compEnabled);

			CharSequence labelCs = ri.loadLabel(pm);
			String label = (labelCs != null && labelCs.length() > 0) ? labelCs.toString() : ai.name;
			Drawable icon = ri.loadIcon(pm);
			Intent launch = new Intent(Intent.ACTION_MAIN);
			launch.addCategory(Intent.CATEGORY_LAUNCHER);
			launch.setClassName(ai.packageName, ai.name);
			launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

			String appKey = ai.packageName + "/" + ai.name;
			NokiaAppItem item = new NokiaAppItem(NokiaAppItem.TYPE_APP, label, icon, launch);
			// 覆盖 label 用于 key 映射
			out.add(item);
		}

		NokiaLog.i("ShortcutSettings", "加载安卓应用: " + out.size() + " 个");
	}

	/** 加载 J2ME 已安装的应用 */
	private void loadJ2meApps(List<NokiaAppItem> out) {
		try {
			String emulatorDir = Config.getEmulatorDir();
			File dbFile = new File(emulatorDir, "J2ME-apps.db");
			if (!dbFile.exists()) {
				NokiaLog.i("ShortcutSettings", "J2ME 数据库不存在，跳过 JAR 应用加载");
				return;
			}

			AppDatabase db = AppDatabase.open(requireActivity().getApplicationContext(), emulatorDir);
			AppItemDao dao = db.appItemDao();
			List<AppItem> j2meApps = dao.getAllSingle(new SimpleSortQuery()).blockingGet();
			db.close();

			for (AppItem app : j2meApps) {
				String label = app.getTitle();
				Drawable icon = null;
				String iconPath = app.getImagePathExt();
				if (iconPath != null) {
					try {
						icon = Drawable.createFromPath(iconPath);
					} catch (Exception e) {
						NokiaLog.w("ShortcutSettings", "加载 J2ME 图标失败: " + iconPath);
					}
				}
				// 使用 pathExt 作为唯一标识
				String appKey = app.getPathExt();
				NokiaAppItem item = new NokiaAppItem(NokiaAppItem.TYPE_BOX, label, icon, null);
				// 劫持 launchIntent 的 component 来存储 J2ME 信息（临时代码结构）
				// 用一个特殊 Intent 来携带 J2ME 数据
				Intent placeholder = new Intent();
				placeholder.setClassName(requireActivity().getPackageName(),
						"j2me:" + label + ":" + app.getPathExt());
				item.launchIntent = placeholder;
				out.add(item);
			}

			NokiaLog.i("ShortcutSettings", "加载 J2ME 应用: " + j2meApps.size() + " 个");
		} catch (Exception e) {
			NokiaLog.e("ShortcutSettings", "加载 J2ME 应用失败", e);
		}
	}

	/** Simple SupportSQLiteQuery for "SELECT * FROM apps ORDER BY title" */
	private static class SimpleSortQuery implements SupportSQLiteQuery {
		@Override
		public String getSql() {
			return "SELECT * FROM apps ORDER BY title ASC";
		}

		@Override
		public void bindTo(SupportSQLiteProgram statement) {}

		@Override
		public int getArgCount() { return 0; }
	}

	// ---- 构建应用列表 UI ----

	private void buildAppList() {
		if (appListLayout == null) return;
		appListLayout.removeAllViews();

		if (allApps.isEmpty()) {
			TextView empty = new TextView(requireContext());
			empty.setText("未找到可添加的应用");
			empty.setTextColor(0xFFAAAAAA);
			NokiaFontManager.textSize(empty, 12);
			empty.setGravity(Gravity.CENTER);
			empty.setPadding(0, NokiaDimens.dp(getResources(), 20), 0, 0);
			appListLayout.addView(empty);
			itemViews = new View[0];
			updateCountText();
			return;
		}

		itemViews = new View[allApps.size()];
		for (int i = 0; i < allApps.size(); i++) {
			NokiaAppItem app = allApps.get(i);
			String key = makeKeyForItem(app);

			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, NokiaDimens.dp(getResources(), 38)));
			row.setPadding(NokiaDimens.dp(getResources(), 8), NokiaDimens.dp(getResources(), 3), NokiaDimens.dp(getResources(), 8), NokiaDimens.dp(getResources(), 3));
			row.setClickable(true);


			// 图标：优先 S60 风格图标（安卓应用，命中时替换；J2ME 占位 component 不参与）
			Drawable listIcon = app.icon;
			if (app.launchIntent != null && app.launchIntent.getComponent() != null
					&& !key.startsWith(ShortcutApp.TYPE_J2ME + ":")) {
				String pkg = app.launchIntent.getComponent().getPackageName();
				int s60Res = NokiaS60IconMap.getIcon(pkg, app.label);
				if (s60Res != 0) {
					try {
						Drawable s60Icon = ContextCompat.getDrawable(requireContext(), s60Res);
						if (s60Icon != null) listIcon = s60Icon;
					} catch (Exception ignored) {}
				}
			}
			ImageView iv = new ImageView(requireContext());
			iv.setLayoutParams(new LinearLayout.LayoutParams(NokiaDimens.dp(getResources(), 24), NokiaDimens.dp(getResources(), 24)));
			if (listIcon != null) {
				iv.setImageDrawable(listIcon);
			} else {
				try {
					iv.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.mipmap.ic_launcher));
				} catch (Exception ignored) {}
			}
			row.addView(iv);

			// 间距
			View space = new View(requireContext());
			space.setLayoutParams(new LinearLayout.LayoutParams(NokiaDimens.dp(getResources(), 8), 1));
			row.addView(space);

			// 应用名
			TextView tv = new TextView(requireContext());
			tv.setLayoutParams(new LinearLayout.LayoutParams(
					0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
			tv.setText(app.label);
			tv.setTextColor(0xFFFFFFFF);
			NokiaFontManager.textSize(tv, 12);
			tv.setSingleLine(true);
			tv.setEllipsize(TextUtils.TruncateAt.END);
			row.addView(tv);

			// 选中/未选中标记
			TextView tvCheck = new TextView(requireContext());
			tvCheck.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
			tvCheck.setWidth(NokiaDimens.dp(getResources(), 24));
			tvCheck.setHeight(NokiaDimens.dp(getResources(), 24));
			tvCheck.setGravity(Gravity.CENTER);
			NokiaFontManager.textSize(tvCheck, 14);
			if (selectedMap.containsKey(key)) {
				tvCheck.setText("[✓]");
				tvCheck.setTextColor(0xFF4CAF50);
			} else {
				tvCheck.setText("[ ]");
				tvCheck.setTextColor(0xFF888888);
			}
			tvCheck.setTag("check_" + i);
			row.addView(tvCheck);

			row.setTag(key);
			final int index = i;
			row.setOnClickListener(v -> {
				setFocusIndex(index);
				toggleSelection(index);
			});

			appListLayout.addView(row);
			itemViews[i] = row;
		}

		updateCountText();
		setFocusIndex(0);
	}

	private String makeKeyForItem(NokiaAppItem app) {
		if (app.launchIntent != null && app.launchIntent.getComponent() != null) {
			String cls = app.launchIntent.getComponent().getClassName();
			if (cls != null && cls.startsWith("j2me:")) {
				// 与 ShortcutApp.appKey(pathExt) 保持一致：1:pathExt，
				// 避免与已保存 key(1:pathExt) 不一致导致已选 J2ME 应用丢失
				String[] parts = cls.split(":", 3);
				return ShortcutApp.TYPE_J2ME + ":" + (parts.length > 2 ? parts[2] : cls);
			}
		// 安卓应用只用"包名"作为 key，忽略 Activity className。
		// 默认快捷栏生成短信/拨号等 action_ 应用时解析出的 Activity，
		// 与设置页 queryIntentActivities 遍历出的 LAUNCHER Activity 常常不一致
		// （如短信：默认 LaunchConversationActivity，列表 ConversationListActivity）。
		// 若用完整 className 作 key，取消勾选会误判"未选中"反而把应用重新加回，
		// 表现为"设置页显示未勾选但桌面仍展示"。统一包名即可消歧。
		return ShortcutApp.TYPE_ANDROID + ":"
				+ app.launchIntent.getComponent().getPackageName();
	}
		return "unknown:" + app.label;
	}

	private static String makeKey(int type, String appKey) {
		return type + ":" + appKey;
	}

	/** 从已保存的 appKey(pkg/className) 中取包名，用于与设置页列表按包名匹配 */
	private static String pkgOfAppKey(String appKey) {
		if (appKey == null) return "";
		int slash = appKey.indexOf('/');
		return slash > 0 ? appKey.substring(0, slash) : appKey;
	}

	private void toggleSelection(int index) {
		if (index < 0 || index >= allApps.size()) return;
		NokiaAppItem app = allApps.get(index);
		String key = makeKeyForItem(app);

		if (selectedMap.containsKey(key)) {
			selectedMap.remove(key);
			NokiaLog.d("ShortcutSettings", "取消选中: " + app.label);
		} else {
			ShortcutApp sa = buildShortcutApp(app);
			if (sa == null) {
				NokiaLog.w("ShortcutSettings", "无法构造快捷项，忽略: " + app.label);
				return;
			}
			selectedMap.put(key, sa);
			NokiaLog.d("ShortcutSettings", "选中: " + app.label);
		}

		// 刷新当前行的勾选标记
		if (itemViews != null && index < itemViews.length && itemViews[index] != null) {
			View row = itemViews[index];
			TextView check = row.findViewWithTag("check_" + index);
			if (check != null) {
				if (selectedMap.containsKey(key)) {
					check.setText("[✓]");
					check.setTextColor(0xFF4CAF50);
				} else {
					check.setText("[ ]");
					check.setTextColor(0xFF888888);
				}
			}
		}
		updateCountText();
		// 即时持久化，无需再按保存
		persistSelection();
	}

	private void updateCountText() {
		if (tvSelectedCount != null) {
			tvSelectedCount.setText("已选 " + selectedMap.size() + " / " + allApps.size() + " 项");
		}
	}

	// ---- 保存选择 ----

	/** 仅持久化，不退出页面 */
	private void persistSelection() {
		// 直接基于已选 Map 写回，仅增删用户实际操作的项；
		// 不再遍历 allApps 重建，避免 key 不匹配 / 列表未加载完整的项被静默丢弃
		List<ShortcutApp> result = new ArrayList<>(selectedMap.values());
		settingsStorage.setShortcutApps(result);
		NokiaLog.i("ShortcutSettings", "即时保存 " + result.size() + " 个快捷栏应用");
	}

	/** 根据列表项构造可持久化的 ShortcutApp；无法构造时返回 null */
	private ShortcutApp buildShortcutApp(NokiaAppItem app) {
		if (app.launchIntent == null || app.launchIntent.getComponent() == null) {
			return null;
		}
		String cls = app.launchIntent.getComponent().getClassName();
		if (cls != null && cls.startsWith("j2me:")) {
			String[] parts = cls.split(":", 3);
			String j2meLabel = parts.length > 1 ? parts[1] : app.label;
			String j2mePath = parts.length > 2 ? parts[2] : "";
			String iconPathStr = null;
			String imgPath = Config.getAppDir() + new File(j2mePath).getName() + "/icon.png";
			if (new File(imgPath).exists()) {
				iconPathStr = imgPath;
			}
			return new ShortcutApp(ShortcutApp.TYPE_J2ME, j2meLabel, j2mePath, iconPathStr);
		} else {
			Intent launch = new Intent(Intent.ACTION_MAIN);
			launch.addCategory(Intent.CATEGORY_LAUNCHER);
			launch.setClassName(
					app.launchIntent.getComponent().getPackageName(),
					app.launchIntent.getComponent().getClassName());
			launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
			String appKey = app.launchIntent.getComponent().getPackageName()
					+ "/" + app.launchIntent.getComponent().getClassName();
			return new ShortcutApp(ShortcutApp.TYPE_ANDROID, app.label, appKey, launch);
		}
	}

	/** 保存并返回上一级（左软键行为） */
	private void saveAndExit() {
		persistSelection();
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
	}

	// ---- NokiaFocusHost ----

	@Override
	public boolean onSelect() {
		if (focusIndex >= 0 && focusIndex < allApps.size()) {
			toggleSelection(focusIndex);
		}
		return true;
	}

	@Override
	public boolean onSoftLeft() {
		return false;
	}

	@Override
	public boolean onSoftRight() {
		NokiaLog.i("ShortcutSettings", "右软键：不保存返回");
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	@Override
	public boolean onBack() {
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	// ---- NokiaPage 接口（底部菜单栏声明，由 host.refreshPageBar() 装配） ----

	@Override
	public String getPageTitle() {
		return "快捷栏设置";
	}

	@Override
	public String getSoftLeftText() {
		return null;
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}

	// ---- 焦点管理 ----



}
