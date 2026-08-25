package ru.playsoftware.j2meloader.nokia;

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

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.topjohnwu.superuser.Shell;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.mini_shizuku.Shizuku;

/**
 * mini_shizuku → root 激活页。
 * <p>
 * 通过 libsu（{@link Shell}）获取 root 权限，以 root 身份拉起 mini_shizuku 服务端
 * （{@code app_process}），使服务端获得完整权限：/dev/uinput 写权限（电源键拦截方案1 的
 * uinput 回放完整生效）、/dev/input 完全读写（grab 更可靠）。拦截逻辑本身与 adb/shell
 * 方式完全一致，仅服务端进程身份不同——root 激活后回到「电源键拦截设置」选方案1 即为完整回放。
 * <p>
 * 页面结构：状态行（root 权限可用性 + 服务在线状态）+ 操作列表（root 激活 / 刷新状态）。
 */
public class ShizukuRootFragment extends NokiaListPageFragment {

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
			row.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, NokiaDimens.dp(getResources(), 36)));
			row.setPadding(NokiaDimens.dp(getResources(), 12), 0,
					NokiaDimens.dp(getResources(), 12), 0);
			row.setClickable(true);

			TextView tv = new TextView(requireContext());
			tv.setLayoutParams(new LinearLayout.LayoutParams(
					0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
			tv.setText(ACTION_NAMES[i]);
			tv.setTextColor(0xFFFFFFFF);
			NokiaDimens.textSize(tv, 12);
			row.addView(tv);

			TextView arrow = new TextView(requireContext());
			arrow.setText(">");
			arrow.setTextColor(0xFFAAAAAA);
			NokiaDimens.textSize(arrow, 14);
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
	 */
	private void activateRoot() {
		NokiaLog.i("ShizukuRoot", "开始 root 激活");
		if (statusText != null) {
			statusText.setText("正在通过 root 激活...");
		}
		final Handler mainHandler = new Handler(Looper.getMainLooper());
		new Thread(new Runnable() {
			@Override
			public void run() {
				// 1) 执行 root 启动：libsu 内部获取 root shell 会弹 su 授权窗并阻塞等待
				//    （Builder 默认超时 20s），用户在弹窗中允许后才继续。
				boolean execOk = startServerAsRoot();
				if (!execOk) {
					NokiaLog.e("ShizukuRoot", "root 激活失败：无 root 或 su 授权被拒");
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
				// 2) root 启动命令已成功执行（旧 shell 服务已被杀），轮询等待新 root 服务上线
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
						break;
					}
				}
				final boolean ok = online;
				// On failure, reuse the root shell to dump diagnostics into NokiaLog.
				// app_process is backgrounded with &, so exit code 0 only means the root
				// shell dispatched the command - not that the server actually came up.
				// The real failure reason lives in minishizuku.log / logcat MiniShizuku.
				if (!ok) {
					collectActivationDiagnostics();
				}
				NokiaLog.i("ShizukuRoot", "root 激活结果: online=" + online + " execOk=true");
				mainHandler.post(new Runnable() {
					@Override
					public void run() {
						if (!isAdded()) return;
						refreshStatus();
						Toast.makeText(requireContext(),
								ok ? "root 激活成功，方案1 将获得完整回放能力"
										: "root 启动命令已执行，但服务未上线，请查看 mini_shizuku 日志",
								Toast.LENGTH_SHORT).show();
					}
				});
			}
		}, "shizuku-root-activate").start();
	}

	/**
	 * 检测当前设备是否可获取 root 权限。
	 * <p>
	 * 使用 libsu {@link Shell#isAppGrantedRoot()}：
	 * <ul>
	 *     <li>已确认授权 → {@code true}；</li>
	 *     <li>PATH 中无 su（未 root）→ {@code false}；</li>
	 *     <li>有 su 但未授权/未探测过 → {@code null}（待确认）。</li>
	 * </ul>
	 * 本方法刻意<strong>不创建 shell</strong>：一旦创建 non-root shell 会被 main shell 缓存，
	 * 污染后续 root 激活。真正的 root 探测发生在 {@link #startServerAsRoot()}（会弹 su 授权窗）。
	 */
	private Boolean isRootAvailable() {
		if (Build.VERSION.SDK_INT < 19) {
			// libsu core 要求 API 19+，低版本直接判定不可用
			NokiaLog.w("ShizukuRoot", "root 检测跳过: SDK " + Build.VERSION.SDK_INT + " < 19");
			return Boolean.FALSE;
		}
		try {
			Boolean granted = Shell.isAppGrantedRoot();
			NokiaLog.i("ShizukuRoot", "root 检测(状态): "
					+ (granted == null ? "待授权" : granted));
			return granted;
		} catch (Exception e) {
			NokiaLog.w("ShizukuRoot", "root 检测异常: " + e.getMessage());
			return Boolean.FALSE;
		}
	}

	/**
	 * 确保 main shell 是 root；若缓存的是 non-root（如探测阶段误建），先关闭释放缓存再重建。
	 *
	 * @return 返回 root shell；无法获得时返回 {@code null}（无 root / 未授权）。
	 */
	private Shell ensureRootShell() {
		Shell shell = Shell.getShell();
		if (shell.isRoot()) {
			return shell;
		}
		// 缓存的是 non-root shell：关闭使其状态置 UNKNOWN，MainShell.getCached() 会丢弃，
		// 下次 get() 重新按 su → sh 顺序构建。
		NokiaLog.w("ShizukuRoot", "main shell 非 root，尝试重建以获取 root 权限");
		try {
			shell.waitAndClose(1, java.util.concurrent.TimeUnit.SECONDS);
		} catch (Exception ignored) {
		}
		// 重建期间若用户拒绝 su 授权，仍会退回 sh（non-root），此处校验兜底
		Shell rebuilt = Shell.getShell();
		if (rebuilt != null && rebuilt.isRoot()) {
			return rebuilt;
		}
		NokiaLog.w("ShizukuRoot", "重建后仍非 root：无 root 或 su 授权被拒绝");
		return null;
	}

	/**
	 * 通过 libsu root shell 以 root 身份拉起 mini_shizuku 服务端：
	 * 先杀掉旧的 app_process（shell/root 均杀），再以 root 启动新服务端。
	 * 启动参数与 {@code mini_shizuku.sh} 一致，并注入 {@code -Dapp.package}，
	 * 供 APK 重装后服务端通过 pm path 重新定位。
	 * <p>
	 * 注意：app_process 后台化前先 {@code trap '' 1} 忽略 SIGHUP，避免 su 进程退出后被回收。
	 */
	private boolean startServerAsRoot() {
		try {
			if (Build.VERSION.SDK_INT < 19) {
				NokiaLog.w("ShizukuRoot", "root 启动跳过: SDK < 19");
				return false;
			}
			// 关键：必须拿到 root shell。直接 Shell.cmd() 会复用被缓存的 non-root shell
			//（探测阶段误建时），导致脚本实际以非 root 身份执行。ensureRootShell 负责
			// 关闭 non-root 缓存并重建 root shell。
			Shell rootShell = ensureRootShell();
			if (rootShell == null) {
				NokiaLog.e("ShizukuRoot", "root 启动失败: 无法获得 root shell");
				return false;
			}
			String apk = requireContext().getApplicationInfo().sourceDir;
			String pkg = requireContext().getPackageName();
			String script = "trap '' 1; "
					+ "ps | grep app_process | grep -v grep | while read -r line; do set -- $line; kill -9 $2 2>/dev/null; done; "
					+ "app_process -Djava.class.path='" + apk + "' -Dapp.package='" + pkg
					+ "' /system/bin ru.playsoftware.mini_shizuku.server.AdbProcess"
					+ " >> /data/local/tmp/minishizuku.log 2>&1 &";
			NokiaLog.i("ShizukuRoot", "执行 root 启动: " + script);
			// 在 root shell 上执行脚本（复用同一持久 root shell，与 su -c 等价）
			Shell.Result r = rootShell.newJob().add(script).exec();
			NokiaLog.i("ShizukuRoot", "root 启动服务端退出码: " + r.getCode());
			return r.isSuccess();
		} catch (Exception e) {
			NokiaLog.e("ShizukuRoot", "root 启动服务端异常", e);
			return false;
		}
	}

	/**
	 * On activation failure, collect diagnostics via the already-acquired root shell and
	 * write them into {@link NokiaLog}, so 'root command ran but server never came online'
	 * cases are self-documenting (user just sends back the app log).
	 *
	 * <ul>
	 *   <li>{@code getenforce} - SELinux mode;</li>
	 *   <li>list {@code app_process} procs (is the server alive? as which uid?);</li>
	 *   <li>{@code tail /data/local/tmp/minishizuku.log} - where the startup script
	 *       redirects stdout/stderr; app_process crash stacks / SELinux denials land here;</li>
	 *   <li>{@code logcat -s MiniShizuku} - server-side Log output (Java-level errors).</li>
	 * </ul>
	 * Silently records a single line if the root shell is unavailable; never throws.
	 */
	private void collectActivationDiagnostics() {
		try {
			Shell rootShell = ensureRootShell();
			if (rootShell == null) {
				NokiaLog.w("ShizukuRoot", "diagnostics skipped: no root shell");
				return;
			}
			String diag = "echo '=== getenforce ==='; getenforce 2>&1; "
					+ "echo '=== app_process procs ==='; "
					+ "(ps -A 2>/dev/null || ps) | grep -i app_process; "
					+ "echo '=== minishizuku.log (tail 80) ==='; "
					+ "tail -n 80 /data/local/tmp/minishizuku.log 2>&1; "
					+ "echo '=== logcat MiniShizuku (tail 60) ==='; "
					+ "logcat -d -t 500 -s MiniShizuku:* 2>&1 | tail -n 60; "
					+ "echo '=== END ==='";
			Shell.Result r = rootShell.newJob().add(diag).exec();
			StringBuilder sb = new StringBuilder();
			for (String l : r.getOut()) {
				sb.append(l).append('\n');
			}
			NokiaLog.e("ShizukuRoot", "root activation failure diagnostics (exit " + r.getCode() + "):\n" + sb.toString());
			// 保险起见多一步：把整个 minishizuku.log 原样复制到 NokiaLog 日志目录，
			// 保留完整原始文件（内联 tail 只截了 80 行），方便事后排查 / 寄回。
			copyMinishizukuLog(rootShell);
		} catch (Exception e) {
			NokiaLog.e("ShizukuRoot", "collect diagnostics failed", e);
		}
	}

	/**
	 * 通过 root shell 把 {@code /data/local/tmp/minishizuku.log} 复制到 {@link NokiaLog}
	 * 日志目录下，文件名带时间戳。app 自身 uid 无权读 /data/local/tmp，必须经 root；
	 * 目标目录是 app 私有外存（/sdcard/Android/data/&lt;pkg&gt;/log），root 可写，
	 * 复制后用户/我们可直接取走完整原始日志。
	 */
	private void copyMinishizukuLog(Shell rootShell) {
		File logDir = NokiaLog.getLogDir();
		if (logDir == null) {
			NokiaLog.w("ShizukuRoot", "copy minishizuku.log skipped: NokiaLog dir not initialized");
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
			Shell.Result r = rootShell.newJob().add(cmd).exec();
			StringBuilder sb = new StringBuilder();
			for (String l : r.getOut()) {
				sb.append(l).append('\n');
			}
			NokiaLog.i("ShizukuRoot", "copied minishizuku.log -> " + target.getAbsolutePath()
					+ " (exit " + r.getCode() + ") " + sb.toString().trim());
		} catch (Exception e) {
			NokiaLog.e("ShizukuRoot", "copy minishizuku.log failed", e);
		}
	}

	// ---- NokiaFocusHost ----


	@Override
	public boolean onSelect() {
		if (focusIndex < 0 || focusIndex >= ACTION_NAMES.length) return false;
		onAction(focusIndex);
		return true;
	}

	private void onAction(int index) {
		switch (index) {
			case 0:
				NokiaLog.i("ShizukuRoot", "点击 root 激活");
				activateRoot();
				break;
			case 1:
				NokiaLog.i("ShizukuRoot", "点击刷新状态");
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
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	@Override
	public boolean onBack() {
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	// ---- NokiaPage ----

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
