package ru.playsoftware.j2meloader.nokia;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.RequiresApi;
import io.github.cctyl.nokia.common.ui.KeydroidxBatteryDrawable;
import ru.playsoftware.j2meloader.R;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import java.util.List;

import ru.playsoftware.j2meloader.R;

/**
 * 驱动顶部状态栏的系统信息显示：双卡信号强度、WiFi、蓝牙、飞行模式。
 * 电池与时钟由布局/基类负责，这里不处理。
 * <p>
 * 双卡信号通过 SubscriptionManager 取得活动 SIM 列表，为每个 SIM 创建独立的
 * TelephonyManager 并监听其信号强度变化；单卡/无权限时退化为默认 TelephonyManager。
 * 无卡/未就绪槽位的显隐与空信号格渲染由 updateSignalVisibility 统一计算（详见其注释）。
 * WiFi、蓝牙、飞行模式通过广播 + 轮询实时更新。
 * <p>
 * 部分机型（如 MIUI）在 Activity 启动瞬间订阅尚未就绪，或运行时权限是异步授予的，
 * 因此额外注册 SubscriptionManager.OnSubscriptionsChangedListener，在订阅可用后
 * 自动重新注册信号监听，避免 SIM2 永远读不到信号。
 */
public class StatusBarController {
	private static final String TAG = "KeydroidxSB";
	private static final int REQ_PHONE_STATE = 1001;

	private final KeydroidxBaseActivity activity;
	private ImageView ivSignal1, ivSignal2, ivWifi, ivBluetooth, ivAirplane, ivBattery;
	private KeydroidxBatteryDrawable batteryDrawable;
	private LinearLayout sim1Container, sim2Container;
	private TextView tvCarrier1, tvCarrier2;
	private View simCarrierContainer;
	private TelephonyManager telephonyManager;
	private SubscriptionManager subscriptionManager;
	private WifiManager wifiManager;

	// 省电模式：监听 Settings.Global.LOW_POWER_MODE 变化，开启后电量格变黄。
	private final ContentObserver powerSaveObserver = new ContentObserver(new Handler()) {
		@Override
		public void onChange(boolean selfChange) {
			updatePowerSaveMode();
		}
	};

	private final SignalListener listener1 = new SignalListener(0);
	private final SignalListener listener2 = new SignalListener(1);

	// 订阅就绪/变化后自动重新注册信号监听。
	private SubscriptionManager.OnSubscriptionsChangedListener subListener;

	// SubscriptionManager 被厂商屏蔽、隐藏 API 被黑名单拒绝时，改用 createForSubscriptionId
	// 探测常见 subId 后监听信号；probeTm1/2 记录各 listener 所挂载的 TM 以便注销。
	private final Handler pollHandler = new Handler(Looper.getMainLooper());
	private Runnable cellInfoPoller;
	private static final long CELL_INFO_POLL_MS = 3000;
	private TelephonyManager probeTm1, probeTm2;

	// 飞行模式：部分机型不会派发 ACTION_AIRPLANE_MODE_CHANGED 广播，
	// 故同时用 ContentObserver 监听 Settings.Global.AIRPLANE_MODE_ON 作为兜底。
	private final ContentObserver airplaneObserver = new ContentObserver(new Handler()) {
		@Override
		public void onChange(boolean selfChange) {
			updateAirplane();
			updateWifi();
			updateCarriers();
			refreshSignals();
		}
	};

	private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
				updateBluetooth();
			} else if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
				updateAirplane();
				updateWifi();
				updateCarriers();
				refreshSignals();
			} else if (WifiManager.RSSI_CHANGED_ACTION.equals(action)
					|| WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)
					|| WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
				updateWifi();
			} else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
				updateBatteryFromIntent(intent);
			}
		}
	};

	public StatusBarController(KeydroidxBaseActivity activity) {
		this.activity = activity;
	}

	/** 绑定视图并注册监听。需在 Activity 的 onResume 中调用。 */
	@SuppressLint("MissingPermission")
	public void start() {
		ivSignal1 = activity.findViewById(R.id.ivSignal1);
		ivSignal2 = activity.findViewById(R.id.ivSignal2);
		ivWifi = activity.findViewById(R.id.ivWifi);
		ivBluetooth = activity.findViewById(R.id.ivBluetooth);
		ivAirplane = activity.findViewById(R.id.ivAirplane);
		ivBattery = activity.findViewById(R.id.ivBattery);
		sim1Container = activity.findViewById(R.id.sim1Container);
		sim2Container = activity.findViewById(R.id.sim2Container);
		tvCarrier1 = activity.findViewById(R.id.tvCarrier1);
		tvCarrier2 = activity.findViewById(R.id.tvCarrier2);
		simCarrierContainer = activity.findViewById(R.id.simCarrierContainer);

		telephonyManager = (TelephonyManager) activity.getSystemService(Context.TELEPHONY_SERVICE);
		// SubscriptionManager 仅在 API 22+ 存在（双卡订阅），API 19 无此类，
		// 直接引用会 NoClassDefFoundError；低版本保持 null，后续使用点均已守卫并降级单卡。
		if (Build.VERSION.SDK_INT >= 22) {
			subscriptionManager = (SubscriptionManager) activity.getSystemService(
					Context.TELEPHONY_SUBSCRIPTION_SERVICE);
		}
		wifiManager = (WifiManager) activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);

		// 双卡信号需要 READ_PHONE_STATE，缺失则请求（缺失时退化为单卡监听）。
		if (Build.VERSION.SDK_INT >= 23
				&& activity.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
				!= PackageManager.PERMISSION_GRANTED) {
			activity.requestPermissions(
					new String[]{android.Manifest.permission.READ_PHONE_STATE}, REQ_PHONE_STATE);
		}

		registerSignalListeners();
		updateAirplane();
		updateWifi();
		updateBluetooth();
		updateCarriers();
		updateBattery();
		updatePowerSaveMode();

		IntentFilter filter = new IntentFilter();
		filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
		filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
		filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
		filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
		filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
		filter.addAction(Intent.ACTION_BATTERY_CHANGED);
		activity.registerReceiver(stateReceiver, filter);

		// 兜底：监听飞行模式设置变化。
		ContentResolver cr = activity.getContentResolver();
		cr.registerContentObserver(
				Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), false, airplaneObserver);
		// 兜底/实时同步省电模式开关（低电量自动触发或手动开启）。
		cr.registerContentObserver(
				Settings.Global.getUriFor("low_power"), false, powerSaveObserver);

		// 订阅就绪后自动重注册信号监听（应对 MIUI 启动瞬间订阅未就绪的情况）。
		if (Build.VERSION.SDK_INT >= 22 && subscriptionManager != null) {
			subListener = new SubscriptionManager.OnSubscriptionsChangedListener() {
				@Override
				public void onSubscriptionsChanged() {
					KeydroidxLog.d(TAG, "onSubscriptionsChanged -> reregister");
					registerSignalListeners();
					updateCarriers();
				}
			};
			subscriptionManager.addOnSubscriptionsChangedListener(subListener);
		}
	}

	/** 运行时权限授予后调用，重新注册信号监听。 */
	@SuppressLint("MissingPermission")
	public void onPermissionGranted() {
		KeydroidxLog.d(TAG, "onPermissionGranted -> reregister");
		registerSignalListeners();
		updateCarriers();
	}

	/**
	 * 运营商视图随桌面碎片重建后重新绑定并立即刷新。
	 * <p>
	 * 运营商视图（simCarrierContainer/tvCarrier1/tvCarrier2）自 2026-10 起从共用顶栏
	 * 迁入桌面大时钟区（fragment_keydroidx_desktop.xml），而桌面页切换用 replace——
	 * 返回桌面时碎片视图整体重建，但 Activity 的 onResume/start() 不会重跑，
	 * 成员里持有的旧视图引用已 detach，直接刷会写进旧视图。由桌面碎片的
	 * {@code onPageCreated} 调用本方法完成重绑 + 立即刷新。
	 */
	public void refreshCarrierViews() {
		tvCarrier1 = activity.findViewById(R.id.tvCarrier1);
		tvCarrier2 = activity.findViewById(R.id.tvCarrier2);
		simCarrierContainer = activity.findViewById(R.id.simCarrierContainer);
		updateCarriers();
	}

	/** 取消监听与广播。需在 Activity 的 onPause 中调用。 */
	public void stop() {
		stopCellInfoPolling();
		unregisterSignalListeners();
		try {
			activity.unregisterReceiver(stateReceiver);
		} catch (Exception ignore) {
			KeydroidxLog.w(TAG, "unregisterReceiver failed: " + ignore.getMessage());
			// 未注册或已注销，忽略
		}
		try {
			activity.getContentResolver().unregisterContentObserver(airplaneObserver);
		} catch (Exception ignore) {
			KeydroidxLog.w(TAG, "getContentResolver failed: " + ignore.getMessage());
			// 忽略
		}
		try {
			activity.getContentResolver().unregisterContentObserver(powerSaveObserver);
		} catch (Exception ignore) {
			KeydroidxLog.w(TAG, "getContentResolver failed: " + ignore.getMessage());
			// 忽略
		}
		// removeOnSubscriptionsChangedListener 需 API 22，必须同时校验版本。
		if (subListener != null && subscriptionManager != null && Build.VERSION.SDK_INT >= 22) {
			try {
				subscriptionManager.removeOnSubscriptionsChangedListener(subListener);
			} catch (Exception ignore) {
				KeydroidxLog.w(TAG, "removeOnSubscriptionsChangedListener failed: " + ignore.getMessage());
				// 忽略
			}
			subListener = null;
		}
	}

	@SuppressLint("MissingPermission")
	private void registerSignalListeners() {
		if (telephonyManager == null) {
			return;
		}
		// 路径可能变化（订阅就绪/权限授予），先停掉可能运行的轮询，避免残留。
		stopCellInfoPolling();
		// 避免重复注册：先全部注销。
		unregisterSignalListeners();

		boolean usedSubscriptionPath = false;
		// createForSubscriptionId(int) 是 API 24 才加入的公开方法，API 22/23（如 Android 5.1
		// 的双卡机型）调用会抛 NoSuchMethodError，必须降级为默认 TelephonyManager。
		boolean canCreateForSubId = Build.VERSION.SDK_INT >= 24;
		if (Build.VERSION.SDK_INT >= 22 && subscriptionManager != null) {
			List<SubscriptionInfo> subs = getActiveSubs();
			KeydroidxLog.d(TAG, "registerSignalListeners subs.size=" + subs.size());
			if (!subs.isEmpty()) {
				usedSubscriptionPath = true;
				for (int i = 0; i < subs.size() && i < 2; i++) {
					SubscriptionInfo info = subs.get(i);
					int subId = info.getSubscriptionId();
					// 按真实槽位索引挂载（「1」=槽 1、「2」=槽 2），避免卡只插槽 2 时
					// 信号画进「1」组；索引异常时退回列表顺序。
					int slotIndex = info.getSimSlotIndex();
					if (slotIndex != 0 && slotIndex != 1) {
						slotIndex = i;
					}
					KeydroidxLog.d(TAG, "  sub i=" + i + " subId=" + subId
							+ " slot=" + slotIndex);
					if (i > 0 && !canCreateForSubId) {
						// API 22/23 无法按订阅分卡监听，只能监听默认卡（卡 1），
						// 该订阅所在槽位之外的另一槽图标保持空。
						getListener(slotIndex).setLevel(0);
						break;
					}
					TelephonyManager tm = canCreateForSubId
							? telephonyManager.createForSubscriptionId(subId)
							: telephonyManager;
					tm.listen(getListener(slotIndex),
							PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
					// 记录每个槽位对应的 TM，供读取运营商名/就绪判断使用。
					if (slotIndex == 0) {
						probeTm1 = tm;
					} else {
						probeTm2 = tm;
					}
				}
				// 仅有 1 张卡时，另一槽位图标保持空。
				if (subs.size() <= 1) {
					int assignedSlot = subs.get(0).getSimSlotIndex();
					if (assignedSlot != 0 && assignedSlot != 1) {
						assignedSlot = 0;
					}
					(assignedSlot == 0 ? listener2 : listener1).setLevel(0);
				}
			}
		}
		// SubscriptionManager 路径拿不到活动 SIM 时（MIUI 等屏蔽第三方读取）：
		// 改用 createForSubscriptionId 探测常见 subId，为每张就绪的卡监听信号。
		if (!usedSubscriptionPath) {
			KeydroidxLog.d(TAG, "registerSignalListeners fallback: probe subIds (both SIMs)");
			probeSubIdsAndListen();
			startSignalPolling();
		}
		// 先按槽位就绪状态同步信号组显隐与空格渲染，再应用缓存等级。
		updateSignalVisibility();
		refreshSignals();
	}

	private void unregisterSignalListeners() {
		if (telephonyManager == null) {
			return;
		}
		// createForSubscriptionId 需 API 24；API 22/23 只监听过默认 TM（卡 1），
		// 由下方统一的 telephonyManager.listen(LISTEN_NONE) 注销即可。
		if (Build.VERSION.SDK_INT >= 24 && subscriptionManager != null) {
			List<SubscriptionInfo> subs = getActiveSubs();
			for (int i = 0; i < subs.size() && i < 2; i++) {
				int subId = subs.get(i).getSubscriptionId();
				// 与注册路径一致的按槽位映射（索引异常时退回列表顺序）。
				int slotIndex = subs.get(i).getSimSlotIndex();
				if (slotIndex != 0 && slotIndex != 1) {
					slotIndex = i;
				}
				TelephonyManager tm = telephonyManager.createForSubscriptionId(subId);
				tm.listen(getListener(slotIndex), PhoneStateListener.LISTEN_NONE);
			}
		}
		// 始终取消默认监听（以防 fallback 路径注册了它）。
		telephonyManager.listen(listener1, PhoneStateListener.LISTEN_NONE);
		// 取消探测路径挂载在 createForSubscriptionId TM 上的监听。
		if (probeTm1 != null) {
			probeTm1.listen(listener1, PhoneStateListener.LISTEN_NONE);
		}
		if (probeTm2 != null) {
			probeTm2.listen(listener2, PhoneStateListener.LISTEN_NONE);
		}
	}

	/** 按槽位索引取对应信号监听（槽 1 → listener1，槽 2 → listener2）。 */
	private SignalListener getListener(int slot) {
		return (slot == 0) ? listener1 : listener2;
	}

	/**
	 * 读取活动 SIM 列表。内部使用的 SubscriptionManager/SubscriptionInfo 方法均要求
	 * API 22，故用 @RequiresApi 声明前提；调用方必须在 SDK_INT >= 22 分支内调用。
	 */
	@SuppressLint("MissingPermission")
	@RequiresApi(22)
	private List<SubscriptionInfo> getActiveSubs() {
		List<SubscriptionInfo> result = new java.util.ArrayList<>();
		try {
			List<SubscriptionInfo> subs = subscriptionManager.getActiveSubscriptionInfoList();
			if (subs != null && !subs.isEmpty()) {
				result.addAll(subs);
				KeydroidxLog.d(TAG, "getActiveSubs via list: size=" + result.size());
				return result;
			}
			// MIUI 等机型 getActiveSubscriptionInfoList() 可能返回空，
			// 但按 slot 逐个查询仍可拿到活动 SIM。逐 slot 兜底。
			for (int slot = 0; slot < 2; slot++) {
				SubscriptionInfo info =
						subscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(slot);
				KeydroidxLog.d(TAG, "getActiveSubs slot=" + slot + " info=" + (info != null
						? ("subId=" + info.getSubscriptionId()) : "null"));
				if (info != null) {
					result.add(info);
				}
			}
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "getActiveSubs failed", e);
		}
		return result;
	}

	private void refreshSignals() {
		listener1.apply();
		listener2.apply();
	}

	/**
	 * SubscriptionManager 被厂商屏蔽（返回空）且隐藏 API 被黑名单拒绝时的兜底：
	 * 探测常见 subscriptionId（1/2/0/3），用公开的 getSimState() 找出就绪的卡，
	 * 为每张卡 createForSubscriptionId 后监听信号。全程只使用公开、非隐藏 API。
	 */
	@SuppressLint("MissingPermission")
	private void probeSubIdsAndListen() {
		probeTm1 = null;
		probeTm2 = null;
		if (telephonyManager == null) {
			return;
		}
		if (Build.VERSION.SDK_INT < 24) {
			// createForSubscriptionId 需 API 24，老版本只能读默认卡。
			telephonyManager.listen(listener1, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
			return;
		}
		int[] candidates = {1, 2, 0, 3};
		java.util.ArrayList<Integer> ready = new java.util.ArrayList<>();
		for (int sub : candidates) {
			try {
				TelephonyManager tm = telephonyManager.createForSubscriptionId(sub);
				if (tm.getSimState() == TelephonyManager.SIM_STATE_READY) {
					ready.add(sub);
				}
			} catch (Exception e) {
				KeydroidxLog.w(TAG, "probeSubIds createForSubscriptionId(" + sub + ") failed", e);
			}
			if (ready.size() >= 2) {
				break;
			}
		}
		KeydroidxLog.d(TAG, "probeSubIds ready=" + ready.size());
		if (ready.isEmpty()) {
			telephonyManager.listen(listener1, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
			// 仍将默认 TM 赋给 probeTm1，使运营商名至少能读到第一张卡。
			probeTm1 = telephonyManager;
			pollTmSignal(telephonyManager, listener1);
			return;
		}
		SignalListener[] ls = {listener1, listener2};
		TelephonyManager[] holders = {null, null};
		for (int i = 0; i < ready.size() && i < 2; i++) {
			int sub = ready.get(i);
			TelephonyManager tm = telephonyManager.createForSubscriptionId(sub);
			holders[i] = tm;
			tm.listen(ls[i], PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
			pollTmSignal(tm, ls[i]);
		}
		probeTm1 = holders[0];
		probeTm2 = holders[1];
		if (ready.size() <= 1 && listener2 != null) {
			listener2.setLevel(0);
		}
	}

	/** 初始主动读取一次信号（公开 API，非隐藏）。 */
	@SuppressLint("MissingPermission")
	private void pollTmSignal(TelephonyManager tm, SignalListener l) {
		if (tm == null) {
			return;
		}
		try {
			if (Build.VERSION.SDK_INT >= 29) {
				SignalStrength ss = tm.getSignalStrength();
				if (ss != null) {
					l.setLevel(ss.getLevel());
					KeydroidxLog.d(TAG, "pollTmSignal slot=" + l.slot + " level=" + l.getLevel());
				}
			}
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "pollTmSignal failed", e);
		}
	}

	private void startSignalPolling() {
		if (cellInfoPoller != null) {
			return;
		}
		cellInfoPoller = new Runnable() {
			@Override
			public void run() {
				// 兜底路径设备（厂商屏蔽订阅读取）插拔卡无系统回调，靠轮询同步显隐。
				updateSignalVisibility();
				pollTmSignal(probeTm1, listener1);
				pollTmSignal(probeTm2, listener2);
				pollHandler.postDelayed(this, CELL_INFO_POLL_MS);
			}
		};
		pollHandler.postDelayed(cellInfoPoller, CELL_INFO_POLL_MS);
	}

	private void stopCellInfoPolling() {
		if (cellInfoPoller != null) {
			pollHandler.removeCallbacks(cellInfoPoller);
			cellInfoPoller = null;
		}
	}

	/**
	 * 始终显示运营商行（避免顶部栏出现空白浪费）。
	 *   - 双卡：分别显示两张卡的运营商名
	 *   - 单卡：仅 tvCarrier1 显示运营商名
	 *   - 飞行模式：tvCarrier1 显示「飞行模式」（仅一份，tvCarrier2 置空，
	 *     重复两遍既浪费宽度也让顶栏信息显得冗余）
	 *   - 无 SIM：tvCarrier1 显示「无 SIM」
	 * 优先用网络运营商名，回退到 SIM 卡中的 SPN，都读不到则按状态填占位文字。
	 */
	@SuppressLint("MissingPermission")
	private void updateCarriers() {
		if (simCarrierContainer == null) {
			return;
		}
		simCarrierContainer.setVisibility(View.VISIBLE);

		boolean airplane = isAirplaneModeOn();
		if (airplane) {
			// 飞行模式：仅 tvCarrier1 标一份「飞行模式」，第二槽位置空
			setCarrierOrFallback(tvCarrier1, null, "飞行模式");
			if (tvCarrier2 != null) {
				tvCarrier2.setText("");
			}
			return;
		}

		// 每个卡槽独立判断：该卡槽无 SIM（或未就绪）显示「无SIM」，有卡则显示运营商名。
		// 仅当设备确为双卡时，第二卡槽才参与显示，避免单卡设备出现幽灵「无SIM」。
		boolean dual = getPhoneCount() >= 2;
		KeydroidxLog.d(TAG, "updateCarriers dual=" + dual
				+ " probeTm1=" + (probeTm1 != null) + " probeTm2=" + (probeTm2 != null));
		updateCarrierSlot(tvCarrier1, probeTm1);
		if (dual) {
			updateCarrierSlot(tvCarrier2, probeTm2);
		} else if (tvCarrier2 != null) {
			tvCarrier2.setText("");
		}
	}

	@SuppressLint("MissingPermission")
	private void setCarrierOrFallback(TextView tv, TelephonyManager tm, String fallback) {
		if (tv == null) return;
		String name = null;
		if (tm != null) {
			name = tm.getNetworkOperatorName();
			if (name == null || name.isEmpty()) {
				name = tm.getSimOperatorName();
			}
		}
		tv.setText((name == null || name.isEmpty()) ? fallback : name);
	}

	/** 单个卡槽的运营商显示：无 SIM/未就绪 → 「无SIM」，有卡则优先网络名再回退 SPN。 */
	@SuppressLint("MissingPermission")
	private void updateCarrierSlot(TextView tv, TelephonyManager tm) {
		if (tv == null) return;
		// 未检测到该卡槽的 TM，或 SIM 未就绪（无卡/未识别）→ 显示「无SIM」
		int simState = TelephonyManager.SIM_STATE_UNKNOWN;
		if (tm != null) {
			try {
				simState = tm.getSimState();
			} catch (Exception e) {
				KeydroidxLog.w(TAG, "updateCarrierSlot getSimState failed", e);
			}
		}
		if (tm == null || simState != TelephonyManager.SIM_STATE_READY) {
			KeydroidxLog.d(TAG, "updateCarrierSlot: 无 SIM（tm=" + (tm != null) + " state=" + simState + "）");
			tv.setText("无SIM");
			return;
		}
		String name = tm.getNetworkOperatorName();
		if (name == null || name.isEmpty()) {
			name = tm.getSimOperatorName();
		}
		KeydroidxLog.d(TAG, "updateCarrierSlot: 运营商=" + (name == null ? "" : name));
		tv.setText((name == null || name.isEmpty()) ? "" : name);
	}

	/** 读取设备 SIM 卡槽数量（用于决定是否显示第二卡槽）。 */
	@SuppressLint("MissingPermission")
	private int getPhoneCount() {
		try {
			if (Build.VERSION.SDK_INT >= 23 && telephonyManager != null) {
				return telephonyManager.getPhoneCount();
			}
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "getPhoneCount failed", e);
		}
		return 1;
	}

	// ── 电量 ──

	/** 读取系统电量并更新图标（启动时调用，从 sticky intent 取）。 */
	private void updateBattery() {
		if (ivBattery == null) return;
		Intent sticky = activity.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		if (sticky != null) {
			updateBatteryFromIntent(sticky);
		}
	}

	/** 从 BATTERY_CHANGED 广播中读取电量百分比及充电状态并更新图标。 */
	@SuppressLint("MissingPermission")
	private void updateBatteryFromIntent(Intent intent) {
		if (ivBattery == null || intent == null) return;
		int level = intent.getIntExtra("level", -1);
		int scale = intent.getIntExtra("scale", 100);
		if (level < 0 || scale <= 0) return;
		int pct = level * 100 / scale;

		int status = intent.getIntExtra("status", -1);
		boolean isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
				|| status == android.os.BatteryManager.BATTERY_STATUS_FULL;

		if (batteryDrawable == null) {
			batteryDrawable = new KeydroidxBatteryDrawable(ivBattery.getContext());
			ivBattery.setImageDrawable(batteryDrawable);
		}
		batteryDrawable.setBatteryState(pct, isCharging);
		batteryDrawable.setPowerSaveMode(isPowerSaveModeOn());
	}

	/** 读取系统省电模式状态并同步到电池图标。 */
	private void updatePowerSaveMode() {
		if (batteryDrawable == null) return;
		batteryDrawable.setPowerSaveMode(isPowerSaveModeOn());
	}

	/**
	 * 判断系统是否处于省电模式。
	 * <p>API 21+ 使用 PowerManager.isPowerSaveMode()；低版本（4.4）原生无此能力，
	 * 直接读取 Settings.Global["low_power"] 兜底。
	 */
	private boolean isPowerSaveModeOn() {
		if (Build.VERSION.SDK_INT >= 21) {
			try {
				PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
				if (pm != null && pm.isPowerSaveMode()) {
					return true;
				}
			} catch (Exception e) {
				KeydroidxLog.w(TAG, "isPowerSaveMode failed", e);
			}
		}
		try {
			return Settings.Global.getInt(activity.getContentResolver(), "low_power", 0) == 1;
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "read low_power setting failed failed: " + e.getMessage());
			return false;
		}
	}

	@SuppressLint("MissingPermission")
	private static int batteryLevelToDrawable(int pct) {
		// 4 格电池：每格约 25%，≤10% 显示 1 红格表示告急
		if (pct <= 10)  return R.drawable.ic_battery_0;
		if (pct <= 25)  return R.drawable.ic_battery_25;
		if (pct <= 50)  return R.drawable.ic_battery_50;
		if (pct <= 75)  return R.drawable.ic_battery_75;
		return R.drawable.ic_battery_100;
	}

	@SuppressLint("MissingPermission")
	private void updateWifi() {
		if (ivWifi == null) {
			return;
		}
		// 飞行模式下用户可手动重新开启 WiFi，此时仍应显示图标。
		boolean enabled = isWifiEnabled();
		boolean connected = isWifiConnected(activity);
		if (!enabled && !connected) {
			ivWifi.setVisibility(View.GONE);
			return;
		}
		ivWifi.setVisibility(View.VISIBLE);
		// 根据真实 RSSI 设置 WiFi 信号等级（0-3），而非始终满格。
		int level = getWifiLevel();
		ivWifi.setImageResource(wifiLevelToDrawable(level));
	}

	private int getWifiLevel() {
		if (wifiManager == null) {
			return 3;
		}
		try {
			android.net.wifi.WifiInfo info = wifiManager.getConnectionInfo();
			if (info == null) {
				return 0;
			}
			int rssi = info.getRssi();
			// calculateSignalLevel(rssi, 4) 返回 0..3
			int lvl = WifiManager.calculateSignalLevel(rssi, 4);
			return Math.max(0, Math.min(3, lvl));
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "getWifiLevel failed: " + e.getMessage());
			return 3;
		}
	}

	private void updateBluetooth() {
		if (ivBluetooth == null) {
			return;
		}
		BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
		boolean on = adapter != null && adapter.isEnabled();
		ivBluetooth.setVisibility(on ? View.VISIBLE : View.GONE);
	}

	/**
	 * 判断指定卡槽是否有就绪的 SIM（与 updateCarrierSlot 的就绪口径一致）。
	 * <p>
	 * 优先用该槽位注册信号监听时记录的 TelephonyManager（probeTm1/probeTm2）读
	 * getSimState()；缺失时 API 22+ 用 SubscriptionManager 按 slot 查活动订阅兜底
	 * （覆盖 API 22/23 双卡只能监听默认卡、probeTm2 为 null 的场景）；更老版本
	 * （单卡机）回退默认 TelephonyManager——注意双卡设备上默认 TM 指向默认订阅
	 * 而非固定槽位，不能用于槽位就绪判断。
	 */
	@SuppressLint("MissingPermission")
	private boolean isSlotReady(int slot) {
		TelephonyManager tm = (slot == 0) ? probeTm1 : probeTm2;
		if (tm == null && slot == 0 && subscriptionManager == null) {
			// API 19-21：无 SubscriptionManager，仅单卡机，默认 TM 即唯一卡槽。
			tm = telephonyManager;
		}
		if (tm != null) {
			try {
				if (tm.getSimState() == TelephonyManager.SIM_STATE_READY) {
					return true;
				}
			} catch (Exception e) {
				KeydroidxLog.w(TAG, "isSlotReady getSimState failed", e);
			}
		}
		if (Build.VERSION.SDK_INT >= 22 && subscriptionManager != null) {
			try {
				SubscriptionInfo info =
						subscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(slot);
				return info != null;
			} catch (Exception e) {
				KeydroidxLog.w(TAG, "isSlotReady query subscription failed", e);
			}
		}
		return false;
	}

	/**
	 * 统一计算信号组（「1」「2」标号 + 信号格）显隐与空格渲染：
	 * <ul>
	 * <li>飞行模式：两组均隐藏（迁移自 updateAirplane 的原逻辑，行为不变）；</li>
	 * <li>任一槽位有就绪 SIM：仅显示有卡槽位的组，空槽整组隐藏（单卡插一张卡的场景）；</li>
	 * <li>所有槽位均无卡：两组均显示（保留「1」「2」标号），信号格强制渲染空图标
	 * ic_signal_0——部分机型无卡仍上报信号强度，必须由就绪门控拦截。</li>
	 * </ul>
	 * 由订阅变化、权限授予、飞行模式变化、3 秒轮询等现有钩子驱动，支持运行时插拔卡。
	 */
	private void updateSignalVisibility() {
		if (sim1Container == null && sim2Container == null) {
			return;
		}
		if (isAirplaneModeOn()) {
			setSignalGroupVisibility(sim1Container, false);
			setSignalGroupVisibility(sim2Container, false);
			listener1.setReady(false);
			listener2.setReady(false);
			return;
		}
		boolean ready1 = isSlotReady(0);
		boolean ready2 = isSlotReady(1);
		boolean hasReady = ready1 || ready2;
		KeydroidxLog.d(TAG, "updateSignalVisibility ready1=" + ready1
				+ " ready2=" + ready2);
		setSignalGroupVisibility(sim1Container, !hasReady || ready1);
		setSignalGroupVisibility(sim2Container, !hasReady || ready2);
		listener1.setReady(ready1);
		listener2.setReady(ready2);
	}

	/** 仅在可见性变化时写视图，避免轮询时机触发无效 requestLayout。 */
	private static void setSignalGroupVisibility(View v, boolean visible) {
		if (v == null) {
			return;
		}
		int target = visible ? View.VISIBLE : View.GONE;
		if (v.getVisibility() != target) {
			v.setVisibility(target);
		}
	}

	private void updateAirplane() {
		boolean airplane = isAirplaneModeOn();
		if (ivAirplane != null) {
			ivAirplane.setVisibility(airplane ? View.VISIBLE : View.GONE);
		}
		// 信号组显隐（含飞行模式分支）统一交给 updateSignalVisibility 计算。
		updateSignalVisibility();
	}

	@SuppressLint("MissingPermission")
	private boolean isWifiEnabled() {
		try {
			WifiManager wm = (WifiManager)
					activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
			return wm != null && wm.isWifiEnabled();
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "getApplicationContext failed: " + e.getMessage());
			return false;
		}
	}

	@SuppressLint("MissingPermission")
	private boolean isWifiConnected(Context c) {
		android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
				c.getSystemService(Context.CONNECTIVITY_SERVICE);
		if (cm == null) {
			return false;
		}
		if (Build.VERSION.SDK_INT >= 23) {
			android.net.Network network = cm.getActiveNetwork();
			if (network == null) {
				return false;
			}
			android.net.NetworkCapabilities cap = cm.getNetworkCapabilities(network);
			return cap != null && cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
		} else {
			android.net.NetworkInfo ni = cm.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI);
			return ni != null && ni.isConnected();
		}
	}

	private boolean isAirplaneModeOn() {
		try {
			return Settings.Global.getInt(activity.getContentResolver(),
					Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "isAirplaneModeOn failed: " + e.getMessage());
			return false;
		}
	}

	private static int asuToLevel(int asu) {
		if (asu <= 0 || asu == 99) {
			return 0;
		}
		if (asu < 8) {
			return 1;
		}
		if (asu < 16) {
			return 2;
		}
		if (asu < 24) {
			return 3;
		}
		return 4;
	}

	@DrawableRes
	private static int signalLevelToDrawable(int level) {
		switch (level) {
			case 1:
				return R.drawable.ic_signal_1;
			case 2:
				return R.drawable.ic_signal_2;
			case 3:
				return R.drawable.ic_signal_3;
			case 4:
				return R.drawable.ic_signal_4;
			default:
				return R.drawable.ic_signal_0;
		}
	}

	@DrawableRes
	private static int wifiLevelToDrawable(int level) {
		switch (level) {
			case 1:
				return R.drawable.ic_wifi_1;
			case 2:
				return R.drawable.ic_wifi_2;
			case 3:
				return R.drawable.ic_wifi_3;
			default:
				return R.drawable.ic_wifi_0;
		}
	}

	/** 每张 SIM 一个监听，按 slot 索引更新对应 ImageView。 */
	private class SignalListener extends PhoneStateListener {
		private final int slot;
		private int level = 0;
		// 槽位是否有就绪 SIM：未就绪时强制渲染空信号格（无卡机型仍会上报信号强度）。
		private boolean ready = false;
		// 上次渲染的等级：相同等级跳过 setImageResource，避免轮询时机触发无效重绘。
		private int appliedLevel = 0;

		SignalListener(int slot) {
			this.slot = slot;
		}

		int getLevel() {
			return level;
		}

		void setLevel(int level) {
			this.level = ready ? level : 0;
			apply();
		}

		void setReady(boolean ready) {
			if (this.ready == ready) {
				return;
			}
			this.ready = ready;
			if (!ready) {
				level = 0;
			}
			// 强制下一帧刷新，避免残留旧等级图标。
			appliedLevel = -1;
			apply();
		}

		@Override
		public void onSignalStrengthsChanged(SignalStrength signalStrength) {
			super.onSignalStrengthsChanged(signalStrength);
			if (ready && signalStrength != null) {
				if (Build.VERSION.SDK_INT >= 29) {
					level = signalStrength.getLevel();
				} else {
					level = asuToLevel(signalStrength.getGsmSignalStrength());
				}
			}
			KeydroidxLog.d(TAG, "onSignalStrengthsChanged slot=" + slot + " level=" + level);
			apply();
		}

		void apply() {
			ImageView iv = (slot == 0) ? ivSignal1 : ivSignal2;
			if (iv == null) {
				return;
			}
			// 未就绪槽位强制空信号格（ic_signal_0），忽略系统上报的 level。
			int shown = ready ? level : 0;
			if (shown == appliedLevel) {
				return;
			}
			appliedLevel = shown;
			iv.setImageResource(signalLevelToDrawable(shown));
		}
	}
}
