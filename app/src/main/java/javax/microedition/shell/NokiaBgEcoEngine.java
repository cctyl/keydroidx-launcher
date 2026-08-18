package javax.microedition.shell;

import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

/**
 * 诺基亚桌面 J2ME 挂机后台智能节能引擎（NokiaBgEcoEngine）
 * <p>
 * 核心机制：
 * 1. 阶梯退避式 Doze 周期（Ladder Doze Cycle）：
 *    - 刚进入后台 (0 ~ 2 分钟)：5 秒小周期 (1s 维护窗口 + 4s 节流打盹)；
 *    - 中期挂机 (2 ~ 10 分钟)：15 秒中周期 (1.5s 维护窗口 + 13.5s 节流打盹)；
 *    - 长期挂机 (> 10 分钟)：45 秒大周期 (2s 维护窗口 + 43s 节流打盹)；
 * 2. 线程安全性与解耦：
 *    - 节流休眠仅作用于游戏主循环（repaint / serviceRepaints），且全部在锁外执行，绝不阻塞 EventQueue 与网络回调；
 * 3. 声音即时唤醒：
 *    - 播放提示音或消息音时，0 毫秒全速放行，确保声音清脆连贯。
 */
public class NokiaBgEcoEngine {

	private static final String TAG = "NokiaBgEcoEngine";

	/** 是否处于后台挂机模式 */
	private static volatile boolean sBackgroundMode = false;

	/** 进入后台的时间戳 (ms) */
	private static volatile long sBackgroundStartTime = 0L;

	/** 当前活动中的音频播放器数量（>0 表示有声音正在播放） */
	private static volatile int sActiveAudioPlayingCount = 0;

	/** 阶段 1：前 2 分钟 (0 ~ 120,000ms) */
	private static final long STAGE_1_DURATION_MS = 2 * 60 * 1000L;
	private static final long STAGE_1_CYCLE_MS = 5000L;
	private static final long STAGE_1_WINDOW_MS = 1000L;
	private static final long STAGE_1_SLEEP_STEP_MS = 1000L;

	/** 阶段 2：2 ~ 10 分钟 (120,000 ~ 600,000ms) */
	private static final long STAGE_2_DURATION_MS = 10 * 60 * 1000L;
	private static final long STAGE_2_CYCLE_MS = 15000L;
	private static final long STAGE_2_WINDOW_MS = 1500L;
	private static final long STAGE_2_SLEEP_STEP_MS = 2000L;

	/** 阶段 3：10 分钟以上 (长期挂机) */
	private static final long STAGE_3_CYCLE_MS = 45000L;
	private static final long STAGE_3_WINDOW_MS = 2000L;
	private static final long STAGE_3_SLEEP_STEP_MS = 3000L;

	/**
	 * 当 MIDlet 挂机退入后台时调用
	 */
	public static void onBackgroundStarted() {
		sBackgroundMode = true;
		sBackgroundStartTime = SystemClock.uptimeMillis();
		Log.i(TAG, "J2ME 进入后台挂机：启动阶梯退避节能引擎 (5s -> 15s -> 45s)");

		try {
			Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
		} catch (Throwable t) {
			Log.w(TAG, "降低后台主线程优先级失败", t);
		}
	}

	/**
	 * 当 MIDlet 恢复前台时调用
	 */
	public static void onForegroundResumed() {
		sBackgroundMode = false;
		sBackgroundStartTime = 0L;
		Log.i(TAG, "J2ME 恢复前台运行：恢复全速渲染");

		try {
			Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
		} catch (Throwable t) {
			Log.w(TAG, "恢复前台主线程优先级失败", t);
		}
	}

	/**
	 * 音频播放状态通知
	 */
	public static synchronized void onAudioStarted() {
		sActiveAudioPlayingCount++;
	}

	public static synchronized void onAudioStopped() {
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
		long elapsedInBg = now - sBackgroundStartTime;

		long cycleMs;
		long windowMs;
		long sleepStepMs;

		if (elapsedInBg < STAGE_1_DURATION_MS) {
			// 阶段 1：刚进后台 2 分钟内
			cycleMs = STAGE_1_CYCLE_MS;
			windowMs = STAGE_1_WINDOW_MS;
			sleepStepMs = STAGE_1_SLEEP_STEP_MS;
		} else if (elapsedInBg < STAGE_2_DURATION_MS) {
			// 阶段 2：挂机 2 ~ 10 分钟
			cycleMs = STAGE_2_CYCLE_MS;
			windowMs = STAGE_2_WINDOW_MS;
			sleepStepMs = STAGE_2_SLEEP_STEP_MS;
		} else {
			// 阶段 3：长期挂机 > 10 分钟
			cycleMs = STAGE_3_CYCLE_MS;
			windowMs = STAGE_3_WINDOW_MS;
			sleepStepMs = STAGE_3_SLEEP_STEP_MS;
		}

		long phase = now % cycleMs;

		// 处于维护/消息轮询窗口内：全速执行，不休眠
		if (phase < windowMs) {
			return;
		}

		// 处于深度节能打盹窗口内：分步休眠降低 CPU 负载
		try {
			Thread.sleep(sleepStepMs);
		} catch (InterruptedException ignored) {
		}
	}
}
