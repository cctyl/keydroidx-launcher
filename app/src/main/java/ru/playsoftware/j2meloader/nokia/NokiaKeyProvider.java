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
 * 原键桌面对外数据 Provider。
 * 供生态内的独立应用（如按键音乐播放器、阅读器、浏览器等）只读获取：
 * 1. 用户在桌面上配置的物理按键映射 (/keys)
 * 2. 桌面主题、字体、字体缩放等外观设置 (/settings)
 *
 * Authority: ${applicationId}.keyprovider (如 io.github.cctyl.nokia.keyprovider 或 io.github.cctyl.nokia.debug.keyprovider)
 */
public class NokiaKeyProvider extends ContentProvider {

	public static final String PATH_KEYS = "keys";
	public static final String PATH_SETTINGS = "settings";

	private static final int CODE_KEYS = 1;
	private static final int CODE_SETTINGS = 2;

	public static final String COL_ACTION = "action";
	public static final String COL_ACTION_ID = "actionId";
	public static final String COL_KEY_CODE = "keyCode";
	public static final String COL_KEY_NAME = "keyName";

	public static final String COL_KEY = "key";
	public static final String COL_VALUE = "value";

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
				uriMatcher.addURI(authority, PATH_SETTINGS, CODE_SETTINGS);
				// 兼容通配符
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
		NokiaLog.d("KeyProvider", "收到查询请求: uri=" + uri);

		if (getContext() == null) {
			NokiaLog.e("KeyProvider", "query 失败: getContext() == null");
			return null;
		}

		int match = getUriMatcher().match(uri);
		if (match == CODE_SETTINGS || (uri.getPath() != null && uri.getPath().contains(PATH_SETTINGS))) {
			MatrixCursor cursor = new MatrixCursor(new String[]{COL_KEY, COL_VALUE});
			NokiaSettingsStorage storage = new NokiaSettingsStorage(getContext());

			cursor.addRow(new Object[]{"theme_id", storage.getThemeId()});
			cursor.addRow(new Object[]{"font_id", storage.getFontId()});
			cursor.addRow(new Object[]{"font_scale", String.valueOf(NokiaSettingsStorage.getFontScale(getContext()))});

			cursor.setNotificationUri(getContext().getContentResolver(), uri);
			NokiaLog.i("KeyProvider", "成功返回 settings 数据，共 " + cursor.getCount() + " 项");
			return cursor;
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
		return "vnd.android.cursor.dir/vnd.nokia.ecosystem";
	}

	@Nullable
	@Override
	public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
		return null;
	}

	@Override
	public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
		return 0;
	}

	@Override
	public int update(@NonNull Uri uri, @Nullable ContentValues values,
					  @Nullable String selection, @Nullable String[] selectionArgs) {
		return 0;
	}
}
