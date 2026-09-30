package ru.playsoftware.j2meloader.nokia;
import io.github.cctyl.nokia.common.ui.KeydroidxFontManager;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import io.github.cctyl.nokia.common.ui.focus.KeydroidxFocusHost;
import io.github.cctyl.nokia.common.util.KeydroidxDimens;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.mini_shizuku.Shizuku;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * mini_shizuku → root 激活页。
 * <p>
 * 通过 {@link KeydroidxRootShell}（su -c 直执）获取 root 权限，以 root 身份拉起 mini_shizuku 服务端
 * （{@code app_process}），使服务端获得完整权限：/dev/uinput 写权限（电源键拦截方案1 的
 * uinput 回放完整生效）、/dev/input 完全读写（grab 更可靠）。拦截逻辑本身与 adb/shell
 * 方式完全一致，仅服务端进程身份不同——root 激活后回到「电源键拦截设置」选方案1 即为完整回放。
 * <p>
 * 页面结构：状态行（root 权限可用性 + 服务在线状态）+ 操作列表（root 激活 / 刷新状态）。
 */
public class ShizukuRootFragment extends KeydroidxListPageFragment {
	private static final String TAG = "ShizukuRootFragment";


	private static final String[] ACTION_NAMES = {
			"root 激活",
			"刷新状态",
	};

	/** root 激活总超时（毫秒）：覆盖 su 授权弹窗等待 + 服务启动。 */
	private static final long ACTIVATE_TIMEOUT_MS = 20000L;
	/** 服务在线轮询间隔（毫秒）。 */
	private static final long POLL_INTERVAL_MS = 500L;

	private TextView statusText;
	private LinearLayout actionList;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_shizuku_root;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		statusText = view.findViewById(R.id.shizukuRootStatus);
		listScroll = view.findViewById(R.id.shizukuRootScroll);
		actionList = view.findViewById(R.id.shizukuRootActions);

		buildActionList();
		refreshStatus();
		setFocusIndex(0);
	}

	/** 构建底部可导航操作列表（方向键 + 确认键触发）。 */
	private void buildActionList() {
		actionList.removeAllViews();
		itemViews = new View[ACTION_NAMES.length];
		for (int i = 0; i < ACTION_NAMES.length; i++) {
			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			// 高度 WRAP_CONTENT + minHeight：大字号/点阵字体行盒放大后固定行高会裁掉文字
			row.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
			row.setMinimumHeight(KeydroidxDimens.dp(getResources(), 36));
			row.setPadding(KeydroidxDimens.dp(getResources(), 12), 0,
					KeydroidxDimens.dp(getResources(), 12), 0);
			row.setClickable(true);

			TextView tv = new TextView(requireContext());
			tv.setLayoutParams(new LinearLayout.LayoutParams(
					0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
			tv.setText(ACTION_NAMES[i]);
			tv.setTextColor(0xFFFFFFFF);
			KeydroidxFontManager.textSize(tv, 12);
			row.addView(tv);

			TextView arrow = new TextView(requireContext());
			arrow.setText(">");
			arrow.setTextColor(0xFFAAAAAA);
			KeydroidxFontManager.textSize(arrow, 13);
			row.addView(arrow);

			final int idx = i;
			row.setOnClickListener(v -> {
				setFocusIndex(idx);
				onSelect();
			});

			actionList.addView(row);
			itemViews[i] = row;
		}
	}

	/** 后台刷新：root 权限可用性 + 服务在线状态，回主线程更新状态行。 */
	private void refreshStatus() {
		final Handler mainHandler = new Handler(Looper.getMainLooper());
		new Thread(new Runnable() {
			@Override
			public void run() {
				final Boolean rootOk = isRootAvailable();
				final boolean running = Shizuku.isRunning();
				mainHandler.post(new Runnable() {
					@Override
					public void run() {
						if (!isAdded()) return;
						updateStatusText(rootOk, running);
					}
				});
			}
		}, "shizuku-root-status").start();
	}

	private void updateStatusText(Boolean rootOk, boolean running) {
		if (statusText == null) return;
		String rootText;
		if (rootOk == null) {
			rootText = "待授权（点击激活确认）";
		} else {
			rootText = rootOk ? "可用" : "不可用";
		}
		statusText.setText("root 权限：" + rootText
				+ "\n服务状态：" + (running ? "在线" : "离线"));
		statusText.setTextColor((Boolean.TRUE.equals(rootOk) && running) ? 0xFF64B5F6 : 0xFFFF8A80);
	}

	/**
	 * root 激活主流程：<strong>先</strong>执行 root 启动（su 授权弹窗在此发生并阻塞等待），
	 * 成功后再轮询新 root 服务上线。
	 * <p>
	 * 注意：绝不能先轮询再启动——服务可能本来就在线（如 adb shell 方式启动的旧服务），
	 * 轮询会立即命中造成「已激活」误报，掩盖真实的 su 授权弹窗。
	 * root 启动脚本会先 kill 旧 app_process，因此服务会短暂离线后由新 root 进程接管。
	 * <p>
	 * 并发保护：启动脚本会 kill 掉<strong>所有</strong> app_process，两次激活并发执行必然互相拆台
	 * （后一次杀掉前一次刚拉起的服务端，双双等满 20 秒超时）。故用
	 * {@link KeydroidxShizukuActivator#tryLockActivation()} 串行化——等待期间再点「root 激活」
	 * 只会提示正在激活，不会另起一次。
	 */
	private void activateRoot() {
		if (!KeydroidxShizukuActivator.tryLockActivation()) {
			KeydroidxLog.w("ShizukuRoot", "已有 root 激活正在进行，忽略重复点击");
			Toast.makeText(requireContext(), "正在激活中，请稍候…", Toast.LENGTH_SHORT).show();
			return;
		}
		KeydroidxLog.i("ShizukuRoot", "开始 root 激活");
		if (statusText != null) {
			statusText.setText("正在通过 root 激活...");
		}
		final Handler mainHandler = new Handler(Looper.getMainLooper());
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					// 1) 执行 root 启动：su -c 直执；SuperSU 策略为 prompt 时会弹授权窗，
					//    用户允许后脚本才真正执行（超时 15s，见 KeydroidxRootShell）。
					boolean execOk = startServerAsRoot();
					if (!execOk) {
						KeydroidxLog.e("ShizukuRoot", "root 激活失败：无 root 或 su 授权被拒");
						mainHandler.post(new Runnable() {
							@Override
							public void run() {
								if (!isAdded()) return;
								refreshStatus();
								Toast.makeText(requireContext(),
										"root 激活失败：无 root 或 su 授权被拒",
										Toast.LENGTH_SHORT).show();
							}
						});
						return;
					}
					// 2) root 启动命令已成功执行（旧服务端已被杀），轮询等待新 root 服务上线
					long deadline = System.currentTimeMillis() + ACTIVATE_TIMEOUT_MS;
					boolean online = false;
					while (System.currentTimeMillis() < deadline) {
						if (Shizuku.isRunning()) {
							online = true;
							break;
						}
						try {
							Thread.sleep(POLL_INTERVAL_MS);
						} catch (InterruptedException e) {
							KeydroidxLog.w(TAG, "sleep failed: " + e.getMessage());
							break;
						}
					}
					final boolean ok = online;
					// WHOAMI 身份校验：模式选择 ≠ 服务真实身份。root 激活要求服务端 uid==0；
					// 不一致（如旧 shell 服务未被杀干净）或无法确认（旧版服务端）时明确提示，
					// 避免后续命令以错误身份执行（设计文档 §3.4）。
					int serverUid = ru.playsoftware.mini_shizuku.ServerIdentity.UID_UNKNOWN;
					boolean uidOk = false;
					if (ok) {
						serverUid = Shizuku.serverUid();
						uidOk = serverUid == 0;
						KeydroidxLog.i(TAG, "WHOAMI 校验: serverUid=" + serverUid + " ok=" + uidOk);
					}
					final int finalServerUid = serverUid;
					final boolean finalUidOk = uidOk;
					// 失败时复用 root 通道抓现场写进日志：启动脚本以 & 后台化，
					// 退出码 0 只代表 root shell 把命令发出去了，不代表服务端真的起来了。
					// 真正的原因在 minishizuku*.log 与 logcat 的 MiniShizuku（详见诊断摘要）。
					if (!ok) {
						collectActivationDiagnostics();
					}
					KeydroidxLog.i("ShizukuRoot", "root 激活结果: online=" + online + " execOk=true"
							+ (ok ? " serverUid=" + finalServerUid : ""));
					mainHandler.post(new Runnable() {
						@Override
						public void run() {
							if (!isAdded()) return;
							refreshStatus();
							String msg;
							if (!ok) {
								msg = "root 启动命令已执行，但服务未上线，请查看 mini_shizuku 日志";
							} else if (!finalUidOk) {
								msg = "服务已上线，但服务端身份异常 (uid=" + finalServerUid
										+ " ≠ 0)，请重新激活";
							} else {
								// root 激活成功且身份校验通过：服务端已是 root 身份，
								// 同步授权模式偏好为 root 模式，保持设置页状态一致
								KeydroidxSettingsStorage.setAuthMode(requireContext(),
										KeydroidxSettingsStorage.AUTH_MODE_ROOT);
								msg = "root 激活成功，方案1 将获得完整回放能力";
							}
							Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
						}
					});
				} finally {
					// 任何出口（含上面的提前 return）都必须放闸，否则激活入口会被永久拒绝
					KeydroidxShizukuActivator.unlockActivation();
				}
			}
		}, "shizuku-root-activate").start();
	}

	/**
	 * 检测当前设备是否可获取 root 权限。
	 * <p>
	 * 使用 {@link KeydroidxRootShell#isRootAvailable}（su -c true 探测，5 秒超时）：
	 * SuperSU 策略为 grant 时秒回 true；策略为 prompt 时会弹授权窗，用户允许后即 granted。
	 * 未 root（PATH 无 su）时 su 直接失败返回 false。
	 */
	private Boolean isRootAvailable() {
		if (Build.VERSION.SDK_INT < 19) {
			KeydroidxLog.w("ShizukuRoot", "root 检测跳过: SDK " + Build.VERSION.SDK_INT + " < 19");
			return Boolean.FALSE;
		}
		try {
			boolean ok = KeydroidxRootShell.isRootAvailable(requireContext());
			KeydroidxLog.i("ShizukuRoot", "root 检测(状态): " + ok);
			return ok;
		} catch (Exception e) {
			KeydroidxLog.w("ShizukuRoot", "root 检测异常: " + e.getMessage());
			return Boolean.FALSE;
		}
	}

	/**
	 * 通过 {@link KeydroidxRootShell}（su -c 直执）以 root 身份拉起 mini_shizuku 服务端：
	 * 先杀掉旧的 app_process（shell/root 均杀），再以 root 启动新服务端。
	 * 启动参数与 {@code mini_shizuku.sh} 一致，并注入 {@code -Dapp.package}，
	 * 供 APK 重装后服务端通过 pm path 重新定位。
	 * <p>
	 * 注意：app_process 后台化前先 {@code trap '' 1} 忽略 SIGHUP，避免 su 进程退出后被回收；
	 * stdin 重定向 /dev/null，防止后台进程持有 su 的 stdin 管道干扰排空。
	 * <p>
	 * <b>历史教训：</b>曾用 libsu 持久 root shell 提交此脚本，exec() 标记回显收不到而
	 * 无限期挂起（脚本实际已执行），4.4 + SuperSU 2.76 上必现——已整体替换为 su -c 直执。
	 */
	private boolean startServerAsRoot() {
		try {
			if (Build.VERSION.SDK_INT < 19) {
				KeydroidxLog.w("ShizukuRoot", "root 启动跳过: SDK < 19");
				return false;
			}
			String apk = requireContext().getApplicationInfo().sourceDir;
			String pkg = requireContext().getPackageName();
			// root 身份的 Java 进程（app_process 所在 SELinux 域）无权写 /data/local/tmp，
			// 服务端自己部署 so 会 EACCES。改为：App 侧从 APK 解出最新 so 到 cache，
			// 由本 root 脚本 cat 到 /data/local/tmp——保证服务端加载的永远是当前 APK 的
			// so 版本（旧 so 可能缺少新 JNI 方法，如 nativeSetSuppGroups）。
			String deployLib = "";
			File soInCache = extractInterceptorLibToCache();
			if (soInCache != null) {
				deployLib = "cat '" + soInCache.getAbsolutePath()
						+ "' > /data/local/tmp/libnokiainterceptor.so; "
						+ "chmod 755 /data/local/tmp/libnokiainterceptor.so; ";
			} else {
				KeydroidxLog.w(TAG, "so 解出失败，服务端将尝试使用 /data/local/tmp 已有库");
			}
			// 与 assets/mini_shizuku.sh 保持一致：日志先试固定名，写不动（被其它 uid 占用）
			// 则退到带 uid 后缀的专属文件，避免 root/adb 混用激活时 app_process 因
			// "can't create ...: Permission denied" 根本不启动。
			// 关键：app_process 必须用 su -cn u:r:shell:s0 切到 shell SELinux 域拉起（root uid 保留）。
			// 4.4 真机实测：SuperSU 默认 context=u:r:init:s0 下，root 身份的 app_process 无法
			// 访问 /data/local/tmp（stat 不可见、create EACCES），so 部署与加载全部失败；
			// shell 域无此限制，且 root uid + CAP_SETGID 保留（setgroups 补组实测成功）。
			String script = "trap '' 1; "
					+ "ps | grep app_process | grep -v grep | while read -r line; do set -- $line; kill -9 $2 2>/dev/null; done; "
					+ deployLib
					+ "LOG=/data/local/tmp/minishizuku.log; "
					+ ": > \"$LOG\" 2>/dev/null; "
					+ "su -cn u:r:shell:s0 -c \"trap '' 1; app_process -Djava.class.path=" + apk
					+ " -Dapp.package=" + pkg
					+ " /system/bin ru.playsoftware.mini_shizuku.server.AdbProcess"
					+ " >> /data/local/tmp/minishizuku.log 2>&1 </dev/null &\"";
			KeydroidxLog.i("ShizukuRoot", "执行 root 启动: " + script);
			// su -c 直执（libsu 在 4.4 + SuperSU 2.76 上 exec() 会挂死，见类注释）
			KeydroidxRootShell.Result r = KeydroidxRootShell.exec(requireContext(), script, 15000);
			KeydroidxLog.i("ShizukuRoot", "root 启动服务端退出码: " + r.code + " out=" + r.out.trim());
			return r.isSuccess();
		} catch (Exception e) {
			KeydroidxLog.e("ShizukuRoot", "root 启动服务端异常", e);
			return false;
		}
	}

	/**
	 * 从本应用 APK 中解出 libnokiainterceptor.so 到 cacheDir，供 root 激活脚本
	 * cat 部署到 /data/local/tmp（root shell 可写；root 身份的 Java 服务进程不可写）。
	 * ABI 选择与 {@code InterceptorNative.getSupportedAbis} 一致：
	 * API 21+ 读 SUPPORTED_ABIS，4.4 降级 CPU_ABI/CPU_ABI2。失败返回 null（不抛异常）。
	 */
	@Nullable
	private File extractInterceptorLibToCache() {
		ZipFile zip = null;
		try {
			String apkPath = requireContext().getApplicationInfo().sourceDir;
			zip = new ZipFile(apkPath);
			String[] abis;
			try {
				Object o = Build.class.getField("SUPPORTED_ABIS").get(null);
				abis = (o instanceof String[] && ((String[]) o).length > 0)
						? (String[]) o : new String[]{Build.CPU_ABI, Build.CPU_ABI2};
			} catch (Throwable t) {
				abis = new String[]{Build.CPU_ABI, Build.CPU_ABI2};
			}
			ZipEntry entry = null;
			for (String abi : abis) {
				if (abi == null || abi.isEmpty()) continue;
				entry = zip.getEntry("lib/" + abi + "/libnokiainterceptor.so");
				if (entry != null) break;
			}
			if (entry == null) {
				return null;
			}
			File out = new File(requireContext().getCacheDir(), "libnokiainterceptor.so");
			InputStream in = zip.getInputStream(entry);
			try {
				FileOutputStream fos = new FileOutputStream(out);
				try {
					byte[] buf = new byte[8192];
					int n;
					while ((n = in.read(buf)) > 0) {
						fos.write(buf, 0, n);
					}
				} finally {
					fos.close();
				}
			} finally {
				in.close();
			}
			return out;
		} catch (Throwable t) {
			KeydroidxLog.w(TAG, "解出 libnokiainterceptor.so 失败: " + t.getMessage());
			return null;
		} finally {
			if (zip != null) {
				try {
					zip.close();
				} catch (Exception ignored) {
				}
			}
		}
	}

	/**
	 * 激活失败时用已到手的 root 通道采集现场，写进 {@link KeydroidxLog}，
	 * 让「root 命令执行成功但服务端始终没上线」这类只能靠用户回传日志定位的问题自证。
	 * <p>
	 * <b>顺序要求：摘要块必须放最前。</b>「待上传」标记只保留 detail 前 200 字符、
	 * 上报注释只保留前 160 字节（UTF-8），而完整现场有上百行——2026-09-29 21:23:54 那次
	 * 自动上报（Android 4.4.2 / MT6572）正文完全为空，退一步讲，即便有内容，
	 * 排在末尾的「服务端日志尾巴 / 端口是否被占」也一定被截掉。故先输出两行可判读摘要，
	 * 再输出详述块（详述块完整落在当天日志里，随上传 zip 一起走）。
	 * <ul>
	 *   <li>{@code proc= / listen10500= / enforce=} —— 服务端进程是否还活着、
	 *       10500（{@code 0x2904}）是否已有人 LISTEN（被占则新服务端必定 BindException 退出）、
	 *       SELinux 模式；</li>
	 *   <li>{@code log_tail= / log=} —— minishizuku.log 的最后两行与大小/时间
	 *       （app_process 崩溃栈、BindException、EACCES 都在这里）；</li>
	 *   <li>详述：app_process 全表、minishizuku*.log tail 80、
	 *       logcat 里的 MiniShizuku（服务端 Java 层错误只进 logcat，不落 minishizuku.log）。</li>
	 * </ul>
	 * 根 shell 不可用时只记一行；任何情况都不抛异常。
	 */
	private void collectActivationDiagnostics() {
		try {
			String diag = "echo '=== SUMMARY ==='; "
					// 进程是否还在 + 端口是否被占（10500 = 0x2904，/proc/net/tcp 里 st=0A 为 LISTEN）+ SELinux
					+ "echo \"proc=$( (ps -A 2>/dev/null || ps) | grep -c app_process ) "
					+ "listen10500=$(cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep ':2904 ' | grep -c ' 0A ') "
					+ "enforce=$(getenforce 2>&1)\"; "
					// log_tail 是决定性的那一行，必须排在 log= 之前：上报注释只有 160 字节，
					// ls -l 的完整路径能吃掉 80 字节，排在前面会把真正的死因挤出可视范围
					+ "echo \"log_tail=$(tail -n 2 /data/local/tmp/minishizuku*.log 2>&1 | while read -r l; do printf '%s | ' \"$l\"; done)\"; "
					+ "echo \"log=$(ls -l /data/local/tmp/minishizuku*.log 2>&1)\"; "
					+ "echo '=== app_process procs ==='; "
					+ "(ps -A 2>/dev/null || ps) | grep -i app_process; "
					+ "echo '=== minishizuku.log (tail 80) ==='; "
					+ "tail -n 80 /data/local/tmp/minishizuku*.log 2>&1; "
					+ "echo '=== logcat MiniShizuku (tail 60) ==='; "
					// logcat -t 是后加的选项，4.4（本上报的设备）上拿不到内容；空了就退回全量 dump + grep
					+ "L=$(logcat -d -t 500 -s MiniShizuku:* 2>/dev/null | tail -n 60); "
					+ "[ -z \"$L\" ] && L=$(logcat -d 2>/dev/null | grep -i minishizuku | tail -n 60); "
					+ "echo \"$L\"; "
					+ "echo '=== END ==='";
			KeydroidxRootShell.Result r = KeydroidxRootShell.exec(requireContext(), diag, 10000);
			KeydroidxLog.e("ShizukuRoot", "root 激活失败诊断(exit " + r.code + "):\n" + r.out);
			// 保险起见多一步：把整个 minishizuku.log 原样复制到 KeydroidxLog 日志目录，
			// 保留完整原始文件（内联 tail 只截了 80 行），方便事后排查 / 寄回。
			copyMinishizukuLog();
		} catch (Exception e) {
			KeydroidxLog.e("ShizukuRoot", "collect diagnostics failed", e);
		}
	}

	/**
	 * 通过 root shell 把 {@code /data/local/tmp/minishizuku.log} 复制到 {@link KeydroidxLog}
 * 日志目录下，文件名带时间戳。app 自身 uid 无权读 /data/local/tmp，必须经 root；
 * 目标目录是 app 私有外存（/sdcard/Android/data/&lt;pkg&gt;/files/log），root 可写，
	 * 复制后用户/我们可直接取走完整原始日志。
	 */
	private void copyMinishizukuLog() {
		File logDir = KeydroidxLog.getLogDir();
		if (logDir == null) {
			KeydroidxLog.w("ShizukuRoot", "copy minishizuku.log skipped: KeydroidxLog dir not initialized");
			return;
		}
		String name = "minishizuku_"
				+ new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
				+ ".log";
		File target = new File(logDir, name);
		String targetPath = target.getAbsolutePath().replace(" ", "\\ ");
		String cmd = "cp -f /data/local/tmp/minishizuku.log " + targetPath + " 2>&1; "
				+ "ls -l " + targetPath + " 2>&1";
		try {
			KeydroidxRootShell.Result r = KeydroidxRootShell.exec(requireContext(), cmd, 8000);
			// 显式校验落盘结果：cp 失败（源不存在 / 目标目录不可写）时 target 不会存在。
			// 旧写法无论成败都只记一句 i，事后无法判断「原始服务端日志到底有没有随包寄回」——
			// 2026-09-29 那份上报里就没有 minishizuku_*.log，只能反推复制没生效。
			if (target.isFile() && target.length() > 0) {
				KeydroidxLog.i("ShizukuRoot", "copied minishizuku.log -> " + target.getAbsolutePath()
						+ " (" + target.length() + " bytes, exit " + r.code + ") " + r.out.trim());
			} else {
				// 诊断链路自身的失败按规范记 w（e 会再落一次上报标记）
				KeydroidxLog.w("ShizukuRoot", "复制 minishizuku.log 失败(exit " + r.code + "): " + r.out.trim());
			}
		} catch (Exception e) {
			KeydroidxLog.w("ShizukuRoot", "copy minishizuku.log failed", e);
		}
	}

	// ---- KeydroidxFocusHost ----


	@Override
	public boolean onSelect() {
		if (focusIndex < 0 || focusIndex >= ACTION_NAMES.length) return false;
		onAction(focusIndex);
		return true;
	}

	private void onAction(int index) {
		switch (index) {
			case 0:
				KeydroidxLog.i("ShizukuRoot", "点击 root 激活");
				activateRoot();
				break;
			case 1:
				KeydroidxLog.i("ShizukuRoot", "点击刷新状态");
				refreshStatus();
				break;
			default:
				break;
		}
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
		((KeydroidxDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	// ---- KeydroidxPage ----

	@Override
	public String getPageTitle() {
		return "root 激活";
	}

	@Override
	public String getSoftLeftText() {
		return "选择";
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}


}
