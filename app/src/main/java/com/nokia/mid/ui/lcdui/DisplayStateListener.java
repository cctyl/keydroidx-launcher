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

import javax.microedition.lcdui.Display;

/**
 * Nokia S40 第 5/6 版私有 API：屏幕（Display）前台/后台状态监听接口。
 * <p>
 * 真机上由 {@link LCDUIUtils#setDisplayStateListener(Display, DisplayStateListener)} 注册，
 * MIDlet 退出前台（后台运行许可后）回调 {@link #displayInactive(Display)}，
 * 重新回到前台时回调 {@link #displayActive(Display)}。
 * <p>
 * 模拟器实现：回调由宿主 Activity 的 onPause/onResume 触发，并派发到模拟器事件线程执行。
 */
public interface DisplayStateListener {

	/**
	 * MIDlet 重新回到前台时回调。
	 *
	 * @param display 注册监听时传入的 Display 实例
	 */
	void displayActive(Display display);

	/**
	 * MIDlet 退到后台运行时回调。
	 *
	 * @param display 注册监听时传入的 Display 实例
	 */
	void displayInactive(Display display);
}
