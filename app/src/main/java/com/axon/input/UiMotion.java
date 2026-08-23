package com.axon.input;

import android.animation.LayoutTransition;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * Small, shared interaction primitives for the settings UI.
 * Motion is intentionally short: feedback first, decoration second.
 */
public final class UiMotion {
    private static final long PRESS_MS = 110L;
    private static final long RELEASE_MS = 140L;
    private static final long DETAILS_MS = 180L;
    private static final long PAGE_MS = 190L;

    private static final Interpolator EASE_OUT = new PathInterpolator(0.23f, 1f, 0.32f, 1f);
    private static final Interpolator EASE_MOVE = new PathInterpolator(0.32f, 0.72f, 0f, 1f);
    // Fast response with a long, controlled deceleration. No slow ease-in phase.
    private static final Interpolator PAGE_EASE = new PathInterpolator(0.20f, 0.92f, 0.20f, 1f);

    private UiMotion() {}

    /** Adds a subtle physical press response without consuming the click. */
    public static void bindPressFeedback(View view) {
        if (view == null) return;
        view.setOnTouchListener((target, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    target.animate().cancel();
                    target.animate()
                            .scaleX(0.985f)
                            .scaleY(0.985f)
                            .setDuration(PRESS_MS)
                            .setInterpolator(EASE_OUT)
                            .start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    target.animate().cancel();
                    target.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(RELEASE_MS)
                            .setInterpolator(EASE_OUT)
                            .start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** Enables compact bounds animation for low-frequency detail expansion. */
    public static void enableLayoutMotion(ViewGroup group) {
        if (group == null) return;
        LayoutTransition transition = new LayoutTransition();
        transition.setDuration(DETAILS_MS);
        transition.setInterpolator(LayoutTransition.CHANGE_APPEARING, EASE_MOVE);
        transition.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, EASE_MOVE);
        transition.setInterpolator(LayoutTransition.APPEARING, EASE_OUT);
        transition.setInterpolator(LayoutTransition.DISAPPEARING, EASE_OUT);
        transition.setStartDelay(LayoutTransition.APPEARING, 0L);
        transition.setStartDelay(LayoutTransition.DISAPPEARING, 0L);
        if (Build.VERSION.SDK_INT >= 16) {
            transition.enableTransitionType(LayoutTransition.CHANGING);
            transition.setInterpolator(LayoutTransition.CHANGING, EASE_MOVE);
        }
        group.setLayoutTransition(transition);
    }

    /** Shows or hides a details panel. Its parent owns the low-frequency bounds/fade transition. */
    public static void setDetailsVisible(View details, boolean visible, boolean animated) {
        if (details == null) return;
        int target = visible ? View.VISIBLE : View.GONE;
        if (details.getVisibility() == target) return;

        details.animate().cancel();
        details.setAlpha(1f);
        details.setTranslationY(0f);

        if (!animated && details.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) details.getParent();
            LayoutTransition transition = parent.getLayoutTransition();
            parent.setLayoutTransition(null);
            details.setVisibility(target);
            parent.setLayoutTransition(transition);
            return;
        }
        details.setVisibility(target);
    }

    public static void rotateDisclosure(View view, boolean expanded, boolean animated) {
        if (view == null) return;
        float target = expanded ? 180f : 0f;
        if (!animated) {
            view.animate().cancel();
            view.setRotation(target);
            return;
        }
        view.animate().cancel();
        view.animate()
                .rotation(target)
                .setDuration(150L)
                .setInterpolator(EASE_OUT)
                .start();
    }
    /**
     * Smooth top-level page transition using two already-laid-out page surfaces.
     *
     * Unlike the previous implementation, content is never swapped/re-laid-out at the
     * midpoint of the animation. Both pages move in the same frame window, which removes
     * the visible hitch caused by VISIBLE/GONE changes on a large ScrollView tree.
     */
    public static void animatePageTransition(
            View outgoing, View incoming, int direction, Runnable onComplete) {
        if (incoming == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        if (outgoing == null || !outgoing.isLaidOut() || !incoming.isLaidOut()) {
            if (outgoing != null) outgoing.setVisibility(View.GONE);
            incoming.setVisibility(View.VISIBLE);
            incoming.setTranslationX(0f);
            incoming.setAlpha(1f);
            if (onComplete != null) onComplete.run();
            return;
        }

        final float density = incoming.getResources().getDisplayMetrics().density;
        final float sign = direction >= 0 ? 1f : -1f;
        final float incomingTravel = 28f * density;
        final float outgoingTravel = 8f * density;

        outgoing.animate().cancel();
        incoming.animate().cancel();

        outgoing.setVisibility(View.VISIBLE);
        outgoing.setTranslationX(0f);
        outgoing.setAlpha(1f);

        incoming.setVisibility(View.VISIBLE);
        incoming.setTranslationX(sign * incomingTravel);
        incoming.setAlpha(1f);

        // The page trees are stable for the whole transition. Temporary hardware layers
        // keep transform animation off the expensive view hierarchy/layout path.
        outgoing.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        incoming.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        outgoing.animate()
                .translationX(-sign * outgoingTravel)
                .setDuration(PAGE_MS)
                .setInterpolator(PAGE_EASE)
                .start();

        incoming.animate()
                .translationX(0f)
                .setDuration(PAGE_MS)
                .setInterpolator(PAGE_EASE)
                .withEndAction(() -> {
                    outgoing.animate().cancel();
                    incoming.animate().cancel();
                    outgoing.setTranslationX(0f);
                    incoming.setTranslationX(0f);
                    outgoing.setAlpha(1f);
                    incoming.setAlpha(1f);
                    outgoing.setLayerType(View.LAYER_TYPE_NONE, null);
                    incoming.setLayerType(View.LAYER_TYPE_NONE, null);
                    if (onComplete != null) onComplete.run();
                })
                .start();
    }

    /** Cancels page motion and restores transform/layer state for every supplied page. */
    public static void cancelPageTransition(View... pages) {
        if (pages == null) return;
        for (View page : pages) {
            if (page == null) continue;
            page.animate().cancel();
            page.setTranslationX(0f);
            page.setAlpha(1f);
            page.setLayerType(View.LAYER_TYPE_NONE, null);
        }
    }

}
