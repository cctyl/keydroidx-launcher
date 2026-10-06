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

/**
 * Nokia S40 第 5/6 版私有 API：请求把 MIDlet 置于前台但前台不可用时抛出。
 * <p>
 * 真机上当应用处于后台、系统不允许其抢占前台时由 {@link LCDUIUtils#setCurrent} 抛出。
 * 模拟器中该异常仅用于保证 MIDlet 字节码链接成功（异常类型必须可解析），
 * 实际实现遇到不可用场景只降级处理并记录日志，不会抛出。
 */
public class ForegroundUnavailableException extends Exception {

	public ForegroundUnavailableException() {
		super();
	}

	public ForegroundUnavailableException(String message) {
		super(message);
	}
}
