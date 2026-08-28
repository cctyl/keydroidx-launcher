package ru.playsoftware.j2meloader.nokia;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.cctyl.nokia.common.log.NokiaLog;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import ru.playsoftware.j2meloader.R;

/**
 * mini_shizuku → adb 激活说明页。
 * <p>
 * 脚本 {@code mini_shizuku.sh} 已随 APK 内置在 {@code assets/} 中，<b>无需用户单独下载</b>。
 * 进入本页时把脚本释放到应用私有外存目录 {@code getExternalFilesDir()}（即
 * {@code /sdcard/Android/data/&lt;包名&gt;/files/mini_shizuku.sh}），并据此动态拼出一条完整
 * 的 adb 命令展示给用户：
 * <pre>
 *   adb shell sh /sdcard/Android/data/&lt;包名&gt;/files/mini_shizuku.sh
 * </pre>
 * 选用 {@code getExternalFilesDir()} 的原因：
 * <ul>
 *   <li>app 无需任何权限即可写（API 19+，4.4 兼容）；</li>
 *   <li>adb shell（uid 2000，属 {@code sdcard_r}/{@code sdcard_rw} 组）能读、能 {@code sh}
 *       该路径下的文件（实测 4.4 可行）；外存挂载不支持 {@code chmod}，但 {@code sh 脚本}
 *       只需读权限，不依赖执行位；</li>
 *   <li>路径可预期（包名固定），命令可由 app 直接拼出供用户复制。</li>
 * </ul>
 * 继承 {@link NokiaScrollPageFragment}，支持方向键平滑滚动；左软键「复制」把命令写入系统剪贴板；右软键「返回」。
 */
public class ShizukuAdbFragment extends NokiaScrollPageFragment {

	/** assets 中的脚本文件名。 */
	private static final String ASSET_SCRIPT = "mini_shizuku.sh";
	/** 释放到外存后的脚本文件名。 */
	private static final String TARGET_SCRIPT_NAME = "mini_shizuku.sh";

	private TextView tvCommand;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_shizuku_adb;
	}

	@Override
	protected void onScrollPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		tvCommand = view.findViewById(R.id.tvAdbCommand);
		// 释放脚本并填充命令。释放涉及 IO，放后台线程避免阻塞 UI。
		final Context ctx = requireContext().getApplicationContext();
		new Thread(new Runnable() {
			@Override
			public void run() {
				final String cmd = buildCommand(ctx);
				if (cmd == null) {
					NokiaLog.e("ShizukuAdb", "释放脚本失败，命令不可用");
					return;
				}
				if (tvCommand != null) {
					tvCommand.post(new Runnable() {
						@Override
						public void run() {
							if (tvCommand != null) tvCommand.setText(cmd);
						}
					});
				}
			}
		}, "shizuku-adb-extract").start();
		NokiaLog.i("ShizukuAdb", "adb 激活说明页初始化完成");
	}

	/**
	 * 把 assets 内的脚本释放到 {@code getExternalFilesDir()}，返回拼好的 adb 命令。
	 * 失败时返回 {@code null}。必须在后台线程调用（含 IO）。
	 */
	private String buildCommand(Context ctx) {
		File target = extractScript(ctx);
		if (target == null) {
			return null;
		}
		// getExternalFilesDir 在 /sdcard/Android/data/<pkg>/files 下；
		// 用 /sdcard 前缀比 /storage/emulated/0 更短、老设备兼容性更好。
		String pkg = ctx.getPackageName();
		return "adb shell sh /sdcard/Android/data/" + pkg + "/files/" + TARGET_SCRIPT_NAME;
	}

	/**
	 * 从 assets 释放 {@link #ASSET_SCRIPT} 到 {@link Context#getExternalFilesDir(String)}。
	 * 每次进入页面都覆盖写一次，保证脚本随 APK 升级后保持最新。返回目标文件，失败返回 {@code null}。
	 */
	private File extractScript(Context ctx) {
		File dir = ctx.getExternalFilesDir(null);
		if (dir == null) {
			NokiaLog.e("ShizukuAdb", "外存不可用，无法释放脚本");
			return null;
		}
		File target = new File(dir, TARGET_SCRIPT_NAME);
		AssetManager am = ctx.getAssets();
		InputStream in = null;
		OutputStream out = null;
		try {
			in = am.open(ASSET_SCRIPT);
			out = new FileOutputStream(target);
			byte[] buf = new byte[4096];
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
			}
			out.flush();
			NokiaLog.i("ShizukuAdb", "脚本已释放: " + target.getAbsolutePath()
					+ " (" + target.length() + "B)");
			return target;
		} catch (IOException e) {
			NokiaLog.e("ShizukuAdb", "释放脚本失败", e);
			return null;
		} finally {
			if (in != null) {
				try { in.close(); } catch (IOException ignored) {}
			}
			if (out != null) {
				try { out.close(); } catch (IOException ignored) {}
			}
		}
	}

	/** 把当前命令文本复制到系统剪贴板。 */
	private void copyCommand() {
		if (tvCommand == null) return;
		CharSequence text = tvCommand.getText();
		if (text == null || text.length() == 0) {
			Toast.makeText(requireContext(), "命令尚未就绪", Toast.LENGTH_SHORT).show();
			return;
		}
		try {
			ClipboardManager cm = (ClipboardManager) requireContext()
					.getSystemService(Context.CLIPBOARD_SERVICE);
			if (cm != null) {
				cm.setPrimaryClip(ClipData.newPlainText("mini_shizuku", text));
				Toast.makeText(requireContext(), "命令已复制", Toast.LENGTH_SHORT).show();
				NokiaLog.i("ShizukuAdb", "命令已复制到剪贴板");
			} else {
				Toast.makeText(requireContext(), "剪贴板不可用", Toast.LENGTH_SHORT).show();
			}
		} catch (Exception e) {
			NokiaLog.e("ShizukuAdb", "复制命令失败", e);
			Toast.makeText(requireContext(), "复制失败", Toast.LENGTH_SHORT).show();
		}
	}

	// ---- NokiaFocusHost ----

	@Override
	public boolean onSoftLeft() {
		copyCommand();
		return true;
	}

	// ---- NokiaPage ----

	@Override
	public String getPageTitle() {
		return "adb 激活";
	}

	@Override
	public String getSoftLeftText() {
		return "复制";
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}
}
