package com.axon.input;

import android.os.SystemClock;

/**
 * Lock-free process-local handoff for camera-derived Live2D full-body motion values.
 * Normalized signed values use [-1, 1], unit values use [0, 1].
 */
public final class Live2DMotionTracker {
    public static final class Sample {
        public final long generation;
        public final long timestampMs;
        public final boolean detected;
        public final float bodyX;
        public final float bodyY;
        public final float crouch;
        public final float leftArmUp;
        public final float rightArmUp;
        public final float leftHandX;
        public final float leftHandY;
        public final float rightHandX;
        public final float rightHandY;
        public final float leftLegMotion;
        public final float rightLegMotion;
        public final float confidence;

        private Sample(long generation, long timestampMs, boolean detected,
                       float bodyX, float bodyY, float crouch,
                       float leftArmUp, float rightArmUp,
                       float leftHandX, float leftHandY,
                       float rightHandX, float rightHandY,
                       float leftLegMotion, float rightLegMotion,
                       float confidence) {
            this.generation = generation;
            this.timestampMs = timestampMs;
            this.detected = detected;
            this.bodyX = bodyX;
            this.bodyY = bodyY;
            this.crouch = crouch;
            this.leftArmUp = leftArmUp;
            this.rightArmUp = rightArmUp;
            this.leftHandX = leftHandX;
            this.leftHandY = leftHandY;
            this.rightHandX = rightHandX;
            this.rightHandY = rightHandY;
            this.leftLegMotion = leftLegMotion;
            this.rightLegMotion = rightLegMotion;
            this.confidence = confidence;
        }
    }

    private static volatile Sample latest = empty(0L);
    private static long generation;

    private Live2DMotionTracker() {}

    public static synchronized void publish(
            float bodyX, float bodyY, float crouch,
            float leftArmUp, float rightArmUp,
            float leftHandX, float leftHandY,
            float rightHandX, float rightHandY,
            float leftLegMotion, float rightLegMotion,
            float confidence) {
        long now = SystemClock.elapsedRealtime();
        float nextBodyX = clampSigned(bodyX);
        float nextBodyY = clampSigned(bodyY);
        float nextCrouch = clampUnit(crouch);
        float nextLeftArm = clampUnit(leftArmUp);
        float nextRightArm = clampUnit(rightArmUp);
        float nextLeftHandX = clampSigned(leftHandX);
        float nextLeftHandY = clampSigned(leftHandY);
        float nextRightHandX = clampSigned(rightHandX);
        float nextRightHandY = clampSigned(rightHandY);
        float nextLeftLeg = clampUnit(leftLegMotion);
        float nextRightLeg = clampUnit(rightLegMotion);
        float nextConfidence = clampUnit(confidence);

        Sample current = latest;
        if (current.detected && now - current.timestampMs < 260L
                && maxDelta(current, nextBodyX, nextBodyY, nextCrouch,
                nextLeftArm, nextRightArm, nextLeftHandX, nextLeftHandY,
                nextRightHandX, nextRightHandY, nextLeftLeg, nextRightLeg,
                nextConfidence) < 0.012f) {
            return;
        }

        generation++;
        latest = new Sample(
                generation, now, true,
                nextBodyX, nextBodyY, nextCrouch,
                nextLeftArm, nextRightArm,
                nextLeftHandX, nextLeftHandY,
                nextRightHandX, nextRightHandY,
                nextLeftLeg, nextRightLeg, nextConfidence);
    }

    private static float maxDelta(
            Sample current, float bodyX, float bodyY, float crouch,
            float leftArm, float rightArm, float leftHandX, float leftHandY,
            float rightHandX, float rightHandY, float leftLeg, float rightLeg,
            float confidence) {
        float max = Math.abs(bodyX - current.bodyX);
        max = Math.max(max, Math.abs(bodyY - current.bodyY));
        max = Math.max(max, Math.abs(crouch - current.crouch));
        max = Math.max(max, Math.abs(leftArm - current.leftArmUp));
        max = Math.max(max, Math.abs(rightArm - current.rightArmUp));
        max = Math.max(max, Math.abs(leftHandX - current.leftHandX));
        max = Math.max(max, Math.abs(leftHandY - current.leftHandY));
        max = Math.max(max, Math.abs(rightHandX - current.rightHandX));
        max = Math.max(max, Math.abs(rightHandY - current.rightHandY));
        max = Math.max(max, Math.abs(leftLeg - current.leftLegMotion));
        max = Math.max(max, Math.abs(rightLeg - current.rightLegMotion));
        return Math.max(max, Math.abs(confidence - current.confidence));
    }

    public static synchronized void clear() {
        if (!latest.detected && latest.generation != 0L) return;
        generation++;
        latest = empty(generation);
    }

    public static Sample snapshot() {
        return latest;
    }

    private static Sample empty(long generation) {
        return new Sample(generation, SystemClock.elapsedRealtime(), false,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f,
                0f, 0f, 0f);
    }

    private static float clampSigned(float value) {
        return Math.max(-1f, Math.min(1f, value));
    }

    private static float clampUnit(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
