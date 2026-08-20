package ru.playsoftware.j2meloader.nokia;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * 本地指令服务器（native 拦截器 → App 快速通道）。
 * <p>
 * 监听 127.0.0.1:10501，native 拦截器通过 socket 直连发送指令，
 * 避免 am broadcast / am start 的进程创建 + Binder IPC 开销（~300ms → ~5ms）。
 * <p>
 * 支持的指令：
 * <ul>
 *   <li>"LOCK" → {@link NokiaLockScreen#lock(Context)} 执行 Device Admin 锁屏</li>
 *   <li>"HOME" → startActivity 拉起原键桌面到前台并触发 goHome() 回到待机屏</li>
 * </ul>
 * <p>
 * 生命周期：由 {@link NokiaDesktopActivity} 在 onCreate 启动、onDestroy 停止。
 */
public class NokiaLockServer {

	private static final String TAG = "NokiaLockServer";
	private static final int PORT = 10501;
	private static final Charset UTF8 = Charset.forName("UTF-8");
	private static final String NOKIA_ACTIVITY =
			"ru.playsoftware.j2meloader.nokia.NokiaDesktopActivity";

	private final Context context;
	private ServerSocket serverSocket;
	private Thread listenThread;
	private volatile boolean running = false;

	public NokiaLockServer(Context context) {
		this.context = context.getApplicationContext();
	}

	/** 启动监听。已运行时忽略。 */
	public void start() {
		if (running) return;
		try {
			serverSocket = new ServerSocket();
			serverSocket.bind(new InetSocketAddress("127.0.0.1", PORT));
			running = true;
			listenThread = new Thread(this::listenLoop, "NokiaLockServer");
			listenThread.start();
			NokiaLog.i(TAG, "监听已启动 127.0.0.1:" + PORT);
		} catch (IOException e) {
			NokiaLog.e(TAG, "启动监听失败", e);
		}
	}

	/** 停止监听。 */
	public void stop() {
		running = false;
		if (serverSocket != null) {
			try {
				serverSocket.close();
			} catch (IOException ignored) {
			}
			serverSocket = null;
		}
		if (listenThread != null) {
			listenThread.interrupt();
			listenThread = null;
		}
		NokiaLog.i(TAG, "监听已停止");
	}

	private void listenLoop() {
		while (running) {
			Socket client = null;
			try {
				client = serverSocket.accept();
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(client.getInputStream(), UTF8));
				String line = reader.readLine();
				if (line != null) {
					line = line.trim();
					NokiaLog.i(TAG, "收到指令: " + line);
					handleCommand(line);
				}
			} catch (IOException e) {
				if (running) {
					NokiaLog.e(TAG, "accept 失败", e);
				}
			} finally {
				if (client != null) {
					try {
						client.close();
					} catch (IOException ignored) {
					}
				}
			}
		}
	}

	private void handleCommand(String cmd) {
		switch (cmd) {
			case "LOCK":
				NokiaLockScreen.lock(context);
				break;
			case "HOME":
				goHome();
				break;
			default:
				NokiaLog.w(TAG, "未知指令: " + cmd);
				break;
		}
	}

	/**
	 * 拉起原键桌面到前台并回到待机屏。
	 * 用 HOME intent + 显式组件：触发 onNewIntent → goHome() → 清空返回栈 → 待机屏。
	 * FLAG_ACTIVITY_NEW_TASK | CLEAR_TOP 确保已在栈顶时也触发 onNewIntent。
	 */
	private void goHome() {
		Intent intent = new Intent(Intent.ACTION_MAIN);
		intent.addCategory(Intent.CATEGORY_HOME);
		intent.setClassName(context, NOKIA_ACTIVITY);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		try {
			context.startActivity(intent);
			NokiaLog.i(TAG, "HOME: startActivity 已发送");
		} catch (Exception e) {
			NokiaLog.e(TAG, "HOME: startActivity 失败", e);
		}
	}
}
