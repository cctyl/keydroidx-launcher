package ru.playsoftware.j2meloader.nokia;

import android.content.res.Resources;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * 统一尺寸换算工具类。
 * 所有 nokia 界面与 Drawable 的 dp → px 换算均收口于此，
 * 替代各 Fragment / Dialog 中各自复制的私有 dp() 方法。
 *
 * 行为：dp = value × density，与原 8 处私有实现逐值一致。
 */
public final class NokiaDimens {

    /**
     * 用户字体缩放系数（桌面设置 → 字体大小），默认 1.0。
     * 由 {@link NokiaBaseActivity#attachBaseContext} 在 Activity 创建早期从设置读取并更新。
     */
    public static float sUserFontScale = 1f;

    private NokiaDimens() {
        // 工具类，禁止实例化
    }

    /** dp 转 px（整数，直接截断小数） */
    public static int dp(Resources res, float value) {
        return (int) (value * res.getDisplayMetrics().density);
    }

    /** dp 转 px（保留小数） */
    public static float dpF(Resources res, float value) {
        return value * res.getDisplayMetrics().density;
    }

    /**
     * 以 dp 为单位设置 TextView 字号。
     * 与 240dp 设计基准一致、不跟随系统字体缩放（配合 NokiaBaseActivity 的 fontScale=1）。
     * 所有动态创建文字的 setTextSize() 一律走此入口，禁止使用默认 sp 单位。
     */
    public static void textSize(TextView tv, float dpValue) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, dpValue * sUserFontScale);
    }
}
