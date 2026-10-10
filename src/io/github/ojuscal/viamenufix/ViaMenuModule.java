package io.github.ojuscal.viamenufix;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Rect;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Via 长按菜单修正 / Via Long-Press Menu Fix
 *
 * Via 的长按菜单以点击点为锚点，并按点击点所在半屏决定展开方向：
 *   上半屏 -> Gravity.TOP    ，从手指向下展开；
 *   下半屏 -> Gravity.BOTTOM ，从手指向上展开（整张菜单跑到手指上方，单手够不到）。
 *
 * 本模块把菜单改成恒定向下展开，下方放不下时整体上移贴着可视区下沿。
 *
 * 两条路径：
 *  1) 精准路径（默认）：hook Via 自己的菜单定位方法 w5.k.g0(II)V。
 *     实测 Via 7.3.3(cn) / 7.3.3(gp) / 7.3.4 三个版本的 R8 名称映射一致，
 *     该类与字段 a(Dialog)/c(高度)/e(顶部) 完全相同，一份模块通吃。
 *  2) 兜底路径（精准路径找不到目标时自动启用；也可用 FORCE_FALLBACK 强制测试）：
 *     完全不依赖混淆名，改为 hook 框架层：
 *       Window.setContentView(...) 给"对话框窗口"打标记（菜单在 show() 之前就设参数，所以不能用 show 打标记）
 *       Window.setAttributes(...)  识别「点定位 + 列表内容」的弹层并就地改写 gravity/y
 */
public class ViaMenuModule implements IXposedHookLoadPackage {

    private static final String TAG = "[ViaMenuFix] ";

    /** Via 的包名：CN/国内版 mark.via，Google Play 版 mark.via.gp */
    private static final String[] TARGET_PACKAGES = {"mark.via", "mark.via.gp"};

    // ---- 精准路径用到的符号（Via 7.3.3 ~ 7.3.4 实测一致）----
    private static final String MENU_BUILDER_CLASS = "w5.k";
    private static final String SHOW_AT_METHOD = "g0";          // void g0(int x, int y)
    private static final String ANCHOR_METHOD_VIEW = "i0";      // i0(View, int, int) 锚在控件上
    private static final String ANCHOR_METHOD_SIMPLE = "h0";    // h0(View)
    private static final String F_DIALOG = "a";                 // Dialog
    private static final String F_CONTENT_H = "c";              // int 内容区高度
    private static final String F_CONTENT_TOP = "e";            // int 内容区顶部屏幕 y

    /** false = 始终向下展开（默认，单手推荐）；true = 菜单垂直居中于手指 */
    private static final boolean CENTER_ON_FINGER = false;

    /** 调试用：强制走框架层兜底路径（验证"Via 改名后"是否也能生效） */
    private static final boolean FORCE_FALLBACK = false;

    /** 标记：g0 由 i0/h0（锚定控件）调用，这类菜单保持原样 */
    private static final ThreadLocal<Boolean> VIEW_ANCHORED = new ThreadLocal<Boolean>();

    /** 兜底路径：被 setContentView 过的窗口 = 对话框窗口 */
    private static final WeakHashMap<Window, Boolean> DIALOG_WINDOWS = new WeakHashMap<Window, Boolean>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!isTarget(lpparam.packageName)) {
            return;
        }
        XposedBridge.log(TAG + "加载于 " + lpparam.packageName + " " + versionName(lpparam));

        boolean primary = installPrimaryHook(lpparam);
        if (!primary || FORCE_FALLBACK) {
            XposedBridge.log(TAG + (primary ? "FORCE_FALLBACK：改用" : "精准路径不可用（Via 可能改名了），改用")
                    + "框架层兜底路径");
            installFallbackHooks();
        }
    }

    private static boolean isTarget(String pkg) {
        for (String p : TARGET_PACKAGES) {
            if (p.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    private static String versionName(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Object info = lpparam.appInfo;
            if (info == null) {
                return "";
            }
            // 用反射取，避免不同版本 API 桩里 appInfo 的类型声明差异
            Object value = XposedHelpers.getObjectField(info, "versionName");
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable t) {
            return "";
        }
    }

    // ================================================================
    // 路径 1：精准 hook Via 自己的菜单定位方法
    // ================================================================

    private boolean installPrimaryHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    MENU_BUILDER_CLASS, lpparam.classLoader, SHOW_AT_METHOD,
                    int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(VIEW_ANCHORED.get())) {
                                return;
                            }
                            try {
                                reposition(param);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "重新定位失败: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + "精准路径已挂钩 " + MENU_BUILDER_CLASS + "." + SHOW_AT_METHOD + "(int,int)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "精准挂钩失败: " + t);
            return false;
        }

        flagAnchorCalls(lpparam.classLoader, ANCHOR_METHOD_SIMPLE, View.class);
        flagAnchorCalls(lpparam.classLoader, ANCHOR_METHOD_VIEW, View.class, int.class, int.class);
        return true;
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

    // ================================================================
    // 路径 2：框架层兜底（不依赖任何混淆名）
    // ================================================================

    private void installFallbackHooks() {
        try {
            XposedHelpers.findAndHookMethod(Window.class, "setContentView", View.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    markDialogWindow((Window) param.thisObject);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "兜底挂钩 setContentView(View) 失败: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(Window.class, "setContentView", View.class,
                    ViewGroup.LayoutParams.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            markDialogWindow((Window) param.thisObject);
                        }
                    });
        } catch (Throwable ignored) {
            // 某些 ROM 没有这个重载，不影响
        }

        try {
            XposedHelpers.findAndHookMethod(Window.class, "setAttributes",
                    WindowManager.LayoutParams.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                fixFallback(param);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "兜底定位失败: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + "兜底路径已挂钩 Window.setAttributes");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "兜底挂钩 Window.setAttributes 失败: " + t);
        }
    }

    private static void markDialogWindow(Window window) {
        if (window == null) {
            return;
        }
        synchronized (DIALOG_WINDOWS) {
            DIALOG_WINDOWS.put(window, Boolean.TRUE);
        }
    }

    private static boolean isDialogWindow(Window window) {
        synchronized (DIALOG_WINDOWS) {
            return DIALOG_WINDOWS.containsKey(window);
        }
    }

    /**
     * 识别「点定位 + 列表内容」的弹层并就地改写窗口参数：
     *   1) 垂直重力只能是 TOP/BOTTOM（居中弹窗不动）
     *   2) 必须显式设过 x/y（点定位才会这样）
     *   3) 内容里要有 ListView / RecyclerView（Via 长按菜单是列表）
     * 改写：gravity = TOP|LEFT，y = clamp(锚点 + 8dp, 4dp, 框架高 − 菜单高 − 4dp)
     */
    private void fixFallback(XC_MethodHook.MethodHookParam param) {
        Window window = (Window) param.thisObject;
        if (!isDialogWindow(window)) {
            return;
        }
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) param.args[0];
        if (lp == null) {
            return;
        }
        int vertical = lp.gravity & Gravity.VERTICAL_GRAVITY_MASK;
        if (vertical != Gravity.TOP && vertical != Gravity.BOTTOM) {
            return;
        }
        if ((lp.gravity & Gravity.HORIZONTAL_GRAVITY_MASK) == 0) {
            return;
        }
        if (lp.x == 0 && lp.y == 0) {
            return;
        }
        View decor = window.getDecorView();
        if (decor == null || !containsList(decor, 0)) {
            return;
        }
        int menuHeight = decor.getMeasuredHeight() > 0 ? decor.getMeasuredHeight() : decor.getHeight();
        if (menuHeight <= 0) {
            return;
        }
        Rect visible = new Rect();
        decor.getWindowVisibleDisplayFrame(visible);
        int frameHeight = visible.height();
        if (frameHeight <= 0) {
            return;
        }

        // 原锚点（相对框架顶部）：BOTTOM 情况下 lp.y 是"距框架底部的距离"
        int anchorFromTop = vertical == Gravity.BOTTOM ? frameHeight - lp.y : lp.y;
        int oldGravity = lp.gravity;
        int targetTop = computeTargetTop(decor.getContext(), anchorFromTop, menuHeight, 0, frameHeight);

        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.y = targetTop;
        XposedBridge.log(TAG + "[兜底] anchorFromTop=" + anchorFromTop + " menuH=" + menuHeight
                + " frameH=" + frameHeight + " gravity " + oldGravity + "->" + lp.gravity + " y=" + lp.y);
    }

    private static boolean containsList(View view, int depth) {
        if (view == null || depth > 8) {
            return false;
        }
        for (Class<?> c = view.getClass(); c != null; c = c.getSuperclass()) {
            String name = c.getName();
            if ("android.widget.ListView".equals(name) || "android.widget.AbsListView".equals(name)
                    || name.endsWith(".RecyclerView")) {
                return true;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsList(group.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ================================================================
    // 共用：目标位置计算
    // ================================================================

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

    private static int dp(Context ctx, float value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, ctx.getResources().getDisplayMetrics());
    }
}
