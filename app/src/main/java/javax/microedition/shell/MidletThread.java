/*
 *  Copyright 2020 Yury Kharchenko
 *  Copyright 2022-2023 Arman Jussupgaliyev
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package javax.microedition.shell;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;
import javax.microedition.util.ContextHolder;

import androidx.annotation.NonNull;

import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.util.MidletStateStore;

public class MidletThread extends HandlerThread implements Handler.Callback {
	private static final String TAG = MidletThread.class.getName();
	private static final UncaughtExceptionHandler uncaughtExceptionHandler = (t, e) ->
			Log.e(TAG, "Error in thread: \"" + t + "\" after destroy app called", e);

	private static final int INIT = 0;
	private static final int START = 1;
	private static final int PAUSE = 2;
	private static final int DESTROY = 3;
	private static final int UNINITIALIZED = 0;
	private static final int STARTED = 1;
	private static final int PAUSED = 2;
	private static final int DESTROYED = 3;
	public static String[] startAfterDestroy;
	private static MidletThread instance;

	// ---- 挂机状态载体（:midlet 进程内静态，Activity 重建后复用） ----
	/** 当前运行/挂机的 MIDlet appPath（分支判定与状态文件内容来源） */
	static volatile String runningAppPath;
	/** 挂机前最后一刻的 Displayable（Activity 重建后重挂 UI 用） */
	static volatile Displayable currentDisplayable;
	/** 首次加载时保存的屏幕方向（跳过 microLoader.init() 后无来源） */
	static volatile int savedOrientation = -1;
	/** 首次加载时保存的菜单键码 */
	static volatile int savedMenuKey = 0;

	private final MicroLoader microLoader;
	private final String mainClass;
	private MIDlet midlet;
	private final Handler handler;
	private int state;

	private MidletThread(MicroLoader microLoader, String mainClass) {
		super("MidletMain");
		this.microLoader = microLoader;
		this.mainClass = mainClass;
		start();
		handler = new Handler(getLooper(), this);
		handler.obtainMessage(INIT).sendToTarget();
	}

	static void create(MicroLoader microLoader, String mainClass) {
		instance = new MidletThread(microLoader, mainClass);
	}

	/**
	 * 是否存在可复用的 MIDlet 实例（未在销毁中）。
	 * state==DESTROYED 表示销毁流程进行中、进程即将死亡，不可复用。
	 */
	public static boolean hasInstance() {
		return instance != null && instance.state != DESTROYED;
	}

	/** 当前运行/挂机的 MIDlet appPath；无实例返回 null。 */
	public static String getRunningAppPath() {
		return hasInstance() ? runningAppPath : null;
	}

	/** 挂机前最后一刻的 Displayable（Activity 重建后重挂 UI 用）；无实例返回 null。 */
	public static Displayable getCurrentDisplayable() {
		return hasInstance() ? currentDisplayable : null;
	}

	/** 首次加载保存的屏幕方向；未保存返回 -1。 */
	public static int getSavedOrientation() {
		return savedOrientation;
	}

	/** 首次加载保存的菜单键码。 */
	public static int getSavedMenuKey() {
		return savedMenuKey;
	}

	public static void notifyDestroyed() {
		Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
		if (instance != null) {
			instance.state = DESTROYED;
		}
		MicroActivity activity = ContextHolder.getActivity();
		// 清挂机状态文件（须在切换启动之后、killProcess 之前：切换场景新 jar 会随后覆写）
		if (startAfterDestroy != null) {
			Config.startApp(ContextHolder.getActivity(), startAfterDestroy[0], startAfterDestroy[1], false, startAfterDestroy[2]);
		}
		runningAppPath = null;
		currentDisplayable = null;
		try {
			MidletStateStore.clear(ContextHolder.getAppContext());
		} catch (Throwable t) {
			Log.w(TAG, "clear midlet state failed", t);
		}
		if (activity != null) {
			activity.finish();
		}
		Process.killProcess(Process.myPid());
	}

	public static void notifyPaused() {
		instance.state = PAUSED;
	}

	static void pauseApp() {
		if (instance != null)
			instance.handler.obtainMessage(PAUSE).sendToTarget();
	}

	public static void resumeApp() {
		MicroActivity activity = ContextHolder.getActivity();
		if (instance != null && activity != null && activity.isVisible())
			instance.handler.obtainMessage(START).sendToTarget();
	}

	/** 优雅销毁（公开给 nokia 包清除通道）：END 键 → destroyApp(true) → notifyDestroyed（含 1s 强杀兜底）。 */
	public static void destroyApp() {
		Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
		new Thread(() -> {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			Process.killProcess(Process.myPid());
		}, "ForceDestroyTimer").start();
		MicroActivity activity = ContextHolder.getActivity();
		Displayable current = activity != null ? activity.getCurrent() : null;
		if (current == null) {
			// 切换场景：ContextHolder 已指向新建的 Activity（current 为空），回退到挂机状态载体
			current = currentDisplayable;
		}
		if (current instanceof Canvas) {
			Canvas canvas = (Canvas) current;
			canvas.postKeyPressed(Canvas.KEY_END);
			canvas.postKeyReleased(Canvas.KEY_END);
		}
		if (instance != null) {
			instance.handler.obtainMessage(DESTROY).sendToTarget();
		}
	}

	@Override
	public boolean handleMessage(@NonNull Message msg) {
		switch (msg.what) {
			case INIT:
				if (state != UNINITIALIZED) {
					break;
				}
				try {
					midlet = microLoader.loadMIDlet(this.mainClass);
					state = PAUSED;
				} catch (Throwable t) {
					throw new RuntimeException("Init midlet failed", t);
				}
				break;
			case START:
				if (state != PAUSED) {
					break;
				}
				try {
					state = STARTED;
					midlet.startApp();
				} catch (MIDletStateChangeException e) {
					state = PAUSED;
					Log.w(TAG, "Midlet doesn't want to start!", e);
				} catch (Throwable t) {
					state = DESTROYED;
					throw new RuntimeException("Failed startApp", t);
				}
				break;
			case PAUSE:
				if (state != STARTED) {
					break;
				}
				try {
					midlet.pauseApp();
					state = PAUSED;
				} catch (Throwable t) {
					state = DESTROYED;
					try {
						midlet.destroyApp(true);
					} catch (MIDletStateChangeException ignored) {}
					throw new RuntimeException("Filed pauseApp", t);
				}
				break;
			case DESTROY:
				if (state == DESTROYED) {
					notifyDestroyed();
					break;
				}
				state = DESTROYED;
				try {
					midlet.destroyApp(true);
				} catch (MIDletStateChangeException e) {
					Log.w(TAG, "Midlet didn't want to die!", e);
				} catch (Throwable t) {
					Log.e(TAG, "Filed destroyApp:", t);
				}
				notifyDestroyed();
				break;
		}
		return true;
	}
}
