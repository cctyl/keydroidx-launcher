package javax.microedition.shell;

import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

/**
 * 诺基亚 J2ME 挂机后台智能节能引擎 (Doze-Cycle Eco Engine)
 *
 * 设计原理：
 * 仿照 Android Doze（打盹）周期轮询模型：
 * 1. 深度休眠窗口 (Deep Eco Sleep): 大部分时间（如 800ms）主循环休眠，抑制 CPU 空转与发热；
 * 2. 维护/轮询活动窗口 (Doze Maintenance Window): 周期性开启短时间（如 200ms）高速运行窗口，
 *    让 MIDlet 集中处理网络 IO、定时器、消息收发与业务逻辑；
 * 3. 声音智能放行 (Smart Sound Allowance): 消息提示音、Tone、Notification 音频完全允许发声。
 */
public class NokiaBgEcoEngine {

	private static final String TAG = "NokiaBgEcoEngine";

	/** 是否处于后台挂机保活模式 */
	private static volatile boolean sBackgroundMode = false;

	/** Doze 周期总时长：10000ms (10秒一个大周期) */
	private static final long DOZE_CYCLE_MS = 10000L;

	/** Doze 维护/活动窗口时长：1000ms (1秒集中全速处理网络与消息收发) */
	private static final long DOZE_MAINTENANCE_WINDOW_MS = 1000L;

	/** 深度休眠期的单次节流休眠时长 (ms) (设为 2000ms 深度打盹休眠，彻底消除后台空转) */
	private static final long DEEP_SLEEP_THROTTLE_MS = 2000L;

	/** 音频播放活跃锁：当正在播放提示音/音效时，临时禁用节流，保证音频流畅不卡顿 */
	private static volatile int sActiveAudioPlayingCount = 0;

	public static void onBackgroundStarted() {
		sBackgroundMode = true;
		try {
			Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
		} catch (Throwable t) {
			Log.w(TAG, "降级线程优先级失败", t);
		}
		Log.i(TAG, "J2ME 进入后台 Doze 节能模式 (周期轮询收发消息 + 音频放行)");
	}

	public static void onForegroundResumed() {
		sBackgroundMode = false;
		try {
			Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
		} catch (Throwable t) {
			Log.w(TAG, "恢复线程优先级失败", t);
		}
		Log.i(TAG, "J2ME 恢复前台全速模式");
	}

	public static boolean isBackgroundMode() {
		return sBackgroundMode;
	}

	/** 当开始播放音频时调用（保证提示音、QQ消息音、音效完整响亮不被节流卡顿） */
	public static void onAudioStarted() {
		sActiveAudioPlayingCount++;
	}

	/** 音频播放结束或停止时调用 */
	public static void onAudioStopped() {
		if (sActiveAudioPlayingCount > 0) {
			sActiveAudioPlayingCount--;
		}
	}

	/**
	 * 在 Canvas 重绘/事件派发时调用的智能 Doze 节流方法
	 */
	public static void throttleIfNeeded() {
		if (!sBackgroundMode) {
			return;
		}

		// 如果当前正在播放提示音，临时全速放行，确保声音连贯清脆
		if (sActiveAudioPlayingCount > 0) {
			return;
		}

		long now = SystemClock.uptimeMillis();
		long phase = now % DOZE_CYCLE_MS;

		// 处于 200ms 的 Doze 维护/消息轮询窗口内：全速执行，不休眠
		if (phase < DOZE_MAINTENANCE_WINDOW_MS) {
			return;
		}

		// 处于深度节能打盹窗口内：休眠降低 CPU 负载
		try {
			Thread.sleep(DEEP_SLEEP_THROTTLE_MS);
		} catch (InterruptedException ignored) {
		}
	}
}
