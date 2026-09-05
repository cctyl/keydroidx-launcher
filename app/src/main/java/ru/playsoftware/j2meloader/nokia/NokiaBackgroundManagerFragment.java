package ru.playsoftware.j2meloader.nokia;
import io.github.cctyl.nokia.common.ui.NokiaFontManager;

import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.ui.NokiaIcons;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import io.github.cctyl.nokia.common.ui.NokiaTheme;
import io.github.cctyl.nokia.common.ui.focus.NokiaFocusHost;
import io.github.cctyl.nokia.common.util.NokiaDimens;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import ru.playsoftware.j2meloader.R;

/**
 * 后台管理窗口（桌面组件「后台管理」点击进入）。
 * <p>
 * 交互（与 HTML 原型一致）：
 * <ul>
 *   <li>顶部页签「运行 / 受保护」，方向键左右切换；</li>
 *   <li>方向键上下选择任务，确认键切换保护状态（盾牌实时标记）；</li>
 *   <li>左软键 = 全部保护 / 全部解除；右软键 = 清除全部未保护后台；</li>
 *   <li>返回键退出回桌面。</li>
 * </ul>
 * 保护名单持久化于 {@link NokiaSettingsStorage}，重启不丢。
 */
public class NokiaBackgroundManagerFragment extends NokiaPageFragment {

	private static final String TAG = "BgManager";

	private LinearLayout listLayout;
	private ScrollView scroll;
	private TextView tvSummary;
	private TextView tabRun, tabProt;
	private View tabRunUnderline, tabProtUnderline;

	/** 全部后台任务（含保护状态，运行页签显示全部，受保护页签过滤）。 */
	private final List<NokiaBgManagerHelper.BgTask> tasks = new ArrayList<>();
	/** 当前页签实际展示的任务。 */
	private final List<NokiaBgManagerHelper.BgTask> shownList = new ArrayList<>();
	private Set<String> protectedSet = new HashSet<>();
	private boolean tabProtected = false;
	private int focusIndex = -1;
	private View selectedView = null;

	private NokiaSettingsStorage settingsStorage;
	private Toast toast;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_bg_manager;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		settingsStorage = new NokiaSettingsStorage(requireContext());
		protectedSet = settingsStorage.getProtectedPackages();

		tabRun = view.findViewById(R.id.tabRun);
		tabProt = view.findViewById(R.id.tabProt);
		tabRunUnderline = view.findViewById(R.id.tabRunUnderline);
		tabProtUnderline = view.findViewById(R.id.tabProtUnderline);
		tvSummary = view.findViewById(R.id.tvBgSummary);
		listLayout = view.findViewById(R.id.bgListLayout);
		scroll = view.findViewById(R.id.bgScroll);

		view.findViewById(R.id.tabRunContainer).setOnClickListener(v -> switchTab(false));
		view.findViewById(R.id.tabProtContainer).setOnClickListener(v -> switchTab(true));

		// 约束 ScrollView 高度，使列表底部正好落在可视区底边
		view.post(() -> {
			if (scroll == null) return;
			View parent = (View) view.getParent();
			if (!(parent instanceof View)) return;
			int panelH = parent.getHeight();
			float scale = view.getScaleX();
			if (scale <= 0) scale = 1;
			int visibleH = (int) (panelH / scale);
			int headH = scroll.getTop();
			int scrollH = visibleH - headH;
			if (scrollH > 0) {
				ViewGroup.LayoutParams lp = scroll.getLayoutParams();
				lp.height = scrollH;
				scroll.setLayoutParams(lp);
				NokiaLog.i(TAG, "约束ScrollView高度: scrollH=" + scrollH);
			}
		});

		loadTasksAsync();
	}

	/** 后台线程枚举任务（含图标加载），完成后主线程渲染。未激活 mini_shizuku 时跳过枚举。 */
	private void loadTasksAsync() {
		final Context appCtx = requireContext().getApplicationContext();
		final Set<String> protSnapshot = new HashSet<>(protectedSet);
		new Thread(() -> {
			// 后台线程同步探测一次状态（含 TCP，主线程会报 NetworkOnMainThreadException）
			NokiaBgManagerHelper.probeShizukuSync();
			if (!NokiaBgManagerHelper.isBgManagerAvailable()) {
				mainHandler.post(() -> {
					if (!isAdded() || getView() == null) return;
					tasks.clear();
					renderList();
					NokiaLog.w(TAG, "mini_shizuku 未激活，后台管理不可用");
				});
				return;
			}
			final List<NokiaBgManagerHelper.BgTask> loaded =
					NokiaBgManagerHelper.enumerateBackgroundTasks(appCtx, protSnapshot);
			mainHandler.post(() -> {
				if (!isAdded() || getView() == null) return;
				tasks.clear();
				tasks.addAll(loaded);
				renderList();
				NokiaLog.i(TAG, "后台任务加载完成: " + tasks.size() + " 个");
			});
		}, "bg-manager-enum").start();
	}

	// ---- 渲染 ----

	private void renderList() {
		// 页签样式
		boolean run = !tabProtected;
		tabRun.setTextColor(run ? 0xFFFFFFFF : 0xFF8A93A5);
		tabRun.setTypeface(null, run ? Typeface.BOLD : Typeface.NORMAL);
		tabProt.setTextColor(tabProtected ? 0xFFFFFFFF : 0xFF8A93A5);
		tabProt.setTypeface(null, tabProtected ? Typeface.BOLD : Typeface.NORMAL);
		tabRunUnderline.setVisibility(run ? View.VISIBLE : View.GONE);
		tabProtUnderline.setVisibility(tabProtected ? View.VISIBLE : View.GONE);

		// 页签过滤：运行页签只显示未保护（可清理），受保护页签只显示已保护，互不重叠
		shownList.clear();
		int protCount = 0;
		for (NokiaBgManagerHelper.BgTask t : tasks) {
			if (t.prot) protCount++;
			if (t.prot != tabProtected) continue;
			shownList.add(t);
		}
		// 软键文字随页签变化（全部保护 / 全部解除）
		((NokiaDesktopActivity) requireActivity()).refreshPageBar();

		tvSummary.setText(tabProtected
				? "已保护 " + protCount + " 个应用（清除时自动跳过）· 按0键清理"
				: buildRunSummary());

		listLayout.removeAllViews();
		if (shownList.isEmpty()) {
			TextView empty = new TextView(requireContext());
			empty.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT,
					NokiaDimens.dp(getResources(), 40)));
			empty.setGravity(Gravity.CENTER);
			if (!NokiaBgManagerHelper.isBgManagerAvailable()) {
				empty.setText("未激活 mini_shizuku，左键「选项」可激活");
			} else {
				empty.setText(tabProtected ? "暂无保护的应用" : "没有可清理的后台应用");
			}
			empty.setTextColor(0xFF8A93A5);
			NokiaFontManager.textSize(empty, 10);
			listLayout.addView(empty);
			clearHighlight();
			focusIndex = -1;
			return;
		}

		if (focusIndex < 0 || focusIndex >= shownList.size()) {
			focusIndex = Math.max(0, Math.min(focusIndex, shownList.size() - 1));
		}

		for (int i = 0; i < shownList.size(); i++) {
			final NokiaBgManagerHelper.BgTask t = shownList.get(i);
			LinearLayout row = createTaskRow(t);
			if (row != null) {
				final int index = i;
				row.setOnClickListener(v -> {
					setFocusIndex(index);
					onSelect();
				});
				listLayout.addView(row);
			}
		}
		setFocusIndex(focusIndex);
	}

	/** 创建单个后台任务行：图标 + 名称 + 状态标记（受保护盾牌 / 可清）。 */
	private LinearLayout createTaskRow(NokiaBgManagerHelper.BgTask t) {
		Context ctx = requireContext();
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, NokiaDimens.dp(getResources(), 30)));
		row.setPadding(NokiaDimens.dp(getResources(), 6), 0,
				NokiaDimens.dp(getResources(), 6), 0);
		row.setClickable(true);

		// 应用图标 18dp
		ImageView iv = new ImageView(ctx);
		iv.setLayoutParams(new LinearLayout.LayoutParams(
				NokiaDimens.dp(getResources(), 18), NokiaDimens.dp(getResources(), 18)));
		iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
		if (t.icon != null) {
			iv.setImageDrawable(t.icon);
		} else {
			try {
				iv.setImageDrawable(ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher));
			} catch (Exception ignored) {}
		}
		row.addView(iv);

		// 应用名
		TextView nameTv = new TextView(ctx);
		nameTv.setLayoutParams(new LinearLayout.LayoutParams(
				0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
		nameTv.setText(t.name);
		nameTv.setTextColor(0xFFFFFFFF);
		NokiaFontManager.textSize(nameTv, 11);
		nameTv.setSingleLine(true);
		nameTv.setEllipsize(TextUtils.TruncateAt.END);
		row.addView(nameTv);

		// 状态标记：受保护 = 绿色盾牌；未保护（仅运行页签）= 灰色「可清」
		if (t.prot) {
			ImageView shield = new ImageView(ctx);
			shield.setLayoutParams(new LinearLayout.LayoutParams(
					NokiaDimens.dp(getResources(), 14), NokiaDimens.dp(getResources(), 14)));
			shield.setScaleType(ImageView.ScaleType.FIT_CENTER);
			try {
				shield.setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_nokia_protect));
			} catch (Exception ignored) {}
			row.addView(shield);
		} else if (!tabProtected) {
			TextView clearTv = new TextView(ctx);
			clearTv.setText("可清");
			clearTv.setTextColor(0xFF55606F);
			NokiaFontManager.textSize(clearTv, 9);
			row.addView(clearTv);
		}
		return row;
	}

	/** 运行页签摘要文字：未激活时提示去激活。 */
	private String buildRunSummary() {
		if (!NokiaBgManagerHelper.isBgManagerAvailable()) {
			return "未激活 mini_shizuku，左键「选项」可激活";
		}
		return "后台进程 " + tasks.size() + " 个 · 可清理 " + shownList.size() + " 个 · 按0键清理";
	}

	// ---- NokiaFocusHost ----

	@Override
	public boolean onDirection(int direction) {
		if (direction == NokiaKeyBinding.ACTION_LEFT || direction == NokiaKeyBinding.ACTION_RIGHT) {
			switchTab(direction == NokiaKeyBinding.ACTION_RIGHT);
			return true;
		}
		if (shownList.isEmpty()) return true;
		if (focusIndex < 0) {
			setFocusIndex(0);
			return true;
		}
		if (direction == NokiaKeyBinding.ACTION_UP) {
			setFocusIndex(focusIndex > 0 ? focusIndex - 1 : shownList.size() - 1);
			return true;
		}
		if (direction == NokiaKeyBinding.ACTION_DOWN) {
			setFocusIndex(focusIndex < shownList.size() - 1 ? focusIndex + 1 : 0);
			return true;
		}
		return true;
	}

	/** 切换页签（true=受保护，false=运行）。 */
	private void switchTab(boolean toProtected) {
		if (tabProtected == toProtected) return;
		tabProtected = toProtected;
		focusIndex = 0;
		renderList();
		NokiaLog.i(TAG, "切换到页签: " + (tabProtected ? "受保护" : "运行"));
	}

	@Override
	public boolean onSelect() {
		if (focusIndex < 0 || focusIndex >= shownList.size()) return false;
		NokiaBgManagerHelper.BgTask t = shownList.get(focusIndex);
		toggleProtect(t);
		return true;
	}

	/** 切换单个任务的保护状态，并持久化。 */
	private void toggleProtect(NokiaBgManagerHelper.BgTask t) {
		t.prot = !t.prot;
		if (t.prot) {
			protectedSet.add(t.pkg);
			showToast("已保护「" + t.name + "」");
		} else {
			protectedSet.remove(t.pkg);
			showToast("已解除「" + t.name + "」保护");
		}
		settingsStorage.setProtectedPackages(protectedSet);
		renderList();
	}

	@Override
	public boolean onSoftLeft() {
		// 左软键：弹出选项菜单（清除全部 / 全部保护 / 全部解除）
		showOptionsDialog();
		return true;
	}

	@Override
	public boolean onSoftRight() {
		// 右软键：退出返回桌面（与所有子页面一致；物理返回键绑定右软键动作，按返回键亦安全退出）
		NokiaLog.i(TAG, "右软键：退出后台管理");
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	/** 弹出诺基亚风格选项菜单（复用 NokiaOptionsDialog，符合软键栏规范）。 */
	private void showOptionsDialog() {
		List<NokiaOptionsDialog.OptionItem> items = new ArrayList<>();
		// Android 5.0+ 未激活 mini_shizuku 时，首要入口为「激活 mini_shizuku」
		if (!NokiaBgManagerHelper.isBgManagerAvailable()) {
			items.add(new NokiaOptionsDialog.OptionItem(NokiaIcons.ICON_SHIZUKU,
					"激活 mini_shizuku", true, false, this::requestShizukuActivation));
			NokiaOptionsDialog.show(getParentFragmentManager(), "后台管理", items);
			NokiaLog.i(TAG, "弹出选项菜单（仅激活入口）");
			return;
		}
		items.add(new NokiaOptionsDialog.OptionItem(NokiaIcons.ICON_CLEAR_ALL,
				"清除全部", true, false, this::clearAll));
		items.add(new NokiaOptionsDialog.OptionItem(NokiaIcons.ICON_LOCK,
				"全部保护", true, false, this::protectAll));
		items.add(new NokiaOptionsDialog.OptionItem(NokiaIcons.ICON_LOCK_OPEN,
				"全部解除", true, false, this::unprotectAll));
		NokiaOptionsDialog.show(getParentFragmentManager(), "后台管理", items);
		NokiaLog.i(TAG, "弹出选项菜单");
	}

	/** 引导用户进入 mini_shizuku 服务激活页（ShizukuFragment）。 */
	private void requestShizukuActivation() {
		NokiaLog.i(TAG, "请求激活 mini_shizuku");
		((NokiaDesktopActivity) requireActivity()).openFragment(new ShizukuFragment());
	}

	/** 全部保护：把当前所有后台加入保护名单。 */
	private void protectAll() {
		int n = 0;
		for (NokiaBgManagerHelper.BgTask t : tasks) {
			if (!t.prot) {
				t.prot = true;
				n++;
			}
		}
		protectedSet.clear();
		for (NokiaBgManagerHelper.BgTask t : tasks) {
			if (t.prot) protectedSet.add(t.pkg);
		}
		settingsStorage.setProtectedPackages(protectedSet);
		renderList();
		showToast(n > 0 ? "已保护 " + n + " 个后台应用" : "没有可保护的后台应用");
	}

	/** 全部解除：清空保护名单。 */
	private void unprotectAll() {
		int n = 0;
		for (NokiaBgManagerHelper.BgTask t : tasks) {
			if (t.prot) {
				t.prot = false;
				n++;
			}
		}
		protectedSet.clear();
		settingsStorage.setProtectedPackages(protectedSet);
		renderList();
		showToast(n > 0 ? "已解除 " + n + " 个应用的保护" : "没有受保护的应用");
	}

	/** 清除全部未保护的后台进程（跳过保护名单），并从列表移除已清理项。 */
	private void clearAll() {
		if (!NokiaBgManagerHelper.isBgManagerAvailable()) {
			showToast("请先激活 mini_shizuku");
			return;
		}
		final Context appCtx = requireContext().getApplicationContext();
		final Set<String> protSnapshot = new HashSet<>(protectedSet);
		// 清理涉及 TCP/shell 命令（ps -A、am force-stop），必须在后台线程执行：
		// 主线程上 Socket 连接会抛 NetworkOnMainThreadException，被 Helper 捕获后返回
		// 空任务列表，导致「清除全部」永远清理不了任何应用且静默失败。
		new Thread(() -> {
			// 先同步探测一次，避免缓存过期（服务已下线）误走无效分支
			NokiaBgManagerHelper.probeShizukuSync();
			if (!NokiaBgManagerHelper.isBgManagerAvailable()) {
				mainHandler.post(() -> showToast("请先激活 mini_shizuku"));
				return;
			}
			final int cleared = NokiaBgManagerHelper.clearBackgroundTasks(appCtx, protSnapshot);
			mainHandler.post(() -> {
				if (!isAdded() || getView() == null) return;
				if (cleared > 0) {
					// 乐观地从内存列表移除所有未保护任务，立即给用户反馈。
					// 下次进入页面会重新枚举真实状态。
					// 注意：不用 removeIf——它依赖 java.util.function.Predicate（API 24+），
					// 项目未开启 core library desugaring，在 Android 4.4 上会因找不到父接口
					// 而抛 NoClassDefFoundError。用迭代器显式删除兼容 4.4。
					Iterator<NokiaBgManagerHelper.BgTask> it = tasks.iterator();
					while (it.hasNext()) {
						if (!it.next().prot) it.remove();
					}
					renderList();
					showToast("已清理 " + cleared + " 个后台应用");
				} else {
					showToast("没有可清理的后台应用");
				}
			});
		}, "bg-manager-clear").start();
	}

	/** 数字键 0 触发：一键清理全部未保护后台（由 Activity 按键分发调用）。 */
	public boolean onCleanKey() {
		NokiaLog.i(TAG, "数字键 0：一键清理后台");
		clearAll();
		return true;
	}

	@Override
	public boolean onBack() {
		NokiaLog.i(TAG, "返回键：退出后台管理");
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	// ---- NokiaPage ----

	@Override
	public String getPageTitle() {
		return "后台管理";
	}

	@Override
	public String getSoftLeftText() {
		return "选项";
	}

	@Override
	public String getSoftRightText() {
		return "退出";
	}

	// ---- 焦点管理 ----

	private void setFocusIndex(int index) {
		if (index < 0 || index >= shownList.size()) return;
		clearHighlight();
		focusIndex = index;
		applyHighlight();
		scrollToVisible(index);
	}

	private void clearHighlight() {
		if (selectedView != null) {
			selectedView.setBackgroundResource(0);
			selectedView = null;
		}
	}

	private void applyHighlight() {
		if (selectedView != null) {
			selectedView.setBackgroundResource(0);
			selectedView = null;
		}
		if (listLayout == null || focusIndex < 0 || focusIndex >= listLayout.getChildCount()) return;
		View v = listLayout.getChildAt(focusIndex);
		v.setBackground(NokiaTheme.createSelectionDrawable(requireContext(), 4));
		selectedView = v;
	}

	private void scrollToVisible(int index) {
		if (scroll == null || listLayout == null || index < 0 || index >= listLayout.getChildCount()) return;
		smoothScrollToVisible(scroll, listLayout.getChildAt(index));
	}

	private void showToast(String msg) {
		if (toast != null) {
			toast.cancel();
		}
		toast = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT);
		toast.show();
		NokiaLog.i(TAG, "Toast: " + msg);
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		mainHandler.removeCallbacksAndMessages(null);
		selectedView = null;
		listLayout = null;
		scroll = null;
	}
}
