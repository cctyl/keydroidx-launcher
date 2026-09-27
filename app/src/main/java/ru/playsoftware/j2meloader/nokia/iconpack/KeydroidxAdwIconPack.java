package ru.playsoftware.j2meloader.nokia.iconpack;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.cctyl.nokia.common.log.KeydroidxLog;

/**
 * 基于 ADW 映射表的图标包实现，内置 S60 与外部已装图标包共用同一套代码，仅数据源不同：
 * <ul>
 *   <li><b>内置 S60</b>（{@code id=s60_builtin}）：读本应用 {@code assets/s60/appfilter.xml}
 *       与 {@code assets/s60/drawable.xml}，图标取自 {@code R.drawable.s60_*}；</li>
 *   <li><b>外部包</b>（{@code id=adw:<包名>}）：按 ADW 标准依次尝试对方的
 *       {@code res/xml/appfilter.xml} → {@code res/raw/appfilter.xml} → {@code assets/appfilter.xml}，
 *       图标通过 {@code Resources.getIdentifier(name,"drawable",pkg)} 取。
 *       drawable.xml 同理。</li>
 * </ul>
 *
 * <p>容错：任一步失败只让本包「无映射」，调用方回退应用原图标，不影响主流程。</p>
 */
public class KeydroidxAdwIconPack extends KeydroidxIconPack {

	private static final String TAG = "IconPack";

	/** 内置 S60 图标包 ID（默认图标包） */
	public static final String ID_BUILTIN = "s60_builtin";
	/** 外部图标包 ID 前缀 */
	public static final String PREFIX_ADW = "adw:";

	/** 内置包 assets 目录 */
	private static final String BUILTIN_ASSET_DIR = "s60";

	private final String id;
	/** 外部包名；内置包为 null */
	private final String externalPackage;
	/** 显示名（枚举到真实应用名后回填，故非 final） */
	private volatile String displayName;

	private final Object loadLock = new Object();
	private volatile boolean loaded;
	private volatile KeydroidxAdwIconParser.Result data;
	/** 外部包的 Resources（解析成功后缓存，避免每个图标一次 IPC） */
	private volatile Resources externalResources;

	private KeydroidxAdwIconPack(String id, String externalPackage, String displayName) {
		this.id = id;
		this.externalPackage = externalPackage;
		this.displayName = displayName;
	}

	/** 内置 S60 图标包单例 */
	public static KeydroidxAdwIconPack builtin() {
		return BuiltinHolder.INSTANCE;
	}

	/** 构造外部图标包（label 为应用显示名） */
	public static KeydroidxAdwIconPack external(String packageName, String label) {
		String name = TextUtils.isEmpty(label) ? packageName : label;
		return new KeydroidxAdwIconPack(PREFIX_ADW + packageName, packageName, name);
	}

	/** 由图标包 ID 构造（{@code adw:<pkg>}）；非该前缀返回 null */
	public static KeydroidxAdwIconPack fromId(String packId, String label) {
		if (packId == null) return null;
		if (ID_BUILTIN.equals(packId)) return builtin();
		if (!packId.startsWith(PREFIX_ADW)) return null;
		String pkg = packId.substring(PREFIX_ADW.length());
		if (TextUtils.isEmpty(pkg)) return null;
		return external(pkg, label);
	}

	/** 外部图标包对应的包名；内置包返回 null */
	public String getPackageName() {
		return externalPackage;
	}

	public boolean isBuiltin() {
		return externalPackage == null;
	}

	/** 回填/刷新显示名（枚举到真实应用名时；空值忽略，避免把好名字覆盖成 null） */
	void updateDisplayName(String name) {
		if (!TextUtils.isEmpty(name)) {
			this.displayName = name;
		}
	}

	@Override
	public String getId() {
		return id;
	}

	@Override
	public String getDisplayName() {
		return displayName;
	}

	@Override
	public boolean isLoaded() {
		return loaded;
	}

	@Override
	public void ensureLoaded(Context context) {
		if (loaded) return;
		synchronized (loadLock) {
			if (loaded) return;
			long start = System.currentTimeMillis();
			try {
				data = isBuiltin() ? loadBuiltin(context) : loadExternal(context);
			} catch (Throwable t) {
				// 图标包损坏 / 被卸载 / ROM IPC 异常：视为「无映射」，回退原图标
				KeydroidxLog.w(TAG, "解析图标包失败 " + id + ": " + t);
				data = new KeydroidxAdwIconParser.Result();
			}
			loaded = true;
			KeydroidxAdwIconParser.Result r = data;
			KeydroidxLog.i(TAG, "图标包解析完成 " + id + "：映射 " + r.mappedCount
					+ " 条（组件 " + r.componentTable.size() + " / 包 " + r.packageTable.size()
					+ "），可挑选 " + r.drawableNames.size() + " 个，忽略 " + r.skippedCount
					+ " 条，耗时 " + (System.currentTimeMillis() - start) + "ms");
		}
	}

	/** 释放跨包资源引用（图标包切换 / 缓存失效时调用，避免长期持有外部包的 AssetManager） */
	public void release() {
		synchronized (loadLock) {
			externalResources = null;
			if (!isBuiltin()) {
				// 外部包可能被升级/卸载，映射表与资源都需要重新解析
				data = null;
				loaded = false;
			}
		}
	}

	private KeydroidxAdwIconParser.Result loadBuiltin(Context context) {
		AssetManager am = context.getAssets();
		XmlPullParser appfilter = null;
		XmlPullParser drawable = null;
		try {
			appfilter = KeydroidxAdwIconParser.newParser(
					am.open(BUILTIN_ASSET_DIR + "/appfilter.xml"));
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "内置 appfilter.xml 缺失或打开失败: " + e.getMessage());
		}
		try {
			drawable = KeydroidxAdwIconParser.newParser(
					am.open(BUILTIN_ASSET_DIR + "/drawable.xml"));
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "内置 drawable.xml 缺失或打开失败: " + e.getMessage());
		}
		return KeydroidxAdwIconParser.parse(appfilter, drawable);
	}

	private KeydroidxAdwIconParser.Result loadExternal(Context context) throws Exception {
		PackageManager pm = context.getPackageManager();
		Resources res = pm.getResourcesForApplication(externalPackage);
		externalResources = res;
		XmlPullParser appfilter = openExternal(res, "appfilter");
		XmlPullParser drawable = openExternal(res, "drawable");
		if (appfilter == null) {
			KeydroidxLog.w(TAG, "外部图标包无 appfilter.xml: " + externalPackage);
		}
		return KeydroidxAdwIconParser.parse(appfilter, drawable);
	}

	/**
	 * 按 ADW 标准的三处位置打开 XML：{@code res/xml} → {@code res/raw} → {@code assets}。
	 *
	 * @param base 文件名（不含扩展名），如 {@code appfilter}
     * @return 解析器；三处都不存在返回 null
	 */
	private XmlPullParser openExternal(Resources res, String base) {
		// 1) res/xml/<base>.xml（推荐位置，编译后的二进制 XML，getXml 直接给解析器）
		try {
			int id = res.getIdentifier(base, "xml", externalPackage);
			if (id != 0) {
				return res.getXml(id);
			}
		} catch (Exception e) {
			KeydroidxLog.d(TAG, "读取 res/xml/" + base + ".xml 失败: " + e.getMessage());
		}
		// 2) res/raw/<base>.xml
		try {
			int id = res.getIdentifier(base, "raw", externalPackage);
			if (id != 0) {
				return KeydroidxAdwIconParser.newParser(res.openRawResource(id));
			}
		} catch (Exception e) {
			KeydroidxLog.d(TAG, "读取 res/raw/" + base + ".xml 失败: " + e.getMessage());
		}
		// 3) assets/<base>.xml（IconShowcase / 多数第三方包的位置）
		try {
			InputStream in = res.getAssets().open(base + ".xml");
			return KeydroidxAdwIconParser.newParser(in);
		} catch (Exception e) {
			KeydroidxLog.d(TAG, "读取 assets/" + base + ".xml 失败: " + e.getMessage());
		}
		return null;
	}

	@Override
	public String getIconNameFor(String pkg, ComponentName cn, String label) {
		KeydroidxAdwIconParser.Result r = data;
		if (!loaded || r == null) return null;

		String componentPkg = pkg;
		String className = null;
		if (cn != null) {
			if (TextUtils.isEmpty(componentPkg)) componentPkg = cn.getPackageName();
			className = cn.getClassName();
		}
		if (TextUtils.isEmpty(componentPkg)) return null;

		if (!TextUtils.isEmpty(className)) {
			// ADW 的 ComponentInfo 可能写全限定类名，也可能写 ".Foo" 简写
			String full = className.startsWith(".") ? componentPkg + className : className;
			String hit = r.componentTable.get(componentPkg + "/" + full);
			if (hit == null && !full.equals(className)) {
				hit = r.componentTable.get(componentPkg + "/" + className);
			}
			if (hit != null) return hit;
		}
		return r.packageTable.get(componentPkg);
	}

	@Override
	public boolean hasIcon(String iconName) {
		if (TextUtils.isEmpty(iconName)) return false;
		if (isBuiltin()) return KeydroidxS60Icons.has(iconName);
		KeydroidxAdwIconParser.Result r = data;
		if (r == null) return false;
		return r.drawableNames.contains(iconName) || r.referencedNames.contains(iconName);
	}

	@Override
	public Drawable getIconByName(Context context, String iconName) {
		if (TextUtils.isEmpty(iconName) || context == null) return null;
		try {
			if (isBuiltin()) {
				int resId = KeydroidxS60Icons.idOf(iconName);
				if (resId == 0) {
					KeydroidxLog.d(TAG, "内置图标名不存在: " + iconName);
					return null;
				}
				Drawable d = ContextCompat.getDrawable(context, resId);
				return d != null ? d.mutate() : null;
			}
			Resources res = externalResources;
			if (res == null) {
				res = context.getPackageManager().getResourcesForApplication(externalPackage);
				externalResources = res;
			}
			int resId = res.getIdentifier(iconName, "drawable", externalPackage);
			if (resId == 0) {
				KeydroidxLog.d(TAG, "外部图标包缺少 drawable: " + externalPackage + "/" + iconName);
				return null;
			}
			Drawable d = ResourcesCompat.getDrawable(res, resId, null);
			return d != null ? d.mutate() : null;
		} catch (Throwable t) {
			// 图标包被卸载、资源损坏等：视为取图失败，调用方回退原图标
			KeydroidxLog.w(TAG, "取图标失败 " + id + "/" + iconName + ": " + t);
			return null;
		}
	}

	@Override
	public List<String> listIconNames() {
		KeydroidxAdwIconParser.Result r = data;
		if (!loaded || r == null) return Collections.emptyList();
		if (!r.drawableNames.isEmpty()) return new ArrayList<>(r.drawableNames);
		// drawable.xml 缺失 → 退化为 appfilter 中出现过的图标名，保证挑选页非空
		return new ArrayList<>(r.referencedNames);
	}

	@Override
	public String getIconLabel(String iconName) {
		return isBuiltin() ? KeydroidxS60Icons.labelOf(iconName) : iconName;
	}

	/** 映射条目数（设置页/日志展示用） */
	public int getMappedCount() {
		KeydroidxAdwIconParser.Result r = data;
		return r != null ? r.mappedCount : 0;
	}

	private static final class BuiltinHolder {
		private static final KeydroidxAdwIconPack INSTANCE =
				new KeydroidxAdwIconPack(ID_BUILTIN, null, "内置 S60 图标包");
	}
}
