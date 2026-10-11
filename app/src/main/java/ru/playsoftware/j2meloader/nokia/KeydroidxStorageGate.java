package ru.playsoftware.j2meloader.nokia;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.hjq.permissions.OnPermissionCallback;

import java.io.File;
import java.util.Collections;
import java.util.List;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import io.github.cctyl.nokia.common.permission.KeydroidxPermissionManager;
import io.github.cctyl.nokia.common.ui.dialog.KeydroidxConfirmDialog;
import ru.playsoftware.j2meloader.appsdb.AppRepository;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.util.FileUtils;

/**
 * 「工作目录（存储）是否可用」的统一门禁，供所有 JAR 安装入口与百宝箱共用。
 *
 * <p><b>为什么需要它</b>：J2ME 工作目录位于外部存储，而存储权限按设计是首次使用相关功能时
 * 才申请的（见 {@code KeydroidxPermissionManager} 核心权限全集说明）。权限授予晚于进程启动，
 * 于是仓库（{@link AppRepository}）可能长期停在「未初始化」状态：
 * <ul>
 *   <li>不做检查直接安装 → 文件已落盘但清单未登记，界面还显示「安装成功」（假成功）；</li>
 *   <li>或者读到空记录后紧接着使用 → 空指针。</li>
 * </ul>
 * 本类把「判断是否需要申请权限 / 申请并重建工作目录 / 最终不可用时提示重启」收敛为单一事实来源，
 * 避免百宝箱与安装弹窗两处各自实现后行为漂移。
 */
public final class KeydroidxStorageGate {

	/** 日志来源标识：类简名去 Keydroidx 前缀，长度须 ≤23 字符（AGENTS.md 统一日志规范）。 */
	private static final String TAG = "StorageGate";

	/** 存储权限申请说明文案（百宝箱与安装弹窗保持一致）。 */
	private static final String PERMISSION_TITLE = "存储权限申请";
	private static final String PERMISSION_MESSAGE =
			"运行 J2ME 应用需要读写手机存储权限：用于读取安装包，并把应用数据写入模拟器工作目录。";

	private KeydroidxStorageGate() {
	}

	/**
	 * 当前系统是否需要「存储读写」运行时权限才能访问外部工作目录。
	 *
	 * <p>API 23 以下无运行时权限机制（安装即授予）；API 30（Android 11）起分区存储强制执行，
	 * 该权限已失效、安装改走 SAF（见 {@code FileUtils.isExternalStorageLegacy()}），
	 * 因此这两段区间都无需申请。
	 */
	public static boolean needsStoragePermission(Context context) {
		if (context == null) {
			return false;
		}
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
				|| Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			return false;
		}
		return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
				!= PackageManager.PERMISSION_GRANTED;
	}

	/**
	 * 幂等检查：先让仓库自愈一次（权限可能刚在别处授予、或目录刚变为可写），仍不可用返回 false。
	 *
	 * @return true 表示工作目录与清单数据库都已可用，可以安全读写
	 */
	public static boolean isWorkDirReady(@Nullable AppRepository repository) {
		if (repository == null) {
			return false;
		}
		repository.ensureReady();
		return repository.isReady();
	}

	/**
	 * 申请「存储读写」权限，并在授予后重建工作目录、重新初始化数据库，最后回调就绪结果。
	 *
	 * <p>三条分支：
	 * <ol>
	 *   <li>授予且目录可写 → {@code onReady}（调用方通常继续原安装流程，无需重启应用）；</li>
	 *   <li>授予但本进程仍未拿到可写视图（个别 ROM 不刷新存储视图）→ 直接弹「无法访问存储」并支持一键重启；</li>
	 *   <li>拒绝 → {@code onDenied}（调用方自行决定提示方式）。</li>
	 * </ol>
	 *
	 * @param onReady  授权且目录可用后的继续动作，可为 null
	 * @param onDenied 被拒绝后的动作，可为 null
	 */
	public static void requestStoragePermission(@NonNull Activity activity,
												@Nullable AppRepository repository,
												@Nullable Runnable onReady,
												@Nullable Runnable onDenied) {
		KeydroidxLog.i(TAG, "存储权限缺失，发起申请");
		KeydroidxPermissionManager.requestWithNokiaDialog(activity,
				PERMISSION_TITLE,
				PERMISSION_MESSAGE,
				Collections.singletonList(Manifest.permission.WRITE_EXTERNAL_STORAGE),
				new OnPermissionCallback() {
					@Override
					public void onGranted(@NonNull List<String> permissions, boolean allGranted) {
						KeydroidxLog.i(TAG, "存储权限已授予，重建工作目录并重新初始化数据库");
						rebuildWorkDir(activity);
						if (repository != null) {
							repository.ensureReady();
						}
						if (repository == null || repository.isReady()) {
							if (onReady != null) {
								onReady.run();
							}
						} else {
							// 授权已生效但当前进程仍未拿到可写视图：只能靠重启进程
							showUnavailableDialog(activity, () -> restartApp(activity));
						}
					}

					@Override
					public void onDenied(@NonNull List<String> permissions, boolean quick) {
						KeydroidxLog.w(TAG, "存储权限被拒绝，无法访问工作目录（quick=" + quick + "）");
						if (onDenied != null) {
							onDenied.run();
						}
					}
				});
	}

	/**
	 * 重建工作目录：权限刚授予时，目录的读写视图可能在本进程内尚未生效，需重新初始化一次。
	 * <p>失败只记 w（属预期内：权限可能仍不可用），后续由 {@link #isWorkDirReady} 复检兜底。
	 */
	private static void rebuildWorkDir(@Nullable Context context) {
		String emulatorDir = Config.getEmulatorDir();
		if (emulatorDir == null) {
			return;
		}
		if (!FileUtils.initWorkDir(new File(emulatorDir))) {
			KeydroidxLog.w(TAG, "重建工作目录失败（可能仍无写权限）: " + emulatorDir);
		}
	}

	/**
	 * 工作目录最终仍不可写时的显式提示。
	 * <p>兜底路径：部分 ROM 在授予存储权限后不刷新已启动进程的存储视图，
	 * 此时只能靠重启进程生效——提供一键重启，避免让用户自己去猜「为什么要重启」。
	 *
	 * @param onRestart 一键重启动作，通常传 {@code () -> restartApp(activity)}
	 */
	public static void showUnavailableDialog(@NonNull Activity activity, @Nullable Runnable onRestart) {
		if (activity.isFinishing()) {
			return;
		}
		String path = Config.getEmulatorDir();
		KeydroidxLog.w(TAG, "工作目录不可写，JAR 列表/安装均不可用: " + path);
		new KeydroidxConfirmDialog(activity, "无法访问存储",
				"无法写入模拟器工作目录：\n" + path
						+ "\n\n若刚刚授予了存储权限，需要重新启动应用才会生效。\n确定立即重启应用吗？")
				.setPositiveButton("立即重启", () -> {
					if (onRestart != null) {
						onRestart.run();
					}
				})
				.setNegativeButton("稍后", null)
				.show();
	}

	/** 重新拉起自身并结束当前进程，使新的存储视图/权限在进程启动时生效。 */
	public static void restartApp(Context context) {
		if (context == null) {
			return;
		}
		KeydroidxLog.i(TAG, "重启应用以使存储权限生效");
		try {
			Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
			if (intent != null) {
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
				context.startActivity(intent);
			}
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "重新拉起应用失败: " + e.getMessage());
		}
		android.os.Process.killProcess(android.os.Process.myPid());
	}
}
