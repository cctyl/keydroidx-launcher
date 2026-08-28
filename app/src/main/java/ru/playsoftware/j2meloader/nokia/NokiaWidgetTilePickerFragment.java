package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.util.NokiaDimens;
import java.util.ArrayList;
import java.util.List;

import ru.playsoftware.j2meloader.R;

/**
 * 快捷开关（Quick Settings Tile）选择器界面。
 * <p>
 * 列出设备上所有第三方应用声明的 {@code android.service.quicksettings.action.QS_TILE} 开关服务，
 * 供用户选择并添加为桌面组件。
 */
public class NokiaWidgetTilePickerFragment extends NokiaListPageFragment {

	private static class TileInfo {
		final String packageName;
		final String serviceName;
		final String appLabel;
		final String tileLabel;
		final Drawable icon;

		TileInfo(String packageName, String serviceName, String appLabel, String tileLabel, Drawable icon) {
			this.packageName = packageName;
			this.serviceName = serviceName;
			this.appLabel = appLabel;
			this.tileLabel = tileLabel;
			this.icon = icon;
		}

		String getDisplayTitle() {
			if (!TextUtils.isEmpty(appLabel) && !appLabel.equals(tileLabel)) {
				return appLabel + " - " + tileLabel;
			}
			return tileLabel;
		}

		String getComponentValue() {
			return packageName + "/" + serviceName;
		}
	}

	private final List<TileInfo> tileList = new ArrayList<>();
	private LinearLayout tileListContainer;
	private TextView tvEmpty;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_widget_tile_picker;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		listScroll = view.findViewById(R.id.tileScroll);
		tileListContainer = view.findViewById(R.id.tileListLayout);
		tvEmpty = view.findViewById(R.id.tvEmpty);
		constrainScrollHeight(view, listScroll);

		loadTiles();
		buildListUI();
	}

	@Override
	public String getPageTitle() {
		return "选择快捷开关";
	}

	@Override
	public String getSoftLeftText() {
		return tileList.isEmpty() ? "" : "选择";
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}

	@Override
	public boolean onSoftLeft() {
		if (focusIndex >= 0 && focusIndex < tileList.size()) {
			selectTile(tileList.get(focusIndex));
			return true;
		}
		return false;
	}

	@Override
	public boolean onSelect() {
		return onSoftLeft();
	}

	@Override
	public boolean onSoftRight() {
		if (getActivity() instanceof NokiaDesktopActivity) {
			((NokiaDesktopActivity) getActivity()).exitCurrent();
			return true;
		}
		return false;
	}

	@Override
	public boolean onBack() {
		return onSoftRight();
	}

	/** 扫描设备上所有 QS_TILE 服务。 */
	private void loadTiles() {
		tileList.clear();
		Context context = getContext();
		if (context == null) return;
		PackageManager pm = context.getPackageManager();
		Intent intent = new Intent("android.service.quicksettings.action.QS_TILE");
		List<ResolveInfo> services = pm.queryIntentServices(intent, PackageManager.GET_META_DATA);

		if (services != null) {
			for (ResolveInfo ri : services) {
				ServiceInfo si = ri.serviceInfo;
				if (si == null) continue;

				String pkg = si.packageName;
				String name = si.name;
				String appLabel = "";
				try {
					if (si.applicationInfo != null) {
						appLabel = si.applicationInfo.loadLabel(pm).toString();
					}
				} catch (Exception ignored) {}

				String tileLabel = "";
				try {
					CharSequence cs = si.loadLabel(pm);
					if (cs != null) tileLabel = cs.toString();
				} catch (Exception ignored) {}

				if (TextUtils.isEmpty(tileLabel)) {
					tileLabel = appLabel;
				}
				if (TextUtils.isEmpty(tileLabel)) {
					tileLabel = name;
				}

				Drawable icon = null;
				try {
					icon = si.loadIcon(pm);
				} catch (Exception ignored) {}
				if (icon == null && si.applicationInfo != null) {
					try {
						icon = si.applicationInfo.loadIcon(pm);
					} catch (Exception ignored) {}
				}

				tileList.add(new TileInfo(pkg, name, appLabel, tileLabel, icon));
			}
		}
	}

	/** 构建诺基亚经典单列列表项。 */
	private void buildListUI() {
		tileListContainer.removeAllViews();

		if (tileList.isEmpty()) {
			tvEmpty.setVisibility(View.VISIBLE);
			itemViews = new View[0];
			if (getActivity() instanceof NokiaDesktopActivity) {
				((NokiaDesktopActivity) getActivity()).refreshPageBar();
			}
			return;
		}

		tvEmpty.setVisibility(View.GONE);
		int count = tileList.size();
		itemViews = new View[count];
		Context context = requireContext();

		for (int i = 0; i < count; i++) {
			TileInfo info = tileList.get(i);
			LinearLayout row = new LinearLayout(context);
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setPadding(NokiaDimens.dp(getResources(), 8),
					NokiaDimens.dp(getResources(), 6),
					NokiaDimens.dp(getResources(), 8),
					NokiaDimens.dp(getResources(), 6));
			row.setLayoutParams(new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));
			row.setClickable(true);

			// 开关图标
			ImageView ivIcon = new ImageView(context);
			ivIcon.setLayoutParams(new LinearLayout.LayoutParams(
					NokiaDimens.dp(getResources(), 24),
					NokiaDimens.dp(getResources(), 24)));
			ivIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
			if (info.icon != null) {
				ivIcon.setImageDrawable(info.icon);
			} else {
				ivIcon.setImageResource(R.drawable.ic_nokia_torch);
			}
			row.addView(ivIcon);

			// 开关名称
			TextView tvLabel = new TextView(context);
			LinearLayout.LayoutParams lpText = new LinearLayout.LayoutParams(
					0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
			lpText.leftMargin = NokiaDimens.dp(getResources(), 8);
			tvLabel.setLayoutParams(lpText);
			tvLabel.setText(info.getDisplayTitle());
			tvLabel.setTextColor(0xFFFFFFFF);
			NokiaFontManager.textSize(tvLabel, 13);
			tvLabel.setSingleLine(true);
			tvLabel.setEllipsize(TextUtils.TruncateAt.END);
			row.addView(tvLabel);

			final int idx = i;
			row.setOnClickListener(v -> {
				setFocusIndex(idx);
				selectTile(info);
			});

			tileListContainer.addView(row);
			itemViews[i] = row;
		}

		setFocusIndex(0);
		if (getActivity() instanceof NokiaDesktopActivity) {
			((NokiaDesktopActivity) getActivity()).refreshPageBar();
		}
	}

	/** 选中并添加为桌面快捷开关小组件。 */
	private void selectTile(TileInfo info) {
		if (info == null) return;
		NokiaWidgetStorage storage = new NokiaWidgetStorage(requireContext());
		NokiaWidgetItem item = new NokiaWidgetItem(
				NokiaWidgetItem.TYPE_QS_TILE,
				info.getDisplayTitle(),
				info.getComponentValue(),
				null
		);
		storage.addWidget(item);
		Toast.makeText(requireContext(), "已添加组件: " + info.tileLabel, Toast.LENGTH_SHORT).show();
		NokiaLog.i("WidgetTilePicker", "添加快捷开关组件: " + info.getDisplayTitle() + " target=" + info.getComponentValue());

		if (getActivity() instanceof NokiaDesktopActivity) {
			((NokiaDesktopActivity) getActivity()).exitCurrent();
		}
	}
}
