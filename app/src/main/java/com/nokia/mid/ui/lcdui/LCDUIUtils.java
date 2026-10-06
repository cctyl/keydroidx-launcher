/*
 *  Nokia S40 v5/v6 私有 API 兼容层（com.nokia.mid.ui.lcdui）
 *  Copyright (C) 2026 KeydroidX Launcher
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

package com.nokia.mid.ui.lcdui;

import android.content.Intent;

import java.lang.ref.WeakReference;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.event.RunnableEvent;
import javax.microedition.shell.MicroActivity;
import javax.microedition.shell.MidletThread;
import javax.microedition.util.ContextHolder;

import io.github.cctyl.nokia.common.log.KeydroidxLog;

/**
 * Nokia S40 第 5/6 版私有 API：显示状态与前台切换工具类。
 * <p>
 * 该 API 仅存在于 S40 v5/v6 真机固件中（无公开 Javadoc，仅有 CHM 文档流传），
 * 但部分商用 MIDlet（如 J2ME 版微信）在 startApp 阶段直接调用它：
 * 真实设备上能跑，而模拟器缺少该类会导致 {@code NoClassDefFoundError}，
 * 最终在 {@code MidletThread} 里被包装为 {@code RuntimeException: Failed startApp} 而闪退。
 * 本类提供签名兼容的实现，使此类 MIDlet 能正常启动。
 * <p>
 * 行为映射：
 * <ul>
 *     <li>{@link #setCurrent(Display, Displayable, Alert)} 且 next 非空：委托
 *     {@link Display#setCurrent(Alert, Displayable)} / {@link Display#setCurrent(Displayable)}。</li>
 *     <li>next 为空（MIDlet 请求后台运行）：回到 HOME（原键桌面），
 *     复用 {@code MicroActivity.onStop} 已有的挂机保活链路；无 HOME 时降级为 {@code moveTaskToBack(true)}。</li>
 *     <li>前后台切换回调由 {@code MicroActivity.onPause/onResume} 调用
 *     {@link #fireDisplayState(boolean)} 触发，并派发到模拟器事件线程执行。</li>
 * </ul>
 * 所有公开方法均不抛出非受检异常：无法实现的能力安全降级并记录日志（{@code w} 级）。
 */
public final class LCDUIUtils {
	private static final String TAG = "NokiaLCDUIUtils";

	/** Display 在进程内为单例，弱引用避免持有已废弃实例 */
	private static WeakReference<Display> displayRef;
	private static DisplayStateListener displayStateListener;

	private LCDUIUtils() {
	}

	/**
	 * 注册屏幕状态监听器（前台/后台切换回调）。
	 *
	 * @param display  调用方持有的 Display 实例，可为 null
	 * @param listener 监听器，传 null 表示取消监听
	 */
	public static void setDisplayStateListener(Display display, DisplayStateListener listener) {
		displayRef = display == null ? null : new WeakReference<>(display);
		displayStateListener = listener;
	}

	/**
	 * 获取已注册的屏幕状态监听器。
	 *
	 * @param display 调用方持有的 Display 实例；传 null 时直接返回当前监听器
	 * @return 与 display 匹配的监听器，未注册或不匹配时返回 null
	 */
	public static DisplayStateListener getDisplayStateListener(Display display) {
		Display registered = displayRef == null ? null : displayRef.get();
		if (display == null) {
			return displayStateListener;
		}
		return registered == display ? displayStateListener : null;
	}

	/**
	 * 切换当前 Displayable；next 为空表示请求把 MIDlet 退到后台运行。
	 * <p>
	 * 签名兜底之一：S40 私有 API 的 Displayable/Alert 参数顺序无公开文档可查，
	 * 故同时提供与 {@link #setCurrent(Display, Alert, Displayable)} 两个重载，
	 * 无论 MIDlet 字节码引用的是哪种顺序都能链接成功（两者语义完全一致）。
	 */
	public static void setCurrent(Display display, Displayable next, Alert alert)
			throws ForegroundUnavailableException {
		setCurrentInternal(display, next, alert);
	}

	/**
	 * 切换当前 Displayable；next 为空表示请求把 MIDlet 退到后台运行。
	 * <p>
	 * 签名兜底之二，见 {@link #setCurrent(Display, Displayable, Alert)}。
	 */
	public static void setCurrent(Display display, Alert alert, Displayable next)
			throws ForegroundUnavailableException {
		setCurrentInternal(display, next, alert);
	}

	private static void setCurrentInternal(Display display, Displayable next, Alert alert) {
		try {
			if (next == null) {
				// 请求后台运行（S40 语义：MIDlet 退后台、露出手机待机界面，进程继续存活）。
				// 模拟器内实现为回到 HOME（本工程即原键桌面）；注意不能只用 moveTaskToBack，
				// 实测那会落到系统「最近任务」界面而不是桌面。
				final MicroActivity activity = ContextHolder.getActivity();
				if (activity == null) {
					KeydroidxLog.w(TAG, "setCurrent(background): host activity is null, ignored");
					return;
				}
				activity.runOnUiThread(() -> {
					try {
						Intent home = new Intent(Intent.ACTION_MAIN);
						home.addCategory(Intent.CATEGORY_HOME);
						home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
						activity.startActivity(home);
					} catch (Exception e) {
						// 无可用 HOME 的极简系统上退化为任务退后台
						KeydroidxLog.w(TAG, "start HOME failed, fallback to moveTaskToBack", e);
						try {
							activity.moveTaskToBack(true);
						} catch (Exception e2) {
							KeydroidxLog.w(TAG, "moveTaskToBack failed", e2);
						}
					}
				});
				return;
			}
			if (display == null) {
				KeydroidxLog.w(TAG, "setCurrent: display is null, ignored");
				return;
			}
			if (alert != null) {
				display.setCurrent(alert, next);
			} else {
				display.setCurrent(next);
			}
		} catch (Throwable t) {
			// 兜底降级：本类方法被 MIDlet 在启动路径调用，任何异常都会导致启动失败，
			// 因此这里吞掉并记录（属预期内降级，用 w 级，不触发崩溃上报）
			KeydroidxLog.w(TAG, "setCurrent failed", t);
		}
	}

	/**
	 * 向前台/后台状态切换派发监听器回调。
	 * <p>
	 * 仅供宿主 {@code MicroActivity} 在 onResume/onPause 中调用：
	 * active=true 表示回到前台，active=false 表示退到后台。
	 * <p>
	 * 回调是 MIDlet 代码，必须经事件队列派发到模拟器事件线程执行，
	 * 禁止在 Activity 主线程直接回调。
	 *
	 * @param active true=回到前台（displayActive），false=退到后台（displayInactive）
	 */
	public static void fireDisplayState(boolean active) {
		final DisplayStateListener listener = displayStateListener;
		if (listener == null) {
			return;
		}
		// MIDlet 已销毁（state==DESTROYED）时不再派发，避免把事件投进废弃的事件队列
		if (!MidletThread.hasInstance()) {
			return;
		}
		final Display display = displayRef == null ? null : displayRef.get();
		Display.postEvent(RunnableEvent.getInstance(() -> {
			try {
				if (active) {
					listener.displayActive(display);
				} else {
					listener.displayInactive(display);
				}
			} catch (Throwable t) {
				// 回调体是 MIDlet 代码，异常不能击穿事件队列线程
				KeydroidxLog.w(TAG, "display state callback failed, active=" + active, t);
			}
		}));
	}
}
