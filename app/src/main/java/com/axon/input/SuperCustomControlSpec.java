package com.axon.input;

import android.graphics.Color;

/** Mutable editor state for one super-custom key display control. */
final class SuperCustomControlSpec {
    static final String CPS_PLACEHOLDER = "(cps)";
    static final String CPS_TEMPLATE = "cps:\"(cps)\"";

    int keyCode;
    String labelText;
    int widthDp = 92;
    int heightDp = 64;
    int cornerDp = 14;
    int opacityPercent = 94;
    int diffusionOpacityPercent = 100;
    int pressColor = Color.rgb(64, 64, 68);
    int borderColor = 0; // 0 = resolve from current UI palette for backward-compatible configs.
    int textColor = Color.WHITE;
    int textSizeSp = 18;
    int motionMode = OverlayState.MOTION_SIZE;
    boolean cpsEnabled;
    String cpsTemplate = CPS_TEMPLATE;
    int centerXPx = -1;
    int centerYPx = -1;
    boolean positionSet;

    SuperCustomControlSpec(int keyCode, String defaultLabel, boolean darkTheme) {
        this.keyCode = keyCode;
        this.labelText = defaultLabel;
        if (!darkTheme) {
            pressColor = Color.rgb(28, 28, 31);
            textColor = Color.rgb(22, 22, 24);
        }
    }

    SuperCustomControlSpec copy() {
        SuperCustomControlSpec out = new SuperCustomControlSpec(keyCode, labelText, false);
        out.widthDp = widthDp;
        out.heightDp = heightDp;
        out.cornerDp = cornerDp;
        out.opacityPercent = opacityPercent;
        out.diffusionOpacityPercent = diffusionOpacityPercent;
        out.pressColor = pressColor;
        out.borderColor = borderColor;
        out.textColor = textColor;
        out.textSizeSp = textSizeSp;
        out.motionMode = motionMode;
        out.cpsEnabled = cpsEnabled;
        out.cpsTemplate = cpsTemplate;
        out.centerXPx = centerXPx;
        out.centerYPx = centerYPx;
        out.positionSet = positionSet;
        return out;
    }

    static boolean isValidCpsTemplate(String template) {
        return template != null && template.contains(CPS_PLACEHOLDER);
    }

    String renderCps(int cpsValue) {
        String template = cpsTemplate == null ? "" : cpsTemplate;
        String value = String.valueOf(cpsValue);
        // Quotation marks around the placeholder are treated as template notation,
        // so 不知道:"(cps)" renders as 不知道:10 instead of 不知道:"10".
        return template.replace("\"" + CPS_PLACEHOLDER + "\"", value)
                .replace(CPS_PLACEHOLDER, value);
    }
}
