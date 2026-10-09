package io.github.ojuscal.viamenufix;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Rect;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.lang.reflect.Field;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Via 浏览器长按菜单定位修正。
 *
 * 原逻辑（w5.k.g0(int x, int y)，Via 7.3.4）以长按点 (x, y) 为锚点：
 *   y 在上半屏 -> gravity = TOP    ，菜单从手指向下展开；
 *   y 在下半屏 -> gravity = BOTTOM ，菜单从手指向上展开。
 * 后者会让菜单整块出现在手指上方，单手（拇指）够不到。
 *
 * 本模块在 g0() 执行完、窗口首次绘制之前改写窗口 gravity / y：
 *   菜单恒定从手指向下展开；下方空间不足时整体上移，保证菜单完整可见。
 *
 * 目标：包名 mark.via，Via 7.3.4 (versionCode 20260920)，LSPosed / Xposed API 82。
 */
public class ViaMenuModule implements IXposedHookLoadPackage {

    private static final String TAG = "[ViaMenuFix] ";
    private static final String TARGET_PACKAGE = "mark.via";
    private static final String MENU_BUILDER_CLASS = "w5.k";
    private static final String SHOW_AT_METHOD = "g0";

    // 真实 dex 字段名（jadx 里的 f13248a 等只是它为避免与包名冲突而起的显示别名，
    // 运行时字段名是单字母）。取不到时下面有按类型兜底的逻辑。
    private static final String F_DIALOG = "a";       // w5.k.a     -> Dialog
    private static final String F_CONTENT_H = "c";    // w5.k.c     -> int 内容区高度
    private static final String F_CONTENT_TOP = "e";  // w5.k.e     -> int 内容区顶部屏幕 y

    private static final String ANCHOR_METHOD_VIEW = "i0";   // i0(View, int, int)：锚在控件上
    private static final String ANCHOR_METHOD_SIMPLE = "h0"; // h0(View)：内部转调 i0

    /** false = 始终向下展开（默认，单手推荐）；true = 菜单垂直居中于手指 */
    private static final boolean CENTER_ON_FINGER = false;

    /** g0 是由 i0/h0（锚定控件）调用的标记：这类菜单保持 Via 原逻辑，只改长按手指的菜单 */
    private static final ThreadLocal<Boolean> VIEW_ANCHORED = new ThreadLocal<Boolean>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(
                    MENU_BUILDER_CLASS, lpparam.classLoader, SHOW_AT_METHOD,
                    int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(VIEW_ANCHORED.get())) {
                                return;   // 锚在控件上的菜单（工具栏/标签栏等）不动
                            }
                            try {
                                reposition(param);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "重新定位失败: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + "已挂钩 " + MENU_BUILDER_CLASS + "." + SHOW_AT_METHOD + "(int,int)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "挂钩失败（Via 版本可能已变化）: " + t);
        }

        flagAnchorCalls(lpparam.classLoader, ANCHOR_METHOD_SIMPLE, View.class);
        flagAnchorCalls(lpparam.classLoader, ANCHOR_METHOD_VIEW, View.class, int.class, int.class);
    }

    /** i0/h0 都是「以控件为锚点」定位，标记出来让 g0 跳过改写。 */
    private void flagAnchorCalls(ClassLoader cl, String method, Class<?>... params) {
        XC_MethodHook flag = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                VIEW_ANCHORED.set(Boolean.TRUE);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                VIEW_ANCHORED.remove();
            }
        };
        Object[] args = new Object[params.length + 1];
        System.arraycopy(params, 0, args, 0, params.length);
        args[params.length] = flag;
        try {
            XposedHelpers.findAndHookMethod(MENU_BUILDER_CLASS, cl, method, args);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "挂钩 " + method + "() 失败: " + t);
        }
    }

    private void reposition(XC_MethodHook.MethodHookParam param) {
        Object builder = param.thisObject;

        Dialog dialog = findDialog(builder);
        if (dialog == null || !dialog.isShowing()) {
            return;
        }
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        View decor = window.getDecorView();
        if (decor == null) {
            return;
        }

        // Via 在写窗口参数之前用 measure() 量过 decor，这里拿得到高度
        int menuHeight = decor.getMeasuredHeight() > 0 ? decor.getMeasuredHeight() : decor.getHeight();
        if (menuHeight <= 0) {
            XposedBridge.log(TAG + "菜单高度未知，保持 Via 原布局");
            return;
        }

        Context ctx = decor.getContext();

        // 对话框窗口的父框架 = 可视显示区（状态栏之下、导航栏之上），
        // 不是 Via 自己记的内容视图顶部（实测该值为 0，会比真实框架高一个状态栏）。
        Rect visible = new Rect();
        decor.getWindowVisibleDisplayFrame(visible);
        int frameTop;
        int frameBottom;
        String frameFrom;
        if (visible.width() > 0 && visible.height() > 0) {
            frameTop = visible.top;
            frameBottom = visible.bottom;
            frameFrom = "visibleFrame";
        } else {
            frameTop = XposedHelpers.getIntField(builder, F_CONTENT_TOP);
            frameBottom = frameTop + XposedHelpers.getIntField(builder, F_CONTENT_H);
            frameFrom = "viaContent";
        }

        int fingerY = (Integer) param.args[1];
        int targetTop = computeTargetTop(ctx, fingerY, menuHeight, frameTop, frameBottom);

        WindowManager.LayoutParams lp = window.getAttributes();
        int oldGravity = lp.gravity;
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.y = targetTop - frameTop;    // lp.y 以窗口框架顶部为原点
        window.setAttributes(lp);

        XposedBridge.log(TAG + "fingerY=" + fingerY + " menuH=" + menuHeight
                + " frame=[" + frameTop + "," + frameBottom + "](" + frameFrom + ")"
                + " gravity " + oldGravity + "->" + lp.gravity + " y=" + lp.y);

        // 只做诊断：等入场动画(240ms)结束后再量一次真实位置，不改动窗口参数
        logFinalPosition(dialog, decor, targetTop);
    }

    /** 目标位置：始终让菜单顶边落在手指下方；放不下就整体上移到屏幕下沿。 */
    private int computeTargetTop(Context ctx, int fingerY, int menuHeight, int frameTop, int frameBottom) {
        int gap = dp(ctx, 8f);       // 菜单顶边与手指的间距（别让指尖盖住第一项）
        int margin = dp(ctx, 4f);    // 与可视区上下边缘的留白
        int minTop = frameTop + margin;
        int maxTop = frameBottom - menuHeight - margin;
        if (maxTop < minTop) {
            maxTop = minTop;
        }
        int targetTop = CENTER_ON_FINGER ? (fingerY - menuHeight / 2) : (fingerY + gap);
        if (targetTop > maxTop) {
            targetTop = maxTop;
        }
        if (targetTop < minTop) {
            targetTop = minTop;
        }
        return targetTop;
    }

    /**
     * 仅在入场动画结束后记录一次真实位置，方便核对（不改动窗口参数）。
     * 注意：动画播放期间 getLocationOnScreen() 会带上动画位移，不能用来校正。
     */
    private void logFinalPosition(final Dialog dialog, final View decor, final int expectedTop) {
        decor.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!dialog.isShowing()) {
                        return;
                    }
                    int[] loc = new int[2];
                    decor.getLocationOnScreen(loc);
                    int delta = loc[1] - expectedTop;
                    XposedBridge.log(TAG + "实际 top=" + loc[1] + " 期望=" + expectedTop
                            + (Math.abs(delta) <= 4 ? " ✓" : (" 偏差=" + delta)));
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "位置核对失败: " + t);
                }
            }
        }, 600L);
    }

    private static Dialog findDialog(Object builder) {
        try {
            Object value = XposedHelpers.getObjectField(builder, F_DIALOG);
            if (value instanceof Dialog) {
                return (Dialog) value;
            }
        } catch (Throwable ignored) {
            // 落到下面的类型扫描
        }
        for (Field f : builder.getClass().getDeclaredFields()) {
            if (Dialog.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    Object value = f.get(builder);
                    if (value instanceof Dialog) {
                        XposedBridge.log(TAG + "按类型找到 Dialog 字段: " + f.getName());
                        return (Dialog) value;
                    }
                } catch (Throwable ignored) {
                    // 继续找
                }
            }
        }
        return null;
    }

    private static int dp(Context ctx, float value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, ctx.getResources().getDisplayMetrics());
    }
}
