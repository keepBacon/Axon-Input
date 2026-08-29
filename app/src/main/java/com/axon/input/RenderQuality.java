package com.axon.input;

import org.json.JSONObject;

/** Shared render-quality policy for Live2D and keyboard-cat WebGL runtimes. */
final class RenderQuality {
    private static final long MIB = 1024L * 1024L;

    private RenderQuality() {}

    static int normalize(int quality) {
        return quality == OverlayState.RENDER_QUALITY_CLEAR
                ? OverlayState.RENDER_QUALITY_CLEAR
                : OverlayState.RENDER_QUALITY_NORMAL;
    }

    static String jsName(int quality) {
        return normalize(quality) == OverlayState.RENDER_QUALITY_CLEAR ? "clear" : "normal";
    }

    /**
     * Keep Normal exactly on the existing adaptive runtime budget. Clear raises framebuffer DPR
     * while still respecting heavy-model texture pressure so choosing clarity cannot blindly force
     * a 3x full-screen framebuffer on every device/model.
     */
    static void applyRuntimeConfig(JSONObject config, int quality) throws Exception {
        if (config == null) return;
        double normalCap = clampDpr(config.optDouble("renderDprCap", 2.0));
        long textureBytes = Math.max(0L, config.optLong("textureEstimatedRgbaBytes", 0L));
        double clearCap;
        if (textureBytes >= 96L * MIB) clearCap = Math.max(normalCap, 1.75);
        else if (textureBytes >= 72L * MIB) clearCap = Math.max(normalCap, 2.0);
        else if (textureBytes >= 48L * MIB) clearCap = Math.max(normalCap, 2.25);
        else if (textureBytes >= 24L * MIB) clearCap = Math.max(normalCap, 2.5);
        else clearCap = 3.0;
        clearCap = clampDpr(clearCap);

        int normalized = normalize(quality);
        config.put("renderQuality", jsName(normalized));
        config.put("renderDprCapNormal", normalCap);
        config.put("renderDprCapClear", clearCap);
        config.put("renderDprCap", normalized == OverlayState.RENDER_QUALITY_CLEAR ? clearCap : normalCap);
    }

    static double builtinNormalDprCap() { return 2.0; }
    static double builtinClearDprCap() { return 3.0; }

    private static double clampDpr(double value) {
        if (!Double.isFinite(value)) return 2.0;
        return Math.max(1.0, Math.min(3.0, value));
    }
}
