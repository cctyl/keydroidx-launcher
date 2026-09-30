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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.mini_shizuku.Shizuku;

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
	 * 以 root 身份拉起 mini_shizuku 服务端（先杀旧 app_process，再启动新服务端）。
	 * <p>
	 * 脚本构建与执行统一在 {@link KeydroidxShizukuActivator#execRootStartScript(Context)}
	 * ——本类原先自己复制了一份，两份脚本各自漂移正是 1.3.2「服务端日志写死 /data/local/tmp、
	 * 启动失败却毫无现场」能长期存在的原因之一，故合并为一处。
	 */
	private boolean startServerAsRoot() {
		return KeydroidxShizukuActivator.execRootStartScript(
				requireContext().getApplicationContext());
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
	 *   <li>{@code wlog=} —— 与启动脚本同一份候选表里，哪个路径真的能写；为 {@code none}
	 *       即说明「服务端日志无处可写」，这正是 app_process 因重定向失败而根本不启动的形态；</li>
	 *   <li>{@code tail=} —— 服务端实际用的那份日志（第一个非空候选）的最后两行
	 *       （app_process 崩溃栈、BindException、EACCES 都在这里）；</li>
	 *   <li>详述：app_process 全表、每个候选日志的 tail 80、
	 *       logcat 里的 MiniShizuku（服务端 Java 层错误只进 logcat，不落日志文件）。</li>
	 * </ul>
	 * 根 shell 不可用时只记一行；任何情况都不抛异常。
	 */
	private void collectActivationDiagnostics() {
		try {
			// 与启动脚本共用同一份候选表，避免「诊断查的路径」和「启动写的路径」不是一回事
			String cands = KeydroidxShizukuActivator.logPathCandidatesShell(requireContext());
			String diag = "echo '=== SUMMARY ==='; "
					// 进程是否还在 + 端口是否被占（10500 = 0x2904，/proc/net/tcp 里 st=0A 为 LISTEN）+ SELinux
					+ "echo \"proc=$( (ps -A 2>/dev/null || ps) | grep -c app_process ) "
					+ "listen10500=$(cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep ':2904 ' | grep -c ' 0A ') "
					+ "enforce=$(getenforce 2>&1)\"; "
					// W=第一个真能写的候选（真实建文件探针，不用 [ -w ]：root 的 DAC 判定会掩盖 SELinux 拒绝）
					// F=第一个非空的候选，即服务端实际用的那份日志。
					// wlog 只回显候选序号 c1/c2/c3（完整路径在详述块里给出）——上报注释只有 160 字节，
					// 一个 /storage/emulated/0/Android/data/... 的完整路径就能吃掉 80 字节，
					// 会把真正的原因（tail=）挤出可视范围。
					+ "W=none; F=none; i=0; for c in " + cands + "; do i=$((i+1)); "
					+ "if [ \"$W\" = none ] && ( : >> \"$c\" ) 2>/dev/null; then W=\"c$i\"; fi; "
					+ "if [ \"$F\" = none ] && [ -s \"$c\" ]; then F=\"$c\"; fi; done; "
					+ "echo \"wlog=$W\"; "
					// tail 是决定性的那一行，紧跟在 wlog 之后（只 tail 单个文件，不带多文件 ==> 头）
					+ "echo \"tail=$(tail -n 2 \"$F\" 2>&1 | while read -r l; do printf '%s | ' \"$l\"; done)\"; "
					+ "echo '=== app_process procs ==='; "
					+ "(ps -A 2>/dev/null || ps) | grep -i app_process; "
					+ "echo '=== server log candidates (#n 序号对应 wlog=cn) ==='; "
					+ "i=0; for c in " + cands + "; do i=$((i+1)); echo \"#$i $c\"; ls -l \"$c\" 2>&1; tail -n 80 \"$c\" 2>&1; done; "
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
	 * <p>
	 * 源按启动脚本的候选顺序取第一个<strong>非空</strong>的：历史位置
	 * {@code /data/local/tmp/minishizuku*.log}，以及 App 自身日志目录下的
	 * {@code minishizuku_server.log}（后者已在日志目录里，随上报 zip 一起走）。
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
		String appLog = KeydroidxShizukuActivator.appLogCandidatePath(requireContext());
		// -s：源为空（0 字节）也算失败，免得把空文件当成功（空文件恰好还会被 zip 的 size>0 过滤掉）
		String cmd = "for s in /data/local/tmp/minishizuku.log /data/local/tmp/minishizuku.*.log '"
				+ appLog + "'; do if [ -s \"$s\" ]; then cp -f \"$s\" " + targetPath
				+ " 2>&1; break; fi; done; ls -l " + targetPath + " 2>&1";
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
