package ru.playsoftware.j2meloader.nokia;

import android.content.Context;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
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
 *   <li>"LOCK" → {@link KeydroidxLockScreen#lock(Context)} 执行 Device Admin 锁屏</li>
 *   <li>"HOME" → startActivity 拉起原键桌面到前台并触发 goHome() 回到待机屏</li>
 * </ul>
 * <p>
 * 生命周期：进程级单例。由 {@link KeydroidxDesktopActivity} 在 onCreate 调用
 * {@link #start(Context)} 懒启动；<b>不随 Activity 实例 onDestroy 停止</b>——
 * 端口与监听线程属于进程资源，保活服务（{@code KeydroidxDesktopKeepAliveService}，
 * {@code START_STICKY}）维持进程存活期间，即便桌面 Activity 被系统回收，native
 * 拦截器仍可下发锁屏/回桌面指令。进程退出时由系统回收 socket。
 */
public class KeydroidxLockServer {

	private static final String TAG = "KeydroidxLockServer";
	private static final int PORT = 10501;
	private static final Charset UTF8 = Charset.forName("UTF-8");

	/** 进程级单例。volatile 保证 start()/stop() 间的可见性。 */
	private static volatile KeydroidxLockServer INSTANCE;

	private final Context context;
	private ServerSocket serverSocket;
	private Thread listenThread;
	private volatile boolean running = false;

	private KeydroidxLockServer(Context context) {
		this.context = context.getApplicationContext();
	}

	/**
	 * 进程级幂等启动：保证整个进程内只绑定一次 127.0.0.1:10501。
	 * <p>
	 * 为什么做成单例：{@link KeydroidxDesktopActivity} 可能在同一进程内被销毁/重建
	 * （系统内存压力、未被 {@code configChanges} 覆盖的配置变更等）。若每次 onCreate
	 * 都 new 一份并 bind 固定端口，新实例 bind 时旧实例的监听 socket 可能尚未释放
	 * （Android 不保证 old.onDestroy 先于 new.onCreate 完成，旧 ROM 尤其飘忽），
	 * 导致 {@code EADDRINUSE}。改为进程级单例 + 幂等 start：已在运行则直接返回，
	 * 不再 bind，从根上消除同进程双实例撞端口。
	 * <p>
	 * bind 失败记 {@code w} 而非 {@code e}：端口被占用是环境性/瞬时降级，native 拦截器
	 * （{@code interceptor.c} 的 {@code inject_lock/inject_go_home}）在 socket 失败时
	 * 有 {@code am broadcast}/{@code am start} 兜底，功能不中断，仅退化为慢路径，
	 * 不应触发崩溃上报消耗每日配额。
	 *
	 * @param context 任意 Context，内部取 ApplicationContext，不随 Activity 生命周期失效
	 */
	public static synchronized void start(Context context) {
		if (INSTANCE != null && INSTANCE.running) {
			KeydroidxLog.i(TAG, "已在运行，跳过重复 bind");
			return;
		}
		KeydroidxLockServer server = new KeydroidxLockServer(context);
		try {
			server.serverSocket = new ServerSocket();
			server.serverSocket.bind(new InetSocketAddress("127.0.0.1", PORT));
			server.running = true;
			INSTANCE = server;
			server.listenThread = new Thread(server::listenLoop, "KeydroidxLockServer");
			server.listenThread.start();
			KeydroidxLog.i(TAG, "监听已启动 127.0.0.1:" + PORT);
		} catch (IOException e) {
			// 端口被占用（通常是上一进程残留或同进程异常）：native 拦截器有 am 兜底，
			// 功能降级而非致命，记 w 不触发上报。
			KeydroidxLog.w(TAG, "启动监听失败（端口被占用，降级走 am 兜底）: " + e.getMessage());
		}
	}

	/**
	 * 停止单例监听。通常<b>无需调用</b>：进程退出时 socket 由系统回收。
	 * 保留方法以备显式关闭场景（如未来在保活服务 onDestroy 中调用）。
	 */
	public static synchronized void stop() {
		KeydroidxLockServer s = INSTANCE;
		INSTANCE = null;
		if (s == null) {
			return;
		}
		s.running = false;
		if (s.serverSocket != null) {
			try {
				s.serverSocket.close();
			} catch (IOException e) {
				KeydroidxLog.w(TAG, "close failed: " + e.getMessage());
			}
			s.serverSocket = null;
		}
		if (s.listenThread != null) {
			s.listenThread.interrupt();
			s.listenThread = null;
		}
		KeydroidxLog.i(TAG, "监听已停止");
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
					KeydroidxLog.i(TAG, "收到指令: " + line);
					handleCommand(line);
				}
			} catch (IOException e) {
				if (running) {
					KeydroidxLog.w(TAG, "accept 失败: " + e.getMessage());
				}
			} finally {
				if (client != null) {
					try {
						client.close();
					} catch (IOException e) {
						KeydroidxLog.w(TAG, "close failed: " + e.getMessage());
					}
				}
			}
		}
	}

	private void handleCommand(String cmd) {
		switch (cmd) {
			case "LOCK":
				KeydroidxLockScreen.lock(context);
				break;
			case "HOME":
				KeydroidxLauncherUtils.navigateToHome(context);
				break;
			default:
				KeydroidxLog.w(TAG, "未知指令: " + cmd);
				break;
		}
	}
}
