package ru.playsoftware.j2meloader.nokia;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;

import io.github.cctyl.nokia.common.log.KeydroidxLog;

/**
 * root 直执工具（4.4 + SuperSU 2.76 实测可靠版）。
 * <p>
 * <b>为什么不用 libsu：</b>2026-09-26 真机（Nokia 4.4.4 + SuperSU v2.76）实测，
 * libsu 5.2.2 的持久 root shell 能建成功（su 授权 GRANTED、标记握手通过），
 * 但 {@code Shell.newJob().add(script).exec()} 提交多语句脚本后<strong>标记回显永远收不到</strong>，
 * exec() 无限期挂起（脚本实际已执行，服务端都起来了）——root 激活页因此永远停在
 * 「正在通过 root 激活...」。而 {@code Runtime.exec("su -c ...")} 模式在同设备上
 * （adb 与 App 内均已验证）秒级返回，SuperSU 策略为 grant 时无弹窗直通。
 * <p>
 * 用法：脚本写入 cache 目录（root 可读），{@code su -c sh <file>} 执行，
 * 后台线程排空 stdout/stderr（防管道阻塞），{@link Process#waitFor(long)} 不存在
 * （API 26+），故用「join 等待线程 + destroy 兜底」实现超时。
 */
public final class KeydroidxRootShell {

	private static final String TAG = "KeydroidxRootShell";

	public static class Result {
		public final int code;
		public final String out;

		Result(int code, String out) {
			this.code = code;
			this.out = out;
		}

		public boolean isSuccess() {
			return code == 0;
		}
	}

	private KeydroidxRootShell() {
	}

	/**
	 * 以 root 执行一段 shell 脚本。
	 *
	 * @param script    完整脚本文本（多行也行）
	 * @param timeoutMs 超时（毫秒）；超时后 destroy 进程并返回 code=-1
	 */
	public static Result exec(Context context, String script, long timeoutMs) {
		File f = null;
		Process p = null;
		try {
			f = new File(context.getCacheDir(), "ki_root_" + System.currentTimeMillis() + ".sh");
			FileWriter w = new FileWriter(f);
			w.write(script);
			w.close();

			// 脚本路径拼入命令前，避免 su -c 的引号嵌套问题
			p = Runtime.getRuntime().exec(new String[]{"su", "-c", "sh " + f.getAbsolutePath()});

			StreamDrainer outDrainer = new StreamDrainer(p.getInputStream());
			StreamDrainer errDrainer = new StreamDrainer(p.getErrorStream());
			outDrainer.start();
			errDrainer.start();

			// 写端先关：脚本不依赖 stdin；保持打开会让某些 su 等待 stdin 而不退出
			try {
				p.getOutputStream().close();
			} catch (Exception ignored) {
			}

			long deadline = System.currentTimeMillis() + timeoutMs;
			while (outDrainer.isAlive() || errDrainer.isAlive()) {
				if (System.currentTimeMillis() > deadline) {
					KeydroidxLog.w(TAG, "root 执行超时(" + timeoutMs + "ms)，destroy 进程");
					p.destroy();
					outDrainer.join(2000);
					errDrainer.join(2000);
					return new Result(-1, outDrainer.text.toString() + errDrainer.text);
				}
				Thread.sleep(50);
			}
			int code = p.waitFor();
			return new Result(code, outDrainer.text.toString() + errDrainer.text);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			if (p != null) p.destroy();
			return new Result(-1, "interrupted");
		} catch (Throwable t) {
			KeydroidxLog.w(TAG, "root 执行异常: " + t.getMessage());
			if (p != null) p.destroy();
			return new Result(-1, "error: " + t.getMessage());
		} finally {
			if (f != null) {
				// 删除失败不影响主流程（cache 会由系统清理）
				f.delete();
			}
		}
	}

	/** 轻量探测：root 通道是否可用（su 可执行且授权）。带 5 秒超时防挂起。 */
	public static boolean isRootAvailable(Context context) {
		Result r = exec(context, "true", 5000);
		return r.isSuccess();
	}

	/** 逐行排空进程输出，防止管道缓冲区满导致子进程写阻塞。 */
	private static class StreamDrainer extends Thread {
		final BufferedReader reader;
		final StringBuilder text = new StringBuilder();

		StreamDrainer(java.io.InputStream is) {
			super("ki-root-drain");
			setDaemon(true);
			reader = new BufferedReader(new InputStreamReader(is), 8192);
		}

		@Override
		public void run() {
			try {
				String line;
				while ((line = reader.readLine()) != null) {
					synchronized (text) {
						if (text.length() < 64 * 1024) {
							text.append(line).append('\n');
						}
					}
				}
			} catch (Exception ignored) {
			} finally {
				try {
					reader.close();
				} catch (Exception ignored) {
				}
			}
		}
	}
}
