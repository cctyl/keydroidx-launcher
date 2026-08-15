package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

/**
 * 按键绑定存储类。
 * 管理 9 个动作到 Android KeyCode 的映射，持久化在 SharedPreferences 中。
 * 默认值适合大多数安卓设备（方向键、确认键、音量键模拟左右软键）。
 */
public class NokiaKeyBinding {

	// ---- 动作常量 ----
	public static final int ACTION_UP = 0;
	public static final int ACTION_DOWN = 1;
	public static final int ACTION_LEFT = 2;
	public static final int ACTION_RIGHT = 3;
	public static final int ACTION_SELECT = 4;
	public static final int ACTION_SOFT_LEFT = 5;
	public static final int ACTION_SOFT_RIGHT = 6;
	public static final int ACTION_LOCK_SCREEN = 7;
	/** 挂机菜单键（绿键/拨号键）：仅在 jar 应用内生效，弹出 继续/退出/后台运行 三菜单 */
	public static final int ACTION_HANGUP = 8;

	public static final int ACTION_COUNT = 9;

	private static final String PREFS_NAME = "nokia_key_bindings";

	private static final String[] PREF_KEYS = {
			"up", "down", "left", "right",
			"select", "soft_left", "soft_right", "lock_screen", "hangup"
	};

	// 首次启动按键绑定向导是否已完成（仅首次启动弹出，清数据后重置）
	private static final String PREF_WIZARD_DONE = "key_bind_wizard_done";

	// 默认按键码 — 方向键/确认/返回有通用默认值，左右软键默认未绑定（需用户自行绑定）
	private static final int[] DEFAULT_KEYCODES = {
			KeyEvent.KEYCODE_DPAD_UP,               // up
			KeyEvent.KEYCODE_DPAD_DOWN,             // down
			KeyEvent.KEYCODE_DPAD_LEFT,             // left
			KeyEvent.KEYCODE_DPAD_RIGHT,            // right
			KeyEvent.KEYCODE_DPAD_CENTER,           // select
			KeyEvent.KEYCODE_SOFT_LEFT,             // soft_left
			KeyEvent.KEYCODE_SOFT_RIGHT,            // soft_right
			KeyEvent.KEYCODE_ENDCALL,               // lock_screen（默认挂机键）
			KeyEvent.KEYCODE_CALL,                  // hangup 挂机菜单键（默认绿色拨号键）
	};

	public static String getActionName(int action) {
		switch (action) {
			case ACTION_UP: return "上";
			case ACTION_DOWN: return "下";
			case ACTION_LEFT: return "左";
			case ACTION_RIGHT: return "右";
			case ACTION_SELECT: return "确认";
			case ACTION_SOFT_LEFT: return "左软键";
			case ACTION_SOFT_RIGHT: return "右软键";
			case ACTION_LOCK_SCREEN: return "锁屏";
			case ACTION_HANGUP: return "挂机";
			default: return "未知";
		}
	}

	/**
	 * 首次启动向导中使用的"提示按键名"。与 {@link #getActionName} 的区别在于：
	 * 确认键在向导里提示为"确定"（更贴近用户对 OK 键的称呼）。
	 */
	public static String getWizardPromptName(int action) {
		if (action == ACTION_SELECT) return "确定";
		return getActionName(action);
	}

	/** 首次启动向导是否已完成（已完成则不再弹出）。 */
	public boolean isWizardDone() {
		boolean done = prefs.getBoolean(PREF_WIZARD_DONE, false);
		NokiaLog.i("KeyBinding", "isWizardDone=" + done);
		return done;
	}

	/** 标记首次启动向导已完成（绑定完成或用户跳过）。 */
	public void markWizardDone() {
		prefs.edit().putBoolean(PREF_WIZARD_DONE, true).apply();
		NokiaLog.i("KeyBinding", "首次启动按键绑定向导已标记为完成");
	}

	public static boolean isBound(int keycode) {
		return keycode != KeyEvent.KEYCODE_UNKNOWN;
	}

	private final SharedPreferences prefs;
	private final int[] keycodes = new int[ACTION_COUNT];

	public NokiaKeyBinding(Context context) {
		prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		load();
		NokiaLog.i("KeyBinding", "初始化完成，当前绑定：");
		dumpBindings();
	}

	/** 打印当前所有动作→按键的绑定，便于调试。 */
	private void dumpBindings() {
		for (int i = 0; i < ACTION_COUNT; i++) {
			int kc = keycodes[i];
			String state = NokiaKeyBinding.isBound(kc)
					? NokiaLog.keyName(kc)
					: "未绑定";
			NokiaLog.i("KeyBinding", "  " + getActionName(i) + " -> " + state);
		}
	}

	/** 从 SharedPreferences 加载所有绑定，未绑定的使用默认值。 */
	private void load() {
		for (int i = 0; i < ACTION_COUNT; i++) {
			keycodes[i] = prefs.getInt(PREF_KEYS[i], DEFAULT_KEYCODES[i]);
		}
	}

	/** 强制从 SharedPreferences 重新加载绑定，仅当发生变化时才打印日志，避免刷屏。 */
	public void reload() {
		int[] old = keycodes.clone();
		load();
		boolean changed = false;
		for (int i = 0; i < ACTION_COUNT; i++) {
			if (old[i] != keycodes[i]) {
				changed = true;
				break;
			}
		}
		if (changed) {
			NokiaLog.i("KeyBinding", "绑定已变更，重新加载：");
			dumpBindings();
		}
	}



	/** 保存指定动作的按键码。若该键已被其它动作占用，自动解除原绑定，保证一对一。 */
	public void setKeyCode(int action, int keycode) {
		if (action < 0 || action >= ACTION_COUNT) {
			NokiaLog.w("KeyBinding", "setKeyCode 忽略非法 action=" + action);
			return;
		}
		// 清除其它动作中已占用该 keycode 的绑定，避免一个键对应多个动作
		if (keycode != KeyEvent.KEYCODE_UNKNOWN) {
			for (int i = 0; i < ACTION_COUNT; i++) {
				if (i != action && keycodes[i] == keycode) {
					keycodes[i] = KeyEvent.KEYCODE_UNKNOWN;
					prefs.edit().putInt(PREF_KEYS[i], KeyEvent.KEYCODE_UNKNOWN).apply();
					NokiaLog.i("KeyBinding", "清除冲突绑定 "
							+ getActionName(i) + "（原占用 " + NokiaLog.keyName(keycode) + "）");
				}
			}
		}
		int old = keycodes[action];
		keycodes[action] = keycode;
		prefs.edit().putInt(PREF_KEYS[action], keycode).apply();
		NokiaLog.i("KeyBinding",
				"setKeyCode " + getActionName(action)
						+ " : " + NokiaLog.keyName(old) + " -> " + NokiaLog.keyName(keycode));
	}

	/** 获取指定动作的按键码。 */
	public int getKeyCode(int action) {
		if (action < 0 || action >= ACTION_COUNT) return KeyEvent.KEYCODE_UNKNOWN;
		return keycodes[action];
	}

	/** 序列化当前全部绑定为 int[]（跨进程经 Intent extra 传给 :midlet 进程使用）。 */
	public int[] toKeyCodeArray() {
		return keycodes.clone();
	}

	/**
	 * 直接从 SharedPreferences 读取全部绑定（不走实例缓存）。
	 * 供 :midlet 进程兜底使用：新进程首次加载 SP 必然读到最新文件值，无跨进程缓存陈旧问题。
	 */
	public static int[] loadKeyCodes(Context context) {
		SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		int[] out = new int[ACTION_COUNT];
		for (int i = 0; i < ACTION_COUNT; i++) {
			out[i] = prefs.getInt(PREF_KEYS[i], DEFAULT_KEYCODES[i]);
		}
		return out;
	}

	/** 根据 KeyCode 反查动作，找不到返回 -1。 */
	public int getActionForKeyCode(int keycode) {
		for (int i = 0; i < ACTION_COUNT; i++) {
			if (keycodes[i] == keycode) return i;
		}
		return -1;
	}

	/**
	 * 判断一个 KeyEvent 是否被绑定到某个动作并返回该动作。
	 * 返回 -1 表示该按键未绑定。
	 */
	public int resolveAction(KeyEvent event) {
		if (event.getAction() != KeyEvent.ACTION_DOWN) {
			NokiaLog.d("KeyBinding", "resolveAction 忽略非按下事件 action="
					+ event.getAction() + " keyCode=" + NokiaLog.keyName(event.getKeyCode()));
			return -1;
		}
		int keyCode = event.getKeyCode();
		int action = getActionForKeyCode(keyCode);
		if (action >= 0) {
			NokiaLog.d("KeyBinding", "resolveAction " + NokiaLog.keyName(keyCode)
					+ " -> " + getActionName(action) + "(" + action + ")");
			return action;
		}

		// 通用确认键兜底：未显式绑定，但属于"确认/OK"按键族时，视为确认动作。
		// 这样即使清除数据后默认绑定里没有该键（如设备 OK 键发的是 ENTER），
		// 确认键也能直接生效，避免事件穿透到列表第一行（已显式绑定的仍优先）。
		if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
				|| keyCode == KeyEvent.KEYCODE_ENTER
				|| keyCode == KeyEvent.KEYCODE_SPACE
				|| keyCode == KeyEvent.KEYCODE_BUTTON_A) {
			NokiaLog.i("KeyBinding", "resolveAction 通用确认键兜底 "
					+ NokiaLog.keyName(keyCode) + " -> 确认(" + ACTION_SELECT + ")");
			return ACTION_SELECT;
		}

		// 菜单键兜底为左软键（部分设备没有独立软键，用菜单键代替"选择"）
		if (keyCode == KeyEvent.KEYCODE_MENU) {
			NokiaLog.i("KeyBinding", "resolveAction 菜单键兜底 "
					+ NokiaLog.keyName(keyCode) + " -> 左软键(" + ACTION_SOFT_LEFT + ")");
			return ACTION_SOFT_LEFT;
		}

		NokiaLog.d("KeyBinding", "resolveAction " + NokiaLog.keyName(keyCode)
				+ " -> 未绑定(-1)");
		return -1;
	}

	/**
	 * 静态版按键解析（无实例，按传入键码表查表 + 同款兜底）。
	 * 供无法取得 NokiaKeyBinding 实例的场景使用：如 :midlet 进程内的弹窗
	 * （NokiaOptionsDialog 注入键码表模式，MicroActivity 宿主不是 NokiaDesktopActivity）。
	 * 语义与实例版 {@link #resolveAction(KeyEvent)} 完全一致（不含日志）。
	 */
	public static int resolveAction(int[] keycodes, KeyEvent event) {
		if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) {
			return -1;
		}
		int keyCode = event.getKeyCode();
		if (keycodes != null) {
			for (int i = 0; i < keycodes.length && i < ACTION_COUNT; i++) {
				if (keycodes[i] == keyCode) return i;
			}
		}
		if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
				|| keyCode == KeyEvent.KEYCODE_ENTER
				|| keyCode == KeyEvent.KEYCODE_SPACE
				|| keyCode == KeyEvent.KEYCODE_BUTTON_A) {
			return ACTION_SELECT;
		}
		if (keyCode == KeyEvent.KEYCODE_MENU) {
			return ACTION_SOFT_LEFT;
		}
		return -1;
	}

	/**
	 * 供诺基亚桌面各弹窗（Dialog / DialogFragment）复用：把一次按键事件按当前绑定解析，
	 * 并分发到左/右软键动作，避免各弹窗写死 keyCode。
	 *
	 * @param event            按键事件
	 * @param leftAction       左软键动作（可为 null）
	 * @param rightAction      右软键动作（可为 null）
	 * @param backAction       返回键动作（可为 null；传 null 表示不拦截 BACK，交给系统处理）
	 * @param consumeUnmapped  是否消费方向键/确认键/未绑定键。
	 *                         信息类弹窗（无输入框/列表）传 true；
	 *                         含 EditText / 列表的表单弹窗传 false，避免破坏文本输入与导航。
	 * @return 是否消费了该事件
	 */
	public boolean dispatchDialogKey(KeyEvent event, Runnable leftAction,
			Runnable rightAction, Runnable backAction, boolean consumeUnmapped) {
		if (event.getAction() != KeyEvent.ACTION_DOWN) {
			return true; // 消费抬起事件，避免重复触发
		}
		if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
			if (backAction != null) {
				backAction.run();
				return true;
			}
			return false; // 不拦截 BACK，交给系统（如可取消弹窗的默认关闭）
		}
		int action = resolveAction(event);
		switch (action) {
			case ACTION_SOFT_LEFT:
				if (leftAction != null) leftAction.run();
				return true;
			case ACTION_SOFT_RIGHT:
				if (rightAction != null) rightAction.run();
				return true;
			case ACTION_SELECT:
			case ACTION_LEFT:
			case ACTION_RIGHT:
				return consumeUnmapped; // 表单弹窗返回 false，留给 EditText/列表
			default:
				return false;
		}
	}
}
