package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

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
 * <p>
 * <b>{@code EADDRINUSE} 的三个来源与处置</b>（历史故障：1.3.2 自动上报
 * 「KeydroidxLockServer: 启动监听失败 / java.net.BindException」）：
 * <ol>
 *   <li><b>同进程重复 bind</b>：修复前本类按 Activity 实例创建、{@code running} 也是实例
 *       字段，桌面 Activity 被重建（内存压力、未被 {@code configChanges} 覆盖的配置变更等）
 *       时，新实例会与尚未释放的旧监听 socket 撞固定端口 10501（Android 不保证
 *       {@code old.onDestroy} 先于 {@code new.onCreate} 完成）。已由进程级单例 +
 *       幂等 {@link #start(Context)} 消除。</li>
 *   <li><b>端口残留（TIME_WAIT）/ 旧进程尚未退场</b>：本服务处理完指令会主动关闭已连接
 *       socket（{@link #listenLoop()} 的 finally），与拦截器「谁先 close 谁进 TIME_WAIT」
 *       存在竞争，我们赢下竞争时该连接会在<b>本地端口 10501</b> 上停留 TIME_WAIT（Linux
 *       约 60s）；而 {@code java.net.ServerSocket} 默认不开 {@code SO_REUSEADDR}，
 *       此窗口内 bind 会被内核判为地址占用（应用自更新、被系统回收后立刻重启最容易落进
 *       这个窗口）。已由 bind 前的 {@code SO_REUSEADDR} + {@link #RETRY_DELAYS_MS 退避重试}
 *       覆盖。</li>
 *   <li><b>端口被别的进程 LISTEN 强占</b>（设备上无关应用、或同机共存的 debug/release
 *       两个副本都监听 10501）：{@code SO_REUSEADDR} 与重试都无法绕过——这是外部占用，
 *       只能靠 native 拦截器的 {@code am broadcast}/{@code am start} 兜底降级（功能不中断，
 *       只是慢路径）。真机排查见 {@code cat /proc/net/tcp* | grep 2905}
 *       （10501 = 0x2905）。</li>
 * </ol>
 */
public class KeydroidxLockServer {

	private static final String TAG = "KeydroidxLockServer";
	private static final int PORT = 10501;
	private static final Charset UTF8 = Charset.forName("UTF-8");

	/**
	 * bind 失败后的退避重试间隔（毫秒）。
	 * <p>
	 * 端口被「正在退场的旧进程」短暂占住是真实且常见的情形（应用自更新、被系统回收后
	 * 立刻重建），等一会儿就能绑上；重试成功则快速通道（~5ms）恢复，不必等到下次
	 * Activity onCreate。三次都失败即永久降级走 native 拦截器的 {@code am} 兜底，
	 * 不再打扰（避免高频 bind 与日志刷屏）。
	 */
	private static final long[] RETRY_DELAYS_MS = {2000L, 10000L, 30000L};

	/** 退避重试调度器（只在主线程 Looper 上投递，重试本身也是主线程的轻量 bind 调用）。 */
	private static final Handler RETRY_HANDLER = new Handler(Looper.getMainLooper());

	/** 进程级单例。volatile 保证 start()/stop() 间的可见性。 */
	private static volatile KeydroidxLockServer INSTANCE;

	/** 重试所需的 ApplicationContext（崩溃/重试路径上不持有 Activity，避免内存泄漏）。 */
	private static Context sAppContext;
	/** 已消耗的重试次数；bind 成功即清零，因此重试总数在进程内是有限的。 */
	private static int sRetryIndex = 0;

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
		// 重试需要跨 Activity 生命周期持有 Context，统一收敛为 ApplicationContext
		Context app = context.getApplicationContext();
		sAppContext = app != null ? app : context;

		KeydroidxLockServer server = new KeydroidxLockServer(sAppContext);
		ServerSocket socket = null;
		try {
			socket = new ServerSocket();
			// 必须在 bind 之前开启：应用自更新 / 被系统回收后快速重启时，上一进程在本地端口
			// 10501 上遗留的 TIME_WAIT 连接会让 bind 报 EADDRINUSE（默认 SO_REUSEADDR=0）。
			// 它只放行 TIME_WAIT 残留，对真正仍处于 LISTEN 的占用者无效，因此不会掩盖真实冲突。
			socket.setReuseAddress(true);
			socket.bind(new InetSocketAddress("127.0.0.1", PORT));
			server.serverSocket = socket;
			server.running = true;
			INSTANCE = server;
			sRetryIndex = 0;
			server.listenThread = new Thread(server::listenLoop, "KeydroidxLockServer");
			server.listenThread.start();
			KeydroidxLog.i(TAG, "监听已启动 127.0.0.1:" + PORT);
		} catch (IOException e) {
			// 端口被占用（上一进程尚未退场 / 端口残留在 TIME_WAIT / 被别的应用强占）：
			// native 拦截器有 am 兜底，功能降级而非致命，记 w 不触发上报。
			// 已创建但未 bind 成功的 socket 必须显式关闭，否则 fd 要等 GC 才释放。
			closeQuietly(socket);
			KeydroidxLog.w(TAG, "启动监听失败（端口被占用，降级走 am 兜底）: " + e.getMessage());
			scheduleRetry();
		}
	}

	/**
	 * 端口被短暂占住时按 {@link #RETRY_DELAYS_MS} 退避重试绑定。
	 * <p>
	 * 只在「单例尚未就绪」时重试，且 {@link #sRetryIndex} 只在 bind 成功时清零，
	 * 因此一个进程内的 bind 尝试总数是有限的（首次 + 3 次重试），端口被无关应用永久
	 * 强占时最多再打扰 3 次就彻底安静。
	 */
	private static void scheduleRetry() {
		final Context app = sAppContext;
		if (app == null || sRetryIndex >= RETRY_DELAYS_MS.length) {
			return;
		}
		long delay = RETRY_DELAYS_MS[sRetryIndex++];
		KeydroidxLog.i(TAG, delay + "ms 后重试绑定 127.0.0.1:" + PORT
				+ "（第 " + sRetryIndex + "/" + RETRY_DELAYS_MS.length + " 次）");
		RETRY_HANDLER.postDelayed(() -> {
			if (INSTANCE == null || !INSTANCE.running) {
				start(app);
			}
		}, delay);
	}

	/** 关闭 socket 并吞掉异常（清理路径，只用 w，避免触发上报递归）。 */
	private static void closeQuietly(ServerSocket socket) {
		if (socket == null) {
			return;
		}
		try {
			socket.close();
		} catch (IOException e) {
			KeydroidxLog.w(TAG, "close failed: " + e.getMessage());
		}
	}

	/**
	 * 停止单例监听。通常<b>无需调用</b>：进程退出时 socket 由系统回收。
	 * 保留方法以备显式关闭场景（如未来在保活服务 onDestroy 中调用）。
	 */
	public static synchronized void stop() {
		// 显式停止后不允许退避重试把服务又拉起来
		RETRY_HANDLER.removeCallbacksAndMessages(null);
		sRetryIndex = 0;
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
