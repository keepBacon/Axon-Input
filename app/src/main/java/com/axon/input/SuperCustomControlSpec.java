package com.axon.input;

import android.graphics.Color;

/** Mutable editor state for one super-custom display component. */
final class SuperCustomControlSpec {
    static final int TYPE_KEY = 0;
    static final int TYPE_TEXT = 1;
    static final int TYPE_STICK = 2;

    static final int STICK_LEFT = 0;
    static final int STICK_RIGHT = 1;

    static final String CPS_PLACEHOLDER = "(cps)";
    static final String CPS_TEMPLATE = "cps:\"(cps)\"";

    int controlType = TYPE_KEY;
    // 编辑器稳定标识。旧配置加载时由 ConfigStore 补齐，避免数组顺序变化破坏绑定关系。
    long controlId;
    long bindingGroupId;
    int bindingGroupIndex;
    boolean bindingAnchor;
    int keyCode;
    String labelText;
    int widthDp = 92;
    int heightDp = 64;
    int cornerDp = 14;
    int opacityPercent = 94;
    int diffusionOpacityPercent = 100;
    int backgroundOpacityPercent = 100;
    int borderOpacityPercent = 100;
    int textOpacityPercent = 100;
    int borderWidthDp = 1;
    int baseColor = 0; // 0 = resolve from current UI palette, preserving v1-v4 configs.
    String baseColors = "";
    int pressColor = Color.rgb(64, 64, 68);
    String pressColors = "";
    int borderColor = 0; // 0 = resolve from current UI palette for backward-compatible key configs.
    String borderColors = "";
    int textColor = Color.WHITE;
    String textColors = "";
    int textSizeSp = 18;
    int motionMode = OverlayState.MOTION_SIZE;
    int stickSide = STICK_LEFT;
    int stickDotSizePercent = 34;
    int stickDotCornerPercent = 100;
    boolean cpsEnabled;
    String cpsTemplate = CPS_TEMPLATE;

    // Text-component-only appearance. These fields are ignored by key components.
    boolean textStrokeEnabled;
    int textStrokeColor = Color.BLACK;
    String textStrokeColors = "";
    int textStrokeWidthDp = 2;

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

    static SuperCustomControlSpec createText(boolean darkTheme) {
        SuperCustomControlSpec spec = new SuperCustomControlSpec(-1, "文本", darkTheme);
        spec.controlType = TYPE_TEXT;
        spec.widthDp = 160;
        spec.heightDp = 56;
        spec.cornerDp = 0;
        spec.opacityPercent = 100;
        spec.diffusionOpacityPercent = 100;
        spec.motionMode = OverlayState.MOTION_NONE;
        spec.cpsEnabled = false;
        spec.textSizeSp = 28;
        spec.textStrokeEnabled = false;
        spec.textStrokeWidthDp = 2;
        spec.textStrokeColor = darkTheme ? Color.BLACK : Color.WHITE;
        return spec;
    }


    static SuperCustomControlSpec createStick(int side, boolean darkTheme) {
        int normalizedSide = side == STICK_RIGHT ? STICK_RIGHT : STICK_LEFT;
        SuperCustomControlSpec spec = new SuperCustomControlSpec(-1,
                normalizedSide == STICK_RIGHT ? "右摇杆" : "左摇杆", darkTheme);
        spec.controlType = TYPE_STICK;
        spec.stickSide = normalizedSide;
        spec.widthDp = 124;
        spec.heightDp = 124;
        // 摇杆的 cornerDp 在该类型下表示 0..100 的背景圆角强度，而不是按键的绝对 dp 半径。
        spec.cornerDp = 100;
        spec.opacityPercent = 100;
        spec.diffusionOpacityPercent = 100;
        spec.motionMode = OverlayState.MOTION_NONE;
        spec.cpsEnabled = false;
        spec.stickDotSizePercent = 34;
        spec.stickDotCornerPercent = 100;
        if (darkTheme) {
            spec.pressColor = Color.rgb(38, 38, 42);
            spec.textColor = Color.rgb(238, 238, 242);
        } else {
            spec.pressColor = Color.rgb(236, 236, 240);
            spec.textColor = Color.rgb(40, 40, 44);
        }
        return spec;
    }

    boolean isTextElement() {
        return controlType == TYPE_TEXT;
    }

    boolean isKeyElement() {
        return controlType == TYPE_KEY;
    }

    boolean isStickElement() {
        return controlType == TYPE_STICK;
    }

    SuperCustomControlSpec copy() {
        SuperCustomControlSpec out = new SuperCustomControlSpec(keyCode, labelText, false);
        out.controlType = controlType;
        out.controlId = controlId;
        out.bindingGroupId = bindingGroupId;
        out.bindingGroupIndex = bindingGroupIndex;
        out.bindingAnchor = bindingAnchor;
        out.widthDp = widthDp;
        out.heightDp = heightDp;
        out.cornerDp = cornerDp;
        out.opacityPercent = opacityPercent;
        out.diffusionOpacityPercent = diffusionOpacityPercent;
        out.backgroundOpacityPercent = backgroundOpacityPercent;
        out.borderOpacityPercent = borderOpacityPercent;
        out.textOpacityPercent = textOpacityPercent;
        out.borderWidthDp = borderWidthDp;
        out.baseColor = baseColor;
        out.baseColors = baseColors;
        out.pressColor = pressColor;
        out.pressColors = pressColors;
        out.borderColor = borderColor;
        out.borderColors = borderColors;
        out.textColor = textColor;
        out.textColors = textColors;
        out.textSizeSp = textSizeSp;
        out.motionMode = motionMode;
        out.stickSide = stickSide;
        out.stickDotSizePercent = stickDotSizePercent;
        out.stickDotCornerPercent = stickDotCornerPercent;
        out.cpsEnabled = cpsEnabled;
        out.cpsTemplate = cpsTemplate;
        out.textStrokeEnabled = textStrokeEnabled;
        out.textStrokeColor = textStrokeColor;
        out.textStrokeColors = textStrokeColors;
        out.textStrokeWidthDp = textStrokeWidthDp;
        out.centerXPx = centerXPx;
        out.centerYPx = centerYPx;
        out.positionSet = positionSet;
        return out;
    }


    int currentBaseColor(int fallback) { return ColorSequence.current(baseColors, baseColor != 0 ? baseColor : fallback); }
    int currentPressColor() { return ColorSequence.current(pressColors, pressColor); }
    int currentBorderColor(int fallback) { return ColorSequence.current(borderColors, borderColor != 0 ? borderColor : fallback); }
    int currentTextColor() { return ColorSequence.current(textColors, textColor); }
    int currentTextStrokeColor() { return ColorSequence.current(textStrokeColors, textStrokeColor); }
    boolean hasAnimatedColors() {
        return ColorSequence.animated(baseColors) || ColorSequence.animated(pressColors)
                || ColorSequence.animated(borderColors) || ColorSequence.animated(textColors)
                || ColorSequence.animated(textStrokeColors);
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
