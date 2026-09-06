package com.axon.input;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.File;

/**
 * 应用界面专用字体状态。
 *
 * 字体文件不再随 APK 打包：默认始终是系统字体；用户在入口弹窗选择“应用字体”后，
 * 才从应用私有目录加载已经通过校验的云端字体。按显与悬浮层继续独立走 FontManager，
 * 这里不会修改任何按显 Paint / Typeface 状态。
 */
final class AppTypeface {
    private static final String FONT_DIR = "app_fonts";
    private static final String FONT_FILE = "SourceHanSansSC-Heavy.otf";

    private static volatile Typeface appTypeface;
    private static volatile boolean appFontSelected;

    private AppTypeface() {}

    static void selectSystem() {
        appFontSelected = false;
        appTypeface = null;
    }

    static boolean selectDownloaded(Context context) {
        File file = downloadedFontFile(context);
        if (!file.isFile() || file.length() < 1024L * 1024L) {
            appTypeface = null;
            appFontSelected = false;
            return false;
        }
        try {
            Typeface loaded = Typeface.createFromFile(file);
            if (loaded == null) {
                appTypeface = null;
                appFontSelected = false;
                return false;
            }
            appTypeface = loaded;
            appFontSelected = true;
            return true;
        } catch (Throwable ignored) {
            appTypeface = null;
            appFontSelected = false;
            return false;
        }
    }

    static Typeface heavy(Context context) {
        Typeface local = appTypeface;
        if (appFontSelected && local != null) return local;
        return Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    }

    static boolean isAppFontSelected() {
        return appFontSelected && appTypeface != null;
    }

    static void applyIfSelected(TextView view) {
        if (view == null || !isAppFontSelected()) return;
        applyTextTypeface(view, appTypeface);
    }

    /**
     * 云端 Heavy 字体的 top/bottom metrics 明显大于系统默认字体。
     * 统一在字体切换入口补足 font padding/minHeight，而不是让每个页面猜固定 dp 高度。
     */
    private static void applyTextTypeface(TextView view, Typeface typeface) {
        view.setIncludeFontPadding(true);
        view.setTypeface(typeface);
        android.graphics.Paint.FontMetricsInt fm = view.getPaint().getFontMetricsInt();
        int fontHeight = Math.max(1, fm.bottom - fm.top);
        int safeMin = fontHeight + view.getPaddingTop() + view.getPaddingBottom();
        if (view.getMinimumHeight() < safeMin) view.setMinHeight(safeMin);
        view.requestLayout();
    }

    static File downloadedFontFile(Context context) {
        File dir = new File(context.getFilesDir(), FONT_DIR);
        return new File(dir, FONT_FILE);
    }

    static File downloadedFontTempFile(Context context) {
        File dir = new File(context.getFilesDir(), FONT_DIR);
        return new File(dir, FONT_FILE + ".download");
    }

    /**
     * 只遍历 Activity 自身 View 树。自绘按显/悬浮层不属于这里，且它们仍使用自己的 FontManager。
     * 选择云端字体后立即刷新当前已创建的 TextView，同时更新 LagSeekBar 右侧数值字形。
     */
    static void applyToViewTree(View root) {
        if (root == null || !isAppFontSelected()) return;
        Typeface typeface = appTypeface;
        if (root instanceof TextView) {
            applyTextTypeface((TextView) root, typeface);
        }
        if (root instanceof LagSeekBar) {
            ((LagSeekBar) root).setValueTypeface(typeface);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyToViewTree(group.getChildAt(i));
            }
        }
    }
}
