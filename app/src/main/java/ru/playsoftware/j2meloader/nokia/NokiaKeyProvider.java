package ru.playsoftware.j2meloader.nokia;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 诺基亚桌面按键配置对外 Provider。
 * 供生态内的独立应用（如按键音乐播放器、浏览器等）只读获取用户在桌面上配置的物理按键映射。
 *
 * Authority: ${applicationId}.keyprovider (如 io.github.cctyl.nokia.keyprovider 或 io.github.cctyl.nokia.debug.keyprovider)
 * Path: /keys
 *
 * 字段结构 (columns):
 * - action (String): 动作名称 (UP, DOWN, LEFT, RIGHT, SELECT, SOFT_LEFT, SOFT_RIGHT, LOCK_SCREEN, HANGUP)
 * - actionId (int): 动作枚举数字 (0 ~ 8)
 * - keyCode (int): 对应的 Android 物理键码
 * - keyName (String): 可读按键名称 (如 DPAD_UP, SOFT_LEFT 等)
 */
public class NokiaKeyProvider extends ContentProvider {

	public static final String PATH_KEYS = "keys";
	private static final int CODE_KEYS = 1;

	public static final String COL_ACTION = "action";
	public static final String COL_ACTION_ID = "actionId";
	public static final String COL_KEY_CODE = "keyCode";
	public static final String COL_KEY_NAME = "keyName";

	private static final String[] ACTION_TAGS = {
			"UP", "DOWN", "LEFT", "RIGHT",
			"SELECT", "SOFT_LEFT", "SOFT_RIGHT", "LOCK_SCREEN", "HANGUP"
	};

	private UriMatcher uriMatcher;

	@Override
	public boolean onCreate() {
		NokiaLog.i("KeyProvider", "NokiaKeyProvider 初始化");
		return true;
	}

	private synchronized UriMatcher getUriMatcher() {
		if (uriMatcher == null) {
			uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
			if (getContext() != null) {
				String authority = getContext().getPackageName() + ".keyprovider";
				uriMatcher.addURI(authority, PATH_KEYS, CODE_KEYS);
				// 也支持通配符或空路径，保证容错
				uriMatcher.addURI(authority, null, CODE_KEYS);
			}
		}
		return uriMatcher;
	}

	@Nullable
	@Override
	public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
						@Nullable String selection, @Nullable String[] selectionArgs,
						@Nullable String sortOrder) {
		NokiaLog.d("KeyProvider", "收到按键查询请求: uri=" + uri);

		if (getContext() == null) {
			NokiaLog.e("KeyProvider", "query 失败: getContext() == null");
			return null;
		}

		MatrixCursor cursor = new MatrixCursor(new String[]{
				COL_ACTION,
				COL_ACTION_ID,
				COL_KEY_CODE,
				COL_KEY_NAME
		});

		int[] keyCodes = NokiaKeyBinding.loadKeyCodes(getContext());
		for (int i = 0; i < NokiaKeyBinding.ACTION_COUNT && i < keyCodes.length; i++) {
			int kc = keyCodes[i];
			String tag = (i < ACTION_TAGS.length) ? ACTION_TAGS[i] : ("ACTION_" + i);
			String name = NokiaLog.keyName(kc);

			cursor.addRow(new Object[]{
					tag,
					i,
					kc,
					name
			});
		}

		// 监听通知 URI
		cursor.setNotificationUri(getContext().getContentResolver(), uri);
		NokiaLog.i("KeyProvider", "成功返回按键映射，共 " + cursor.getCount() + " 项");
		return cursor;
	}

	@Nullable
	@Override
	public String getType(@NonNull Uri uri) {
		return "vnd.android.cursor.dir/vnd.nokia.keys";
	}

	@Nullable
	@Override
	public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
		// 只读 Provider，不支持外部写入
		return null;
	}

	@Override
	public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
		// 只读 Provider，不支持外部删除
		return 0;
	}

	@Override
	public int update(@NonNull Uri uri, @Nullable ContentValues values,
					  @Nullable String selection, @Nullable String[] selectionArgs) {
		// 只读 Provider，不支持外部更新
		return 0;
	}
}
