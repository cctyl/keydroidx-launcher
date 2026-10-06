/*
 * Copyright 2018 Nikita Shakarun
 * Copyright 2019-2022 Yury Kharchenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.appsdb;

import static ru.playsoftware.j2meloader.util.Constants.PREF_APP_SORT;
import static ru.playsoftware.j2meloader.util.Constants.PREF_EMULATOR_DIR;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.preference.PreferenceManager;
import androidx.sqlite.db.SupportSQLiteProgram;
import androidx.sqlite.db.SupportSQLiteQuery;

import org.jetbrains.annotations.NotNull;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.Completable;
import io.reactivex.CompletableObserver;
import io.reactivex.Flowable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.flowables.ConnectableFlowable;
import io.reactivex.schedulers.Schedulers;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.applist.AppItem;
import ru.playsoftware.j2meloader.applist.AppListModel;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.util.AppUtils;

public class AppRepository implements SharedPreferences.OnSharedPreferenceChangeListener {
	private static final String TAG = "AppRepository";


	private final String[] orderTerms;
	private final Context context;
	private final MutableLiveData<List<AppItem>> listLiveData = new MutableLiveData<>();
	private final MutableLiveData<Throwable> errorsLiveData = new MutableLiveData<>();
	private final CompositeDisposable compositeDisposable = new CompositeDisposable();
	private final ErrorObserver errorObserver = new ErrorObserver(errorsLiveData);

	private AppDatabase db;
	private AppItemDao appItemDao;
	private int sortVariant;

	public AppRepository(AppListModel model) {
		if (model.getAppRepository() != null) {
			throw new IllegalStateException("You must get instance from 'AppListModel'");
		}
		this.context = model.getApplication();
		orderTerms = context.getResources().getStringArray(R.array.pref_app_sort_values);
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
		try {
			sortVariant = preferences.getInt(PREF_APP_SORT, 0);
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "IllegalStateException failed: " + e.getMessage());
			sortVariant = preferences.getString(PREF_APP_SORT, "name").equals("name") ? 0 : 1;
			preferences.edit().putInt(PREF_APP_SORT, sortVariant).apply();
		}
		preferences.registerOnSharedPreferenceChangeListener(this);
		// 构造时只做一次「尽力初始化」。存储权限按设计是首次使用相关功能时才申请的
		// （见 KeydroidxPermissionManager 核心权限全集说明：存储不纳入启动自检），
		// 因此此刻工作目录往往还不可写。绝不能因为这一次判定失败就永久放弃初始化——
		// 目录随后变为可写（权限授予、用户改目录）时必须能重新初始化，见 ensureReady()。
		ensureReady();
	}

	/** 数据库是否已就绪。未就绪时所有读写都会被安全跳过（不再空指针）。 */
	public boolean isReady() {
		return appItemDao != null;
	}

	/**
	 * 尝试初始化工作目录数据库（幂等，可重复调用）。
	 * <p>
	 * 调用时机：仓库构造、存储权限授予回调、页面重建/回到前台、工作目录变更。
	 * 目录不可写时只记 w 日志并返回，等待下一次时机重试；目录可写且尚未初始化时才真正建库。
	 */
	public synchronized void ensureReady() {
		if (appItemDao != null) {
			return;
		}
		String emulatorDir = Config.getEmulatorDir();
		File dir = new File(emulatorDir);
		// 目录不存在时尝试创建（首次使用且从未进过 J2ME Loader 设置页的路径）；
		// 缺存储权限时 mkdirs 会失败，同样落到下面的 w 日志，等下次时机重试。
		if ((!dir.isDirectory() && !dir.mkdirs()) || !dir.canWrite()) {
			KeydroidxLog.w(TAG, "工作目录暂不可用（可能缺少存储权限），跳过数据库初始化: " + emulatorDir);
			return;
		}
		KeydroidxLog.i(TAG, "工作目录可用，初始化数据库: " + emulatorDir);
		initDb(emulatorDir);
	}

	/**
	 * 取 DAO；未就绪时先尝试初始化一次，仍不可用则返回 null（调用方安全跳过并记日志）。
	 */
	private AppItemDao daoOrNull() {
		if (appItemDao == null) {
			ensureReady();
		}
		return appItemDao;
	}

	public synchronized void initDb(String path) {
		db = AppDatabase.open(context, path);
		appItemDao = db.appItemDao();
		ConnectableFlowable<List<AppItem>> listConnectableFlowable = getAll()
				.subscribeOn(Schedulers.io())
				.publish();
		compositeDisposable.add(listConnectableFlowable
				.firstElement()
				.subscribe(list -> AppUtils.updateDb(this, new ArrayList<>(list)), errorsLiveData::postValue));
		compositeDisposable.add(listConnectableFlowable.subscribe(listLiveData::postValue, errorsLiveData::postValue));
		compositeDisposable.add(listConnectableFlowable.connect());
	}

	public void observeApps(LifecycleOwner owner, Observer<List<AppItem>> observer) {
		listLiveData.observe(owner, observer);
	}

	public Flowable<List<AppItem>> getAll() {
		return appItemDao.getAll(new MutableSortSQLiteQuery(this, orderTerms));
	}

	public void insert(AppItem item) {
		AppItemDao dao = daoOrNull();
		if (dao == null) {
			KeydroidxLog.w(TAG, "数据库未就绪，忽略写入: " + item.getTitle());
			return;
		}
		Completable.fromAction(() -> dao.insert(item))
				.subscribeOn(Schedulers.io())
				.subscribe(errorObserver);
	}

	public void insert(List<AppItem> items) {
		AppItemDao dao = daoOrNull();
		if (dao == null) {
			KeydroidxLog.w(TAG, "数据库未就绪，忽略批量写入: " + items.size());
			return;
		}
		Completable.fromAction(() -> dao.insert(items))
				.subscribeOn(Schedulers.io())
				.subscribe();
	}

	public void update(AppItem item) {
		AppItemDao dao = daoOrNull();
		if (dao == null) {
			KeydroidxLog.w(TAG, "数据库未就绪，忽略更新: " + item.getTitle());
			return;
		}
		Completable.fromAction(() -> dao.update(item))
				.subscribeOn(Schedulers.io())
				.subscribe(errorObserver);
	}

	public void delete(AppItem item) {
		AppItemDao dao = daoOrNull();
		if (dao == null) {
			KeydroidxLog.w(TAG, "数据库未就绪，忽略删除: " + item.getTitle());
			return;
		}
		Completable.fromAction(() -> dao.delete(item))
				.subscribeOn(Schedulers.io())
				.subscribe(errorObserver);
	}

	public void delete(List<AppItem> items) {
		AppItemDao dao = daoOrNull();
		if (dao == null) {
			KeydroidxLog.w(TAG, "数据库未就绪，忽略批量删除: " + items.size());
			return;
		}
		Completable.fromAction(() -> dao.delete(items))
				.subscribeOn(Schedulers.io())
				.subscribe(errorObserver);
	}

	public void deleteAll() {
		AppItemDao dao = daoOrNull();
		if (dao == null) {
			KeydroidxLog.w(TAG, "数据库未就绪，忽略清空");
			return;
		}
		Completable.fromAction(dao::deleteAll)
				.subscribeOn(Schedulers.io())
				.subscribe(errorObserver);
	}

	/**
	 * 按名称+厂商查询。
	 * <p>
	 * 未就绪时返回 null（语义上等价于「没装过」）。安装流程在进入前会用
	 * {@link #isReady()} 拦截，因此该分支只在异常时序下出现。
	 */
	public AppItem get(String name, String vendor) {
		AppItemDao dao = daoOrNull();
		if (dao == null) {
			KeydroidxLog.w(TAG, "数据库未就绪，查询被跳过: " + name);
			return null;
		}
		return dao.get(name, vendor);
	}

	public AppItem get(int id) {
		AppItemDao dao = daoOrNull();
		if (dao == null) {
			KeydroidxLog.w(TAG, "数据库未就绪，按 id 查询被跳过: " + id);
			return null;
		}
		return dao.get(id);
	}

	public void close() {
		if (db != null) {
			db.close();
		}
		compositeDisposable.clear();
	}

	public int getSort() {
		return sortVariant;
	}

	private void setSort(int variant) {
		if (this.sortVariant == variant) {
			variant |= 0x80000000;
		}
		this.sortVariant = variant;
		AppItemDao dao = daoOrNull();
		if (dao == null) {
			KeydroidxLog.w(TAG, "数据库未就绪，跳过一次排序刷新");
			return;
		}
		Disposable disposable = dao.getAllSingle(new MutableSortSQLiteQuery(this, orderTerms))
				.subscribeOn(Schedulers.io())
				.subscribe(listLiveData::postValue, errorsLiveData::postValue);
		compositeDisposable.add(disposable);
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
		if (PREF_APP_SORT.equals(key)) {
			setSort(sp.getInt(PREF_APP_SORT, 0));
		} else if (PREF_EMULATOR_DIR.equals(key)) {
			String newPath = sp.getString(key, null);
			if (db != null) {
				String databaseName = db.getOpenHelper().getDatabaseName();
				if (databaseName != null) {
					String dbDir = new File(databaseName).getParent();
					if (dbDir != null) {
						if (dbDir.equals(newPath)) {
							return;
						}
					}
				}
				db.close();
				compositeDisposable.clear();
			}
			initDb(newPath);
		}
	}

	public void observeErrors(LifecycleOwner owner, Observer<Throwable> observer) {
		errorsLiveData.observe(owner, observer);
	}

	/**
	 * 工作目录就绪通知（兼容原有调用方）：等价于 {@link #ensureReady()}。
	 */
	public void onWorkDirReady() {
		ensureReady();
	}

	private static class ErrorObserver implements CompletableObserver {
		private final MutableLiveData<Throwable> callback;

		public ErrorObserver(MutableLiveData<Throwable> callback) {
			this.callback = callback;
		}

		@Override
		public void onSubscribe(@NotNull Disposable d) {
		}

		@Override
		public void onComplete() {
		}

		@Override
		public void onError(@NotNull Throwable e) {
			callback.postValue(e);
		}
	}

	private static class MutableSortSQLiteQuery implements SupportSQLiteQuery {
		private static final String SELECT = "SELECT * FROM apps ORDER BY ";
		private final AppRepository repository;
		private final String[] orderTerms;

		private MutableSortSQLiteQuery(AppRepository repository, String[] orderTerms) {
			this.repository = repository;
			this.orderTerms = orderTerms;
		}

		@Override
		public String getSql() {
			int sortVariant = repository.getSort();
			String order = sortVariant >= 0 ? " ASC" : " DESC";
			return SELECT + String.format(orderTerms[sortVariant & 0x7FFFFFFF], order);
		}

		@Override
		public void bindTo(SupportSQLiteProgram statement) {
		}

		@Override
		public int getArgCount() {
			return 0;
		}
	}
}
