package com.axon.input;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Shared color editor used by every display color selector. Supports 1..8 looping colors. */
final class MultiColorPickerDialog {
    interface Callback { void onColors(int[] colors); }

    private MultiColorPickerDialog() {}

    static void show(Activity activity, CharSequence title, int[] initial, Callback callback) {
        final List<Integer> colors = new ArrayList<>();
        int[] normalized = ColorSequence.normalize(initial, Color.WHITE);
        for (int color : normalized) colors.add(color);
        final int[] selected = new int[]{0};
        final int[] channels = new int[3];

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 18), dp(activity, 8), dp(activity, 18), dp(activity, 2));

        TextView hint = text(activity, "可添加 2–8 个颜色；多个颜色会持续循环渐变。", 12f);
        hint.setTextColor(UiPalette.textSecondary(activity));
        root.addView(hint, wrap(dp(activity, 8)));

        LinearLayout swatches = new LinearLayout(activity);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        swatches.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(swatches, wrap(dp(activity, 8)));

        TextView preview = text(activity, "", 12f);
        preview.setGravity(Gravity.CENTER);
        preview.setPadding(dp(activity, 8), dp(activity, 10), dp(activity, 8), dp(activity, 10));
        root.addView(preview, wrap(dp(activity, 8)));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button add = new Button(activity);
        add.setText("添加颜色");
        add.setAllCaps(false);
        Button remove = new Button(activity);
        remove.setText("删除当前");
        remove.setAllCaps(false);
        actions.addView(add, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        removeLp.leftMargin = dp(activity, 8);
        actions.addView(remove, removeLp);
        root.addView(actions, wrap(dp(activity, 8)));

        String[] names = new String[]{"R", "G", "B"};
        SeekBar[] bars = new SeekBar[3];
        TextView[] labels = new TextView[3];
        boolean[] syncing = new boolean[]{false};

        Runnable updatePreview = () -> {
            int color = colors.get(selected[0]);
            preview.setText(String.format(java.util.Locale.US, "颜色 %d / %d   #%06X",
                    selected[0] + 1, colors.size(), color & 0x00ffffff));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(activity, 9));
            bg.setColor(color);
            preview.setBackground(bg);
            double luma = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color);
            preview.setTextColor(luma > 150 ? Color.BLACK : Color.WHITE);
        };

        final Runnable[] rebuildSwatches = new Runnable[1];
        rebuildSwatches[0] = () -> {
            swatches.removeAllViews();
            for (int i = 0; i < colors.size(); i++) {
                final int index = i;
                View dot = new View(activity);
                GradientDrawable d = new GradientDrawable();
                d.setShape(GradientDrawable.OVAL);
                d.setColor(colors.get(i));
                d.setStroke(dp(activity, index == selected[0] ? 2 : 1),
                        index == selected[0] ? UiPalette.accent(activity) : UiPalette.divider(activity));
                dot.setBackground(d);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(activity, 30), dp(activity, 30));
                if (i > 0) lp.leftMargin = dp(activity, 8);
                swatches.addView(dot, lp);
                dot.setOnClickListener(v -> {
                    selected[0] = index;
                    int c = colors.get(index);
                    syncing[0] = true;
                    channels[0] = Color.red(c); channels[1] = Color.green(c); channels[2] = Color.blue(c);
                    for (int k = 0; k < 3; k++) {
                        bars[k].setProgress(channels[k]);
                        labels[k].setText(names[k] + "  " + channels[k]);
                    }
                    syncing[0] = false;
                    updatePreview.run();
                    rebuildSwatches[0].run();
                });
            }
            remove.setEnabled(colors.size() > 1);
            add.setEnabled(colors.size() < ColorSequence.MAX_COLORS);
        };

        for (int i = 0; i < 3; i++) {
            final int channel = i;
            TextView label = text(activity, "", 12f);
            label.setTextColor(UiPalette.textSecondary(activity));
            labels[i] = label;
            root.addView(label, wrap(0));
            SeekBar bar = new LagSeekBar(activity);
            UiChrome.styleSeekBar(activity, bar);
            bar.setMax(255);
            bars[i] = bar;
            root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 36)));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (syncing[0]) return;
                    channels[channel] = progress;
                    labels[channel].setText(names[channel] + "  " + progress);
                    int color = Color.rgb(channels[0], channels[1], channels[2]);
                    colors.set(selected[0], color);
                    updatePreview.run();
                    rebuildSwatches[0].run();
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        Runnable syncSelected = () -> {
            int c = colors.get(selected[0]);
            syncing[0] = true;
            channels[0] = Color.red(c); channels[1] = Color.green(c); channels[2] = Color.blue(c);
            for (int i = 0; i < 3; i++) {
                bars[i].setProgress(channels[i]);
                labels[i].setText(names[i] + "  " + channels[i]);
            }
            syncing[0] = false;
            updatePreview.run();
            rebuildSwatches[0].run();
        };

        add.setOnClickListener(v -> {
            if (colors.size() >= ColorSequence.MAX_COLORS) return;
            int base = colors.get(selected[0]);
            colors.add(base);
            selected[0] = colors.size() - 1;
            syncSelected.run();
        });
        remove.setOnClickListener(v -> {
            if (colors.size() <= 1) return;
            colors.remove(selected[0]);
            selected[0] = Math.min(selected[0], colors.size() - 1);
            syncSelected.run();
        });
        syncSelected.run();

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            int[] result = new int[colors.size()];
            for (int i = 0; i < result.length; i++) result[i] = colors.get(i);
            callback.onColors(result);
            dialog.dismiss();
        }));
        dialog.show();
    }

    private static TextView text(Activity a, String value, float size) {
        TextView out = new TextView(a);
        out.setText(value);
        out.setTextSize(size);
        return out;
    }

    private static LinearLayout.LayoutParams wrap(int bottomMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = bottomMargin;
        return lp;
    }

    private static int dp(Activity a, float value) {
        return Math.round(value * a.getResources().getDisplayMetrics().density);
    }
}
