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
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import io.github.cctyl.nokia.common.ui.NokiaBatteryDrawable;
import ru.playsoftware.j2meloader.R;

import java.util.List;

import ru.playsoftware.j2meloader.R;

/**
 * 驱动顶部状态栏的系统信息显示：双卡信号强度、WiFi、蓝牙、飞行模式。
 * 电池与时钟由布局/基类负责，这里不处理。
 * <p>
 * 双卡信号通过 SubscriptionManager 取得活动 SIM 列表，为每个 SIM 创建独立的
 * TelephonyManager 并监听其信号强度变化；单卡/无权限时退化为默认 TelephonyManager。
 * WiFi、蓝牙、飞行模式通过广播 + 轮询实时更新。
 * <p>
 * 部分机型（如 MIUI）在 Activity 启动瞬间订阅尚未就绪，或运行时权限是异步授予的，
 * 因此额外注册 SubscriptionManager.OnSubscriptionsChangedListener，在订阅可用后
 * 自动重新注册信号监听，避免 SIM2 永远读不到信号。
 */
public class StatusBarController {
	private static final String TAG = "NokiaSB";
	private static final int REQ_PHONE_STATE = 1001;

	private final NokiaBaseActivity activity;
	private ImageView ivSignal1, ivSignal2, ivWifi, ivBluetooth, ivAirplane, ivBattery;
	private NokiaBatteryDrawable batteryDrawable;
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

	public StatusBarController(NokiaBaseActivity activity) {
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
					Log.d(TAG, "onSubscriptionsChanged -> reregister");
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
		Log.d(TAG, "onPermissionGranted -> reregister");
		registerSignalListeners();
		updateCarriers();
	}

	/** 取消监听与广播。需在 Activity 的 onPause 中调用。 */
	public void stop() {
		stopCellInfoPolling();
		unregisterSignalListeners();
		try {
			activity.unregisterReceiver(stateReceiver);
		} catch (Exception ignore) {
			// 未注册或已注销，忽略
		}
		try {
			activity.getContentResolver().unregisterContentObserver(airplaneObserver);
		} catch (Exception ignore) {
			// 忽略
		}
		try {
			activity.getContentResolver().unregisterContentObserver(powerSaveObserver);
		} catch (Exception ignore) {
			// 忽略
		}
		if (subListener != null && subscriptionManager != null) {
			try {
				subscriptionManager.removeOnSubscriptionsChangedListener(subListener);
			} catch (Exception ignore) {
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
		if (Build.VERSION.SDK_INT >= 22 && subscriptionManager != null) {
			List<SubscriptionInfo> subs = getActiveSubs();
			Log.d(TAG, "registerSignalListeners subs.size=" + subs.size());
			if (!subs.isEmpty()) {
				usedSubscriptionPath = true;
				for (int i = 0; i < subs.size() && i < 2; i++) {
					int subId = subs.get(i).getSubscriptionId();
					Log.d(TAG, "  sub i=" + i + " subId=" + subId
							+ " slot=" + subs.get(i).getSimSlotIndex());
					TelephonyManager tm = telephonyManager.createForSubscriptionId(subId);
					PhoneStateListener l = (i == 0) ? listener1 : listener2;
					tm.listen(l, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
					// 记录每张卡对应的 TM，供读取运营商名使用。
					if (i == 0) {
						probeTm1 = tm;
					} else {
						probeTm2 = tm;
					}
				}
				// 仅有 1 张卡时，第二张卡图标保持空。
				if (subs.size() <= 1) {
					listener2.setLevel(0);
				}
			}
		}
		// SubscriptionManager 路径拿不到活动 SIM 时（MIUI 等屏蔽第三方读取）：
		// 改用 createForSubscriptionId 探测常见 subId，为每张就绪的卡监听信号。
		if (!usedSubscriptionPath) {
			Log.d(TAG, "registerSignalListeners fallback: probe subIds (both SIMs)");
			probeSubIdsAndListen();
			startSignalPolling();
		}
		refreshSignals();
	}

	private void unregisterSignalListeners() {
		if (telephonyManager == null) {
			return;
		}
		if (Build.VERSION.SDK_INT >= 22 && subscriptionManager != null) {
			List<SubscriptionInfo> subs = getActiveSubs();
			for (int i = 0; i < subs.size() && i < 2; i++) {
				int subId = subs.get(i).getSubscriptionId();
				TelephonyManager tm = telephonyManager.createForSubscriptionId(subId);
				PhoneStateListener l = (i == 0) ? listener1 : listener2;
				tm.listen(l, PhoneStateListener.LISTEN_NONE);
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

	@SuppressLint("MissingPermission")
	private List<SubscriptionInfo> getActiveSubs() {
		List<SubscriptionInfo> result = new java.util.ArrayList<>();
		try {
			List<SubscriptionInfo> subs = subscriptionManager.getActiveSubscriptionInfoList();
			if (subs != null && !subs.isEmpty()) {
				result.addAll(subs);
				Log.d(TAG, "getActiveSubs via list: size=" + result.size());
				return result;
			}
			// MIUI 等机型 getActiveSubscriptionInfoList() 可能返回空，
			// 但按 slot 逐个查询仍可拿到活动 SIM。逐 slot 兜底。
			for (int slot = 0; slot < 2; slot++) {
				SubscriptionInfo info =
						subscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(slot);
				Log.d(TAG, "getActiveSubs slot=" + slot + " info=" + (info != null
						? ("subId=" + info.getSubscriptionId()) : "null"));
				if (info != null) {
					result.add(info);
				}
			}
		} catch (Exception e) {
			Log.e(TAG, "getActiveSubs failed", e);
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
				Log.e(TAG, "probeSubIds createForSubscriptionId(" + sub + ") failed", e);
			}
			if (ready.size() >= 2) {
				break;
			}
		}
		Log.d(TAG, "probeSubIds ready=" + ready.size());
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
					Log.d(TAG, "pollTmSignal slot=" + l.slot + " level=" + l.getLevel());
				}
			}
		} catch (Exception e) {
			Log.e(TAG, "pollTmSignal failed", e);
		}
	}

	private void startSignalPolling() {
		if (cellInfoPoller != null) {
			return;
		}
		cellInfoPoller = new Runnable() {
			@Override
			public void run() {
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
	 *   - 飞行模式：tvCarrier1 显示「飞行模式」
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
			// 飞行模式：两卡槽位都标「飞行模式」
			setCarrierOrFallback(tvCarrier1, null, "飞行模式");
			setCarrierOrFallback(tvCarrier2, null, "飞行模式");
			return;
		}

		// 每个卡槽独立判断：该卡槽无 SIM（或未就绪）显示「无SIM」，有卡则显示运营商名。
		// 仅当设备确为双卡时，第二卡槽才参与显示，避免单卡设备出现幽灵「无SIM」。
		boolean dual = getPhoneCount() >= 2;
		Log.d(TAG, "updateCarriers dual=" + dual
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
				Log.w(TAG, "updateCarrierSlot getSimState failed", e);
			}
		}
		if (tm == null || simState != TelephonyManager.SIM_STATE_READY) {
			Log.d(TAG, "updateCarrierSlot: 无 SIM（tm=" + (tm != null) + " state=" + simState + "）");
			tv.setText("无SIM");
			return;
		}
		String name = tm.getNetworkOperatorName();
		if (name == null || name.isEmpty()) {
			name = tm.getSimOperatorName();
		}
		Log.d(TAG, "updateCarrierSlot: 运营商=" + (name == null ? "" : name));
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
			Log.w(TAG, "getPhoneCount failed", e);
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
			batteryDrawable = new NokiaBatteryDrawable(ivBattery.getContext());
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
				Log.w(TAG, "isPowerSaveMode failed", e);
			}
		}
		try {
			return Settings.Global.getInt(activity.getContentResolver(), "low_power", 0) == 1;
		} catch (Exception e) {
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

	private void updateAirplane() {
		boolean airplane = isAirplaneModeOn();
		if (ivAirplane != null) {
			ivAirplane.setVisibility(airplane ? View.VISIBLE : View.GONE);
		}
		// 飞行模式下隐藏信号栏（SIM 图标 + 标号）。
		int sigVis = airplane ? View.GONE : View.VISIBLE;
		if (sim1Container != null) {
			sim1Container.setVisibility(sigVis);
		}
		if (sim2Container != null) {
			sim2Container.setVisibility(sigVis);
		}
	}

	@SuppressLint("MissingPermission")
	private boolean isWifiEnabled() {
		try {
			WifiManager wm = (WifiManager)
					activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
			return wm != null && wm.isWifiEnabled();
		} catch (Exception e) {
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

		SignalListener(int slot) {
			this.slot = slot;
		}

		int getLevel() {
			return level;
		}

		void setLevel(int level) {
			this.level = level;
			apply();
		}

		@Override
		public void onSignalStrengthsChanged(SignalStrength signalStrength) {
			super.onSignalStrengthsChanged(signalStrength);
			if (signalStrength != null) {
				if (Build.VERSION.SDK_INT >= 29) {
					level = signalStrength.getLevel();
				} else {
					level = asuToLevel(signalStrength.getGsmSignalStrength());
				}
			}
			Log.d(TAG, "onSignalStrengthsChanged slot=" + slot + " level=" + level);
			apply();
		}

		void apply() {
			ImageView iv = (slot == 0) ? ivSignal1 : ivSignal2;
			if (iv != null) {
				iv.setImageResource(signalLevelToDrawable(level));
			}
		}
	}
}
