package com.axon.input;

import android.content.Context;

/** Shared floating-media sizing so editor and runtime always use identical geometry. */
final class FloatingMediaLayout {
    private FloatingMediaLayout() {}

    static int[] sizePx(Context context, FloatingMediaStore.Item item) {
        int sourceWidth = Math.max(1, item == null ? 16 : item.sourceWidth);
        int sourceHeight = Math.max(1, item == null ? 9 : item.sourceHeight);
        float aspect = sourceWidth / (float) sourceHeight;
        if (!Float.isFinite(aspect) || aspect <= 0.05f || aspect >= 20f) aspect = 16f / 9f;
        float scale = Math.max(0.5f, Math.min(3f,
                (item == null ? 100 : item.sizePercent) / 100f));
        float maxWidthDp = 260f * scale;
        float maxHeightDp = 220f * scale;
        float widthDp = maxWidthDp;
        float heightDp = widthDp / aspect;
        if (heightDp > maxHeightDp) {
            heightDp = maxHeightDp;
            widthDp = heightDp * aspect;
        }
        widthDp = Math.max(48f, widthDp);
        heightDp = Math.max(36f, heightDp);
        float density = context.getResources().getDisplayMetrics().density;
        return new int[]{
                Math.max(1, Math.round(widthDp * density)),
                Math.max(1, Math.round(heightDp * density))
        };
    }
}
