package ru.playsoftware.j2meloader.nokia;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.MessageDigest;

import io.github.cctyl.nokia.shizuku.MiniShizukuConst;

/**
 * mini_shizuku 密钥派发 Provider（exported）。
 * <p>
 * Authority: {@code ${applicationId}.shizuku}（如 io.github.cctyl.nokia.shizuku / .debug.shizuku）。
 * <p>
 * 只做一件事——按身份派发进程级密钥 K（{@link NokiaShizukuKeyHolder}），不代理命令执行：
 * <ul>
 *   <li>{@code getServerKey}：仅放行特权 uid（shell=2000 / system=1000 / root=0），返回 K。
 *       供 app_process server 跨进程拉取（server 无法被普通应用冒充，因普通应用 uid 非上述值）。</li>
 *   <li>{@code getKey}：仅放行与 launcher 同签名者（含 launcher 自身 hostUid），返回 K。
 *       异签名返回 null（Binder.getCallingUid 内核级可信，签名由 PackageManager 查，不可伪造）。</li>
 * </ul>
 * query/insert/update/delete 未使用，返回空。
 */
public class NokiaShizukuProvider extends ContentProvider {

    private static final String TAG = "MiniShizuku";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        int callingUid = Binder.getCallingUid();
        switch (method) {
            case MiniShizukuConst.METHOD_GET_SERVER_KEY:
                if (isPrivilegedUid(callingUid)) {
                    Log.i(TAG, "getServerKey: uid=" + callingUid + " -> granted");
                    return bundleWithKey();
                }
                Log.w(TAG, "getServerKey: uid=" + callingUid + " -> denied");
                return null;
            case MiniShizukuConst.METHOD_GET_KEY:
                if (isSameSignature(callingUid)) {
                    Log.i(TAG, "getKey: uid=" + callingUid + " -> granted");
                    return bundleWithKey();
                }
                Log.w(TAG, "getKey: uid=" + callingUid + " -> denied");
                return null;
            default:
                return null;
        }
    }

    private Bundle bundleWithKey() {
        Bundle b = new Bundle();
        b.putString(MiniShizukuConst.EXTRA_KEY, NokiaShizukuKeyHolder.get());
        return b;
    }

    /** shell / system / root 等特权 uid（普通应用 uid 不会落入这些值）。 */
    private boolean isPrivilegedUid(int uid) {
        return uid == 2000 || uid == 1000 || uid == 0;
    }

    /** 调用方与 launcher 同签名（含 launcher 自身 hostUid）。 */
    private boolean isSameSignature(int callingUid) {
        Context ctx = getContext();
        if (ctx == null) return false;
        byte[] selfDigest = selfSignatureDigest(ctx);
        if (selfDigest == null) return false;

        // launcher 自身（in-process 或自调用）直接放行
        if (callingUid == android.os.Process.myUid()) return true;

        PackageManager pm = ctx.getPackageManager();
        String[] pkgs = pm.getPackagesForUid(callingUid);
        if (pkgs == null) return false;
        for (String pkg : pkgs) {
            if (signatureMatches(ctx, pkg, selfDigest)) return true;
        }
        return false;
    }

    private boolean signatureMatches(Context ctx, String pkg, byte[] selfDigest) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(pkg,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                            ? PackageManager.GET_SIGNING_CERTIFICATES
                            : PackageManager.GET_SIGNATURES);
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
                sigs = info.signingInfo.getApkContentsSigners();
            } else {
                sigs = info.signatures;
            }
            if (sigs == null) return false;
            for (Signature s : sigs) {
                byte[] d = sha256(s.toByteArray());
                if (d != null && MessageDigest.isEqual(d, selfDigest)) return true;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        } catch (Exception e) {
            Log.w(TAG, "signatureMatches failed for " + pkg, e);
        }
        return false;
    }

    private byte[] selfSignatureDigest(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(),
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                            ? PackageManager.GET_SIGNING_CERTIFICATES
                            : PackageManager.GET_SIGNATURES);
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
                sigs = info.signingInfo.getApkContentsSigners();
            } else {
                sigs = info.signatures;
            }
            if (sigs != null && sigs.length > 0) {
                return sha256(sigs[0].toByteArray());
            }
        } catch (Exception e) {
            Log.w(TAG, "selfSignatureDigest failed", e);
        }
        return null;
    }

    private byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            return null;
        }
    }

    // 以下方法未使用
    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
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
