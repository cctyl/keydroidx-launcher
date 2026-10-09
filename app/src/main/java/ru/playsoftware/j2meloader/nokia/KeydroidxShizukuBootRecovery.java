package ru.playsoftware.j2meloader.nokia;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import ru.playsoftware.mini_shizuku.ServerIdentity;
import ru.playsoftware.mini_shizuku.Shizuku;

/**
 * mini_shizuku 重启 / 失效自动恢复器。
 * <p>
 * 背景：mini_shizuku 服务端是一个独立的 {@code app_process} 进程（非本应用进程、也非系统服务），
 * <strong>只在设备重启后消失</strong>（被杀同理）。此前没有任何自愈机制，用户必须每次手动进设置页点一下。
 * <p>
 * 规则（2026-10 需求，按授权模式二分）：
 * <ul>
 *     <li><b>root 模式</b>：只要探测到服务离线、或服务在线但身份不是 root（残留的 shell 服务端），
 *     就自动执行一次 root 激活（{@link KeydroidxShizukuActivator#activateRootServer}，会自动弹一次
 *     su 授权框），无需用户手动点击；</li>
 *     <li><b>adb（mini_shizuku）模式</b>：服务离线且<strong>历史上曾成功激活过</strong>时，
 *     弹一条 Toast 提醒用户连接电脑重新激活；从未激活过则不打扰。</li>
 * </ul>
 * 防打扰：
 * <ul>
 *     <li>进程内单次守卫 —— 一个进程生命周期内只做一次恢复检查；</li>
 *     <li>「本次开机已自动尝试」令牌 —— 同一次开机内只自动激活一次，避免反复弹 su 授权框；</li>
 *     <li>「本次开机已提醒」令牌 —— 同一次开机内只弹一次失效提醒；</li>
 *     <li>复用 {@link KeydroidxShizukuActivator#tryLockActivation()} 互斥闸 —— 与设置页的手动激活
 *     天然互斥，不会出现两次并发启动互相 kill 服务端。</li>
 * </ul>
 * 执行时机：桌面 {@code onCreate} 之后延迟 3 秒（避开首帧与权限自检弹窗），且设备处于锁屏时
 * 轮询等待解锁后再执行（锁屏下 Toast 不可见、su 授权框也可能被系统拦截），最多等 5 分钟。
 * <p>
 * 线程模型：{@link Shizuku#isRunning()} / {@link Shizuku#serverUid()} 内部是 TCP（4.4 上主线程
 * 调用直接 {@code NetworkOnMainThreadException} 闪退），su 执行同样阻塞，故探测与激活一律在后台线程；
 * Toast 回主线程。本类对首帧零影响（延迟 + 异步）。
 */
public final class KeydroidxShizukuBootRecovery {

	private static final String TAG = "ShizukuBoot";

	/** 进入桌面后的延迟：避开首帧渲染与启动权限自检弹窗（与更新提醒弹窗同风格）。 */
	private static final long START_DELAY_MS = 3000L;
	/** 锁屏轮询间隔。 */
	private static final long KEYGUARD_POLL_MS = 3000L;
	/** 锁屏等待上限：超过则不再等解锁，按当前状态直接执行（避免永久挂起）。 */
	private static final long KEYGUARD_WAIT_TIMEOUT_MS = 5 * 60 * 1000L;

	/** 进程内单次守卫：本进程是否已发起过恢复检查。 */
	private static final AtomicBoolean sChecked = new AtomicBoolean(false);
	/** 恢复检查是否已进入后台执行（进入后 {@link #release(Activity)} 不再打断）。 */
	private static volatile boolean sWorkerStarted = false;

	private static final Handler sHandler = new Handler(Looper.getMainLooper());

	private KeydroidxShizukuBootRecovery() {
	}

	/**
	 * 桌面 {@code onCreate} 调用：延迟 + 解锁门控后，在后台执行一次恢复检查（进程内单次）。
	 * 幂等，可重复调用（例如 Activity 重建）。
	 */
	public static void scheduleAfterDesktopStart(Activity activity) {
		if (activity == null) {
			return;
		}
		final Context appCtx = activity.getApplicationContext();
		final long scheduledAt = System.currentTimeMillis();
		sHandler.postDelayed(new Runnable() {
			@Override
			public void run() {
				if (!sChecked.compareAndSet(false, true)) {
					KeydroidxLog.i(TAG, "本进程已发起过恢复检查，跳过");
					return;
				}
				waitUnlockThenStart(appCtx, scheduledAt);
			}
		}, START_DELAY_MS);
	}

	/**
	 * 锁屏门控：锁屏期间轮询等待解锁后再启动后台检查（锁屏下 Toast 不可见、su 授权框也可能被系统
	 * 拦截），最长等 {@link #KEYGUARD_WAIT_TIMEOUT_MS} 后按当前状态直接执行，避免永久挂起。
	 * <p>注意：这里<strong>不能</strong>再动 {@link #sChecked} —— 单次守卫只在首次调度时消费一次，
	 * 否则轮询的第二跳会被自己判成「已发起过」而直接放弃（2026-10-09 真机实测：开机锁屏场景
	 * 就是这样把整个恢复检查吞掉的）。
	 */
	private static void waitUnlockThenStart(final Context appCtx, final long scheduledAt) {
		if (isKeyguardLocked(appCtx)
				&& System.currentTimeMillis() - scheduledAt < KEYGUARD_WAIT_TIMEOUT_MS) {
			KeydroidxLog.i(TAG, "设备处于锁屏，延后恢复检查");
			sHandler.postDelayed(new Runnable() {
				@Override
				public void run() {
					waitUnlockThenStart(appCtx, scheduledAt);
				}
			}, KEYGUARD_POLL_MS);
			return;
		}
		startWorker(appCtx);
	}

	/**
	 * 桌面 {@code onDestroy} 调用：清掉尚未执行的延迟/轮询任务并复位单次守卫，
	 * 使下次进入桌面能重新发起检查。若后台检查已开始执行则<strong>不打断</strong>
	 * （激活流程可能正在进行，中途放弃会留下半激活状态）。
	 */
	public static void release(Activity activity) {
		if (sWorkerStarted) {
			return;
		}
		sHandler.removeCallbacksAndMessages(null);
		sChecked.set(false);
	}

	/** 是否处于锁屏。低版本 API 1 起即有；取不到服务时视为未锁屏（按原流程继续）。 */
	private static boolean isKeyguardLocked(Context ctx) {
		try {
			KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
			return km != null && km.isKeyguardLocked();
		} catch (Throwable t) {
			KeydroidxLog.w(TAG, "锁屏状态读取失败: " + t.getMessage());
			return false;
		}
	}

	private static void startWorker(final Context appCtx) {
		sWorkerStarted = true;
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					runRecovery(appCtx);
				} catch (Throwable t) {
					// 恢复检查失败不能影响桌面使用：只记录，不改任何状态
					KeydroidxLog.w(TAG, "恢复检查异常: " + t.getMessage(), t);
				}
			}
		}, "shizuku-boot-recovery").start();
	}

	/** 恢复检查主体（后台线程）。只做一次 TCP 探测 + 必要时一次激活/一次提醒。 */
	private static void runRecovery(Context appCtx) {
		final boolean running = Shizuku.isRunning();
		final int uid = running ? Shizuku.serverUid() : ServerIdentity.UID_UNKNOWN;
		final int mode = KeydroidxSettingsStorage.getAuthMode(appCtx);
		KeydroidxLog.i(TAG, "恢复检查: running=" + running + " uid=" + uid
				+ " mode=" + KeydroidxSettingsStorage.getAuthModeName(mode));

		if (running) {
			// 任意身份在线都算「曾成功激活过」——adb 模式（uid=2000）也由这里置位，
			// 它是「重启后是否值得提醒用户重新激活」的唯一依据。
			KeydroidxSettingsStorage.setShizukuEverActivated(appCtx, true);
		}

		if (mode == KeydroidxSettingsStorage.AUTH_MODE_ROOT) {
			if (running && uid == 0) {
				KeydroidxLog.i(TAG, "root 模式且服务端身份正确，无需处理");
				return;
			}
			// 离线，或在线但身份不是 root（残留 shell 服务端）：kill 旧进程后以 root 重新拉起
			autoActivateRoot(appCtx, running ? "身份非 root(uid=" + uid + ")" : "服务离线");
			return;
		}

		// adb（mini_shizuku）模式：只能靠电脑 adb 重新激活，服务离线且曾激活过时提醒一次
		if (!running && KeydroidxSettingsStorage.hasShizukuEverActivated(appCtx)) {
			notifyReactivateNeeded(appCtx);
		}
	}

	/**
	 * 自动 root 激活（后台线程，阻塞至多约 35 秒）。
	 * <p>
	 * 同一次开机内只尝试一次（持久化开机令牌），避免反复弹 su 授权框；
	 * 成功静默（su 授权框本身即反馈），失败弹 Toast 说明并提示可手动激活。
	 */
	private static void autoActivateRoot(Context appCtx, String reason) {
		final long bootToken = KeydroidxSettingsStorage.currentBootToken();
		final long lastToken = KeydroidxSettingsStorage.getShizukuAutoActivateBootToken(appCtx);
		if (lastToken == bootToken) {
			KeydroidxLog.i(TAG, "本次开机已自动激活尝试过，跳过（原因: " + reason + "）");
			return;
		}
		KeydroidxSettingsStorage.setShizukuAutoActivateBootToken(appCtx, bootToken);
		KeydroidxLog.i(TAG, "开始自动 root 激活（原因: " + reason + "）");
		final KeydroidxShizukuActivator.Result r = KeydroidxShizukuActivator.activateRootServer(appCtx);
		if (r.busy) {
			// 设置页正在激活：它自己会给出结果提示，这里不打扰
			KeydroidxLog.i(TAG, "自动激活被互斥闸拒绝（已有激活在进行）");
			return;
		}
		if (r.isFullyOk()) {
			KeydroidxSettingsStorage.setAuthMode(appCtx, KeydroidxSettingsStorage.AUTH_MODE_ROOT);
			KeydroidxLog.i(TAG, "自动 root 激活成功");
			return;
		}
		final String msg;
		if (!r.execOk) {
			msg = "mini_shizuku 自动激活失败：无 root 或 su 授权被拒";
		} else if (!r.online) {
			msg = "mini_shizuku 自动激活失败：服务未上线，请到设置页手动激活";
		} else {
			msg = "mini_shizuku 自动激活异常：服务端身份 uid=" + r.serverUid + " ≠ 0";
		}
		KeydroidxLog.w(TAG, msg + " (execOk=" + r.execOk + " online=" + r.online
				+ " serverUid=" + r.serverUid + ")");
		toast(appCtx, msg);
	}

	/** adb 模式失效提醒（同一次开机只弹一次）。 */
	private static void notifyReactivateNeeded(Context appCtx) {
		final long bootToken = KeydroidxSettingsStorage.currentBootToken();
		if (KeydroidxSettingsStorage.getShizukuNotifiedBootToken(appCtx) == bootToken) {
			KeydroidxLog.i(TAG, "本次开机已提醒过服务失效，跳过");
			return;
		}
		KeydroidxSettingsStorage.setShizukuNotifiedBootToken(appCtx, bootToken);
		KeydroidxLog.i(TAG, "adb 模式服务离线，提醒用户重新激活");
		toast(appCtx, "mini_shizuku 已失效，请连接电脑 adb 重新激活");
	}

	/** 主线程弹 Toast（应用级 Context，不依赖 Activity 存活）。 */
	private static void toast(final Context appCtx, final String msg) {
		sHandler.post(new Runnable() {
			@Override
			public void run() {
				try {
					Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show();
				} catch (Throwable t) {
					KeydroidxLog.w(TAG, "Toast 失败: " + t.getMessage());
				}
			}
		});
	}
}
