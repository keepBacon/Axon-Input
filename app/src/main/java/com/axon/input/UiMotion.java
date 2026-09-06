package com.axon.input;

import android.animation.LayoutTransition;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * Shared motion system for Axon settings surfaces.
 *
 * Rules:
 * 1) Motion is deliberately calm and readable rather than fast/snappy.
 * 2) Every property animation restarts from the value currently on screen, so a second
 *    gesture redirects the motion instead of snapping to an endpoint first.
 * 3) Spatial/state changes use one family of transforms and easing so the interface explains
 *    where state moved, just like the bottom navigation's moving selection surface.
 */
public final class UiMotion {
    private static final long PRESS_MS = 115L;
    private static final long RELEASE_MS = 130L;
    private static final long STATE_MS = 180L;
    private static final long ENTER_MS = 180L;
    private static final long EXIT_MS = 140L;
    private static final long DETAILS_MS = 200L;
    private static final long PAGE_MS = 220L;
    private static final long SPATIAL_MIN_MS = 150L;
    private static final long SPATIAL_MAX_MS = 240L;

    // First-frame response remains immediate; most of the time is spent decelerating into place.
    private static final Interpolator EASE_OUT = new PathInterpolator(0.16f, 1f, 0.30f, 1f);
    // Shared spatial curve for indicators, disclosure, page motion and state-surface movement.
    private static final Interpolator EASE_MOVE = new PathInterpolator(0.20f, 0.82f, 0.20f, 1f);

    private UiMotion() {}

    static Interpolator easeOut() { return EASE_OUT; }
    static Interpolator easeMove() { return EASE_MOVE; }
    static long pressMs() { return PRESS_MS; }
    static long releaseMs() { return RELEASE_MS; }
    static long stateMs() { return STATE_MS; }
    static long enterMs() { return ENTER_MS; }
    static long exitMs() { return EXIT_MS; }
    static long pageMs() { return PAGE_MS; }

    /** Adds a small, fast compression that always releases from the currently rendered scale. */
    public static void bindPressFeedback(View view) {
        if (view == null) return;
        view.setOnTouchListener((target, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    target.animate().cancel();
                    target.animate()
                            .scaleX(0.975f)
                            .scaleY(0.975f)
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

    /**
     * Short bounds movement for low-frequency expansion. Parent-hierarchy animation is disabled
     * so opening one row does not cause unrelated ancestors to drift at the same time.
     */
    public static void enableLayoutMotion(ViewGroup group) {
        if (group == null) return;
        LayoutTransition transition = new LayoutTransition();
        transition.setAnimateParentHierarchy(false);
        transition.setDuration(LayoutTransition.CHANGE_APPEARING, DETAILS_MS);
        transition.setDuration(LayoutTransition.CHANGE_DISAPPEARING, DETAILS_MS);
        transition.setDuration(LayoutTransition.APPEARING, ENTER_MS);
        transition.setDuration(LayoutTransition.DISAPPEARING, EXIT_MS);
        transition.setInterpolator(LayoutTransition.CHANGE_APPEARING, EASE_MOVE);
        transition.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, EASE_MOVE);
        transition.setInterpolator(LayoutTransition.APPEARING, EASE_OUT);
        transition.setInterpolator(LayoutTransition.DISAPPEARING, EASE_OUT);
        transition.setStartDelay(LayoutTransition.APPEARING, 0L);
        transition.setStartDelay(LayoutTransition.DISAPPEARING, 0L);
        transition.setStartDelay(LayoutTransition.CHANGE_APPEARING, 0L);
        transition.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0L);
        if (Build.VERSION.SDK_INT >= 16) {
            transition.enableTransitionType(LayoutTransition.CHANGING);
            transition.setDuration(LayoutTransition.CHANGING, DETAILS_MS);
            transition.setInterpolator(LayoutTransition.CHANGING, EASE_MOVE);
            transition.setStartDelay(LayoutTransition.CHANGING, 0L);
        }
        group.setLayoutTransition(transition);
    }

    /**
     * Visibility remains layout-driven, while the detail surface itself gets a small continuity
     * cue. A reversal begins from the current alpha/translation instead of replaying from zero.
     */
    public static void setDetailsVisible(View details, boolean visible, boolean animated) {
        if (details == null) return;
        int target = visible ? View.VISIBLE : View.GONE;
        if (details.getVisibility() == target && !animated) return;

        details.animate().cancel();
        if (!animated) {
            if (details.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) details.getParent();
                LayoutTransition transition = parent.getLayoutTransition();
                parent.setLayoutTransition(null);
                details.setVisibility(target);
                parent.setLayoutTransition(transition);
            } else {
                details.setVisibility(target);
            }
            details.setAlpha(1f);
            details.setTranslationY(0f);
            return;
        }

        final float dy = 4f * details.getResources().getDisplayMetrics().density;
        if (visible) {
            if (details.getVisibility() != View.VISIBLE) {
                details.setAlpha(Math.min(details.getAlpha(), 0.72f));
                details.setTranslationY(Math.max(details.getTranslationY(), dy));
                details.setVisibility(View.VISIBLE);
            }
            details.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(ENTER_MS)
                    .setInterpolator(EASE_OUT)
                    .start();
        } else {
            // GONE starts the parent bounds transition immediately; transforms are restored for
            // the next open so a rapid reopen never inherits a stale half-faded state.
            details.setVisibility(View.GONE);
            details.setAlpha(1f);
            details.setTranslationY(0f);
        }
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
                .setDuration(STATE_MS)
                .setInterpolator(EASE_MOVE)
                .start();
    }

    /**
     * Moves a shared selection/state surface from its current on-screen position. Repeated calls
     * cancel only the old destination; the current transform is retained and becomes the new start.
     */
    public static void moveSelectionSurface(View view, float targetX, float targetY) {
        if (view == null) return;
        float dx = targetX - view.getTranslationX();
        float dy = targetY - view.getTranslationY();
        float distance = (float) Math.hypot(dx, dy);
        float density = view.getResources().getDisplayMetrics().density;
        float reference = Math.max(view.getWidth(), 56f * density);
        float normalized = Math.min(1f, distance / Math.max(1f, reference));
        long duration = SPATIAL_MIN_MS
                + Math.round((SPATIAL_MAX_MS - SPATIAL_MIN_MS) * (float) Math.sqrt(normalized));

        view.animate().cancel();
        view.animate()
                .translationX(targetX)
                .translationY(targetY)
                .setDuration(duration)
                .setInterpolator(EASE_MOVE)
                .start();
    }

    /**
     * Two-surface page motion. It deliberately preserves a page's current transform when a prior
     * page animation is interrupted, so the next selection redirects from what the user can see.
     */
    public static void animatePageTransition(
            View outgoing, View incoming, int direction, Runnable onComplete) {
        if (incoming == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        if (outgoing == null || !outgoing.isLaidOut() || !incoming.isLaidOut()) {
            if (outgoing != null) outgoing.setVisibility(View.INVISIBLE);
            incoming.setVisibility(View.VISIBLE);
            incoming.setTranslationX(0f);
            incoming.setAlpha(1f);
            if (onComplete != null) onComplete.run();
            return;
        }

        final float density = incoming.getResources().getDisplayMetrics().density;
        final float sign = direction >= 0 ? 1f : -1f;
        final float incomingTravel = 24f * density;
        final float outgoingTravel = 10f * density;
        final boolean incomingWasVisible = incoming.getVisibility() == View.VISIBLE;

        outgoing.animate().cancel();
        incoming.animate().cancel();

        outgoing.setVisibility(View.VISIBLE);
        if (!incomingWasVisible) {
            incoming.setTranslationX(sign * incomingTravel);
            incoming.setAlpha(0.88f);
        }
        incoming.setVisibility(View.VISIBLE);

        outgoing.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        incoming.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        outgoing.animate()
                .translationX(-sign * outgoingTravel)
                .alpha(0.90f)
                .setDuration(PAGE_MS)
                .setInterpolator(EASE_MOVE)
                .start();

        incoming.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(PAGE_MS)
                .setInterpolator(EASE_MOVE)
                .withEndAction(() -> {
                    incoming.setTranslationX(0f);
                    incoming.setAlpha(1f);
                    outgoing.setLayerType(View.LAYER_TYPE_NONE, null);
                    incoming.setLayerType(View.LAYER_TYPE_NONE, null);
                    if (onComplete != null) onComplete.run();
                })
                .start();
    }

    /** Cancels active page animators but intentionally preserves the current visual state. */
    public static void interruptPageTransition(View... pages) {
        if (pages == null) return;
        for (View page : pages) {
            if (page == null) continue;
            page.animate().cancel();
            page.setLayerType(View.LAYER_TYPE_NONE, null);
        }
    }

    /** Full reset used only after a transition has really reached its destination. */
    public static void resetPageMotion(View page) {
        if (page == null) return;
        page.animate().cancel();
        page.setTranslationX(0f);
        page.setTranslationY(0f);
        page.setAlpha(1f);
        page.setLayerType(View.LAYER_TYPE_NONE, null);
    }
}
