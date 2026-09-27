package ru.playsoftware.j2meloader.nokia;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import io.github.cctyl.nokia.common.ui.KeydroidxFontManager;
import io.github.cctyl.nokia.common.util.KeydroidxDimens;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import java.util.ArrayList;
import java.util.List;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.nokia.iconpack.KeydroidxIconPack;
import ru.playsoftware.j2meloader.nokia.iconpack.KeydroidxIconPackManager;
import ru.playsoftware.j2meloader.nokia.iconpack.KeydroidxIconResolver;

/**
 * 图标挑选页（分页网格）：功能表 → 选项 → 更换图标 → 选中图标包后进入。
 *
 * <p>展示所选图标包的全部图标（内置 S60 显示中文名），方向键在页内移动、上下到边界翻页，
 * 左软键「选择」写入该应用的图标覆盖并返回功能表，右软键「返回」放弃。</p>
 */
public class KeydroidxIconPickerFragment extends KeydroidxPageFragment {

	private static final String TAG = "IconPicker";

	private static final String ARG_PACK_ID = "pack_id";
	private static final String ARG_PKG = "pkg";
	private static final String ARG_LABEL = "label";

	/** 列数：240dp 宽下 4 列每列约 56dp，放得下 32dp 图标 + 短标签 */
	private static final int COLS = 4;
	/** 图标框基准边长（dp） */
	private static final int ICON_BOX_DP = 32;
	/** 标签行高预算（dp）：8sp × 点阵字体行距 1.6 + 2dp */
	private static final float LABEL_BUDGET_DP = 8f * 1.6f + 2f;

	private String packId;
	private String targetPkg;
	private String targetLabel;
	private KeydroidxIconPack pack;

	private final List<String> iconNames = new ArrayList<>();
	private LinearLayout grid;
	private TextView tvPage;

	private int rowsPerPage = 4;
	private int perPage = COLS * 4;
	private int totalPages = 1;
	private int pageIndex = 0;
	private int focusPos = 0;
	private View[] cellViews;
	/** 当前高亮选中的单元格（清除旧高亮用） */
	private View selectedView;

	public static KeydroidxIconPickerFragment newInstance(String packId, String pkg, String label) {
		KeydroidxIconPickerFragment f = new KeydroidxIconPickerFragment();
		Bundle args = new Bundle();
		args.putString(ARG_PACK_ID, packId);
		args.putString(ARG_PKG, pkg);
		args.putString(ARG_LABEL, label);
		f.setArguments(args);
		return f;
	}

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_keydroidx_menu;
	}

	@Override
	public String getPageTitle() {
		return "选择图标";
	}

	@Override
	public String getSoftLeftText() {
		return "选择";
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}

	@Override
	public boolean onSoftLeft() {
		return onSelect();
	}

	@Override
	public boolean onSoftRight() {
		((KeydroidxDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	@Override
	public boolean onBack() {
		return onSoftRight();
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		Bundle args = getArguments();
		if (args != null) {
			packId = args.getString(ARG_PACK_ID);
			targetPkg = args.getString(ARG_PKG);
			targetLabel = args.getString(ARG_LABEL);
		}
		grid = view.findViewById(R.id.appGrid);
		tvPage = view.findViewById(R.id.menuPage);
		TextView title = view.findViewById(R.id.menuTitle);
		if (title != null) {
			title.setText("选择图标");
		}

		pack = KeydroidxIconPackManager.get().findPack(packId);
		if (pack == null || TextUtils.isEmpty(targetPkg)) {
			KeydroidxLog.w(TAG, "参数非法：packId=" + packId + " pkg=" + targetPkg);
			Toast.makeText(requireContext(), "图标包不可用", Toast.LENGTH_SHORT).show();
			((KeydroidxDesktopActivity) requireActivity()).exitCurrent();
			return;
		}
		if (title != null) {
			title.setText(pack.getDisplayName());
		}
		loadIconNamesAsync();
	}

	/** 图标清单可能尚未解析（外部图标包首次进入）：后台解析完成后再铺网格 */
	private void loadIconNamesAsync() {
		if (pack.isLoaded()) {
			populate();
			return;
		}
		showEmptyHint("正在读取图标包…");
		final Context appCtx = requireContext().getApplicationContext();
		final Handler main = new Handler(Looper.getMainLooper());
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					pack.ensureLoaded(appCtx);
				} catch (Throwable t) {
					KeydroidxLog.w(TAG, "解析图标包失败: " + t);
				}
				main.post(new Runnable() {
					@Override
					public void run() {
						if (isAdded() && getView() != null) populate();
					}
				});
			}
		}, "icon-picker-load").start();
	}

	private void showEmptyHint(String text) {
		if (grid == null) return;
		grid.removeAllViews();
		TextView tv = new TextView(requireContext());
		tv.setText(text);
		tv.setTextColor(0xFFAAAAAA);
		KeydroidxFontManager.textSize(tv, 12);
		tv.setGravity(Gravity.CENTER);
		tv.setPadding(0, KeydroidxDimens.dp(getResources(), 20), 0, 0);
		grid.addView(tv);
	}

	private void populate() {
		iconNames.clear();
		iconNames.addAll(pack.listIconNames());
		KeydroidxLog.i(TAG, "图标清单就绪：" + iconNames.size() + " 个（" + pack.getId() + "）");
		if (iconNames.isEmpty()) {
			showEmptyHint("该图标包没有可用图标");
			if (tvPage != null) tvPage.setText("");
			return;
		}
		computeRowsPerPage();
		totalPages = Math.max(1, (int) Math.ceil((double) iconNames.size() / perPage));
		pageIndex = 0;
		cellViews = new View[perPage];
		buildCurrentPage();
		setFocusPos(0);
	}

	/** 按可用高度与字号决定每页行数（与功能表同一套预算口径） */
	private void computeRowsPerPage() {
		KeydroidxDesktopActivity host = (KeydroidxDesktopActivity) requireActivity();
		int panelH = host.getMidPanelHeight();
		float density = getResources().getDisplayMetrics().density;
		float scale = host.getScale();
		float availDesign = panelH > 0 ? (panelH / density / scale) : 262f;
		float fontScale = KeydroidxSettingsStorage.getFontScale(requireContext());
		if (fontScale <= 0f) fontScale = 1f;
		float iconScale = Math.max(0.8f, 1f + (fontScale - 1f) * 0.6f);
		float rowNeed = ICON_BOX_DP * iconScale + 8f + LABEL_BUDGET_DP * fontScale + 2f;
		float titleBudget = 13f * fontScale * 1.5f + 2f;
		int rows = (int) Math.floor(Math.max(0f, availDesign - titleBudget) / rowNeed);
		rowsPerPage = Math.max(2, Math.min(6, rows));
		perPage = COLS * rowsPerPage;
		KeydroidxLog.d(TAG, "每页行数=" + rowsPerPage + "（iconScale=" + iconScale
				+ ", fontScale=" + fontScale + ", availDesign=" + availDesign + "）");
	}

	private void buildCurrentPage() {
		if (grid == null) return;
		grid.removeAllViews();
		for (int i = 0; i < cellViews.length; i++) {
			cellViews[i] = null;
		}

		KeydroidxDesktopActivity host = (KeydroidxDesktopActivity) requireActivity();
		float density = getResources().getDisplayMetrics().density;
		float scale = host.getScale();
		int panelH = host.getMidPanelHeight();
		float availDesign = panelH > 0 ? (panelH / density / scale) : 262f;
		float fontScale = KeydroidxSettingsStorage.getFontScale(requireContext());
		if (fontScale <= 0f) fontScale = 1f;
		float rowActualDp = rowsPerPage > 0 ? ((availDesign - 13f * fontScale * 1.5f - 2f) / rowsPerPage)
				: (ICON_BOX_DP + LABEL_BUDGET_DP);
		int rowH = KeydroidxDimens.dp(getResources(), Math.round(rowActualDp));
		int iconBox = KeydroidxDimens.dp(getResources(),
				Math.round(ICON_BOX_DP * Math.max(0.8f, 1f + (fontScale - 1f) * 0.6f)));

		int start = pageIndex * perPage;
		int count = Math.min(perPage, iconNames.size() - start);

		for (int r = 0; r < rowsPerPage; r++) {
			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, rowH));

			for (int c = 0; c < COLS; c++) {
				int pos = r * COLS + c;
				LinearLayout cell = new LinearLayout(requireContext());
				cell.setOrientation(LinearLayout.VERTICAL);
				cell.setGravity(Gravity.CENTER);
				cell.setLayoutParams(new LinearLayout.LayoutParams(0,
						LinearLayout.LayoutParams.MATCH_PARENT, 1f));
				cell.setPadding(2, 2, 2, 2);

				if (pos < count) {
					final int index = start + pos;
					final String iconName = iconNames.get(index);

					ImageView iv = new ImageView(requireContext());
					iv.setLayoutParams(new LinearLayout.LayoutParams(iconBox, iconBox));
					Drawable icon = pack.getIconByName(requireContext(), iconName);
					if (icon != null) {
						iv.setImageDrawable(icon);
					}
					cell.addView(iv);

					TextView tv = new TextView(requireContext());
					tv.setText(pack.getIconLabel(iconName));
					tv.setTextColor(0xFFFFFFFF);
					KeydroidxFontManager.textSize(tv, 8);
					tv.setSingleLine(true);
					tv.setEllipsize(TextUtils.TruncateAt.END);
					tv.setMaxWidth(KeydroidxDimens.dp(getResources(), 52));
					cell.addView(tv);

					final int fPos = pos;
					cell.setClickable(true);
					cell.setOnClickListener(v -> {
						setFocusPos(fPos);
						onSelect();
					});
					cellViews[pos] = cell;
				}
				row.addView(cell);
			}
			grid.addView(row);
		}

		if (tvPage != null) {
			tvPage.setText((pageIndex + 1) + "/" + totalPages);
		}
	}

	public boolean onSelect() {
		if (iconNames.isEmpty()) return false;
		int index = pageIndex * perPage + focusPos;
		if (index < 0 || index >= iconNames.size()) return false;
		String iconName = iconNames.get(index);
		Context ctx = requireContext();
		KeydroidxSettingsStorage.setIconOverride(ctx, targetPkg, packId, iconName);
		KeydroidxIconResolver.invalidatePackage(ctx, targetPkg);
		KeydroidxLog.i(TAG, "已为 " + targetPkg + " 设置图标 " + packId + "/" + iconName);
		Toast.makeText(ctx, "已更换图标：" + pack.getIconLabel(iconName), Toast.LENGTH_SHORT).show();
		// 关闭挑选页 → 关闭图标包选择页，回到功能表（功能表重建后即为新图标）
		FragmentManager fm = getParentFragmentManager();
		fm.popBackStack();
		fm.popBackStack();
		return true;
	}

	// ---- 焦点与导航（分页网格，口径与功能表一致） ----

	private void setFocusPos(int pos) {
		int count = Math.min(perPage, Math.max(0, iconNames.size() - pageIndex * perPage));
		if (count <= 0) return;
		int target = Math.max(0, Math.min(pos, count - 1));
		if (selectedView != null) {
			selectedView.setBackgroundResource(0);
			selectedView = null;
		}
		focusPos = target;
		View cell = cellViews != null && target < cellViews.length ? cellViews[target] : null;
		if (cell != null) {
			cell.setBackground(io.github.cctyl.nokia.common.ui.KeydroidxTheme
					.createSelectionDrawable(requireContext(), 4));
			selectedView = cell;
		}
		if (tvPage != null) {
			tvPage.setText((pageIndex + 1) + "/" + totalPages);
		}
	}

	@Override
	public boolean onDirection(int direction) {
		int count = Math.min(perPage, Math.max(0, iconNames.size() - pageIndex * perPage));
		if (count == 0) return false;
		int row = focusPos / COLS;
		int col = focusPos % COLS;
		switch (direction) {
			case KeydroidxKeyBinding.ACTION_UP:
				if (row > 0 && (focusPos - COLS) < count) {
					setFocusPos(focusPos - COLS);
				} else if (pageIndex > 0) {
					pageIndex--;
					buildCurrentPage();
					setFocusPos(Math.min(focusPos, count - 1));
				}
				return true;
			case KeydroidxKeyBinding.ACTION_DOWN:
				if ((focusPos + COLS) < count) {
					setFocusPos(focusPos + COLS);
				} else if (pageIndex < totalPages - 1) {
					pageIndex++;
					buildCurrentPage();
					setFocusPos(focusPos % COLS);
				}
				return true;
			case KeydroidxKeyBinding.ACTION_LEFT:
				if (col > 0) {
					setFocusPos(focusPos - 1);
				} else if (row > 0) {
					setFocusPos(Math.min(focusPos + COLS - 1, count - 1));
				}
				return true;
			case KeydroidxKeyBinding.ACTION_RIGHT:
				if (col < COLS - 1 && (focusPos + 1) < count) {
					setFocusPos(focusPos + 1);
				} else if ((row + 1) * COLS < count) {
					setFocusPos(Math.min(row * COLS, count - 1));
				}
				return true;
			default:
				return false;
		}
	}
}
