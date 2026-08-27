package com.axon.input;

import android.media.Image;

import java.nio.ByteBuffer;

/**
 * Lightweight dependency-free Live2D body-motion estimator.
 * Camera2 face bounds are used only as a stable body anchor/skin sampling region; no facial
 * expression, eye, mouth or head-pose output is produced.
 */
final class Live2DMotionEstimator {
    static final class FaceGeometry {
        final float centerX;
        final float centerY;
        final float width;
        final float height;
        final float confidence;
        final long timestampMs;

        FaceGeometry(float centerX, float centerY, float width, float height,
                     float confidence, long timestampMs) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
            this.confidence = confidence;
            this.timestampMs = timestampMs;
        }
    }

    static final class Result {
        final float bodyX;
        final float bodyY;
        final float crouch;
        final float leftArmUp;
        final float rightArmUp;
        final float leftHandX;
        final float leftHandY;
        final float rightHandX;
        final float rightHandY;
        final float leftLegMotion;
        final float rightLegMotion;
        final float confidence;

        Result(float bodyX, float bodyY, float crouch,
               float leftArmUp, float rightArmUp,
               float leftHandX, float leftHandY,
               float rightHandX, float rightHandY,
               float leftLegMotion, float rightLegMotion,
               float confidence) {
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

    private boolean hasSkin;
    private float skinU;
    private float skinV;
    private boolean neutralReady;
    private int neutralFrames;
    private float neutralX;
    private float neutralY;
    private float neutralSize;

    private float smoothBodyX;
    private float smoothBodyY;
    private float smoothCrouch;
    private float smoothLeftArm;
    private float smoothRightArm;
    private float smoothLeftHandX;
    private float smoothLeftHandY;
    private float smoothRightHandX;
    private float smoothRightHandY;
    private float smoothLeftLeg;
    private float smoothRightLeg;

    private byte[] previousLuma;
    private short[] lumaDeltaScratch;
    private int previousGridW;
    private int previousGridH;
    private int frameCounter;
    private boolean leftHandLocked;
    private boolean rightHandLocked;
    private int leftHandAcquireFrames;
    private int rightHandAcquireFrames;
    private int leftHandMissFrames;
    private int rightHandMissFrames;
    // Reused hot-path buffers. The previous implementation allocated a float[2] for every sampled
    // pixel, which caused frequent GC pauses on 320/480p camera frames.
    private final float[] pointScratch = new float[2];
    private final float[] legScratch = new float[2];

    Result process(Image image, FaceGeometry face, int relativeRotation, boolean frontFacing) {
        return process(image, face, relativeRotation, frontFacing, true);
    }

    Result process(Image image, FaceGeometry face, int relativeRotation, boolean frontFacing, boolean fullMotion) {
        if (image == null || face == null) return null;
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0) return null;
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) return null;

        frameCounter++;
        toUpright(face.centerX, face.centerY, relativeRotation, frontFacing, pointScratch);
        float faceX = pointScratch[0];
        float faceY = pointScratch[1];
        float faceSize = Math.max(0.035f, Math.max(face.width, face.height));

        updateNeutral(faceX, faceY, faceSize);
        HandEstimate hands = HandEstimate.EMPTY;
        if (fullMotion) {
            // Skin chroma drifts slowly; recalibrating every frame wastes bandwidth without improving
            // hand tracking. Keep the fast bootstrap, then refresh at 1/3 of the motion rate.
            if (!hasSkin || frameCounter % 3 == 0) updateSkinModel(image, face);
            hands = hasSkin
                    ? estimateHands(image, faceX, faceY, faceSize, relativeRotation, frontFacing)
                    : HandEstimate.EMPTY;
            hands = stabilizeHandTargets(hands);
            // Lower-body energy is visually low-frequency. Sample it every other analysis frame and
            // decay in-between instead of scanning the whole frame 15 times per second.
            if ((frameCounter & 1) == 0) {
                estimateLowerBodyMotion(image, relativeRotation, frontFacing, legScratch);
            } else {
                legScratch[0] *= 0.90f;
                legScratch[1] *= 0.90f;
            }
        } else {
            // Detailed face mode should stay lightweight: do not scan the rest of the frame for
            // skin-color hands or lower-body motion unless full-motion capture is actually enabled.
            legScratch[0] = legScratch[1] = 0f;
        }
        float bodyX = neutralReady ? clampSigned((faceX - neutralX) * 3.25f) : 0f;
        float bodyY = neutralReady ? clampSigned((neutralY - faceY) * 3.10f) : 0f;
        float sizeDelta = neutralReady ? (neutralSize - faceSize) / Math.max(0.035f, neutralSize) : 0f;
        float crouch = neutralReady
                ? clampUnit((faceY - neutralY) * 4.0f + Math.max(0f, sizeDelta) * 0.55f)
                : 0f;

        // Camera/YUV estimates occasionally contain a single bad centroid or color sample. Plain
        // exponential smoothing still reacts to that spike. First bound target velocity, then apply
        // the normal low-pass. This removes one-frame kicks without adding heavy global latency.
        bodyX = stabilizeSigned(smoothBodyX, bodyX, 0.18f, 0.012f);
        bodyY = stabilizeSigned(smoothBodyY, bodyY, 0.18f, 0.012f);
        crouch = stabilizeUnit(smoothCrouch, crouch, 0.20f, 0.012f);
        final float bodyAlpha = 0.24f;
        smoothBodyX = smooth(smoothBodyX, bodyX, bodyAlpha);
        smoothBodyY = smooth(smoothBodyY, bodyY, bodyAlpha);
        smoothCrouch = smooth(smoothCrouch, crouch, 0.20f);

        float leftArmTarget = hands.leftFound
                ? stabilizeUnit(smoothLeftArm, hands.leftArmUp, 0.25f, 0.018f)
                : smoothLeftArm * 0.86f;
        float rightArmTarget = hands.rightFound
                ? stabilizeUnit(smoothRightArm, hands.rightArmUp, 0.25f, 0.018f)
                : smoothRightArm * 0.86f;
        float leftHandXTarget = hands.leftFound
                ? stabilizeSigned(smoothLeftHandX, hands.leftX, 0.24f, 0.018f)
                : smoothLeftHandX * 0.86f;
        float leftHandYTarget = hands.leftFound
                ? stabilizeSigned(smoothLeftHandY, hands.leftY, 0.24f, 0.018f)
                : smoothLeftHandY * 0.86f;
        float rightHandXTarget = hands.rightFound
                ? stabilizeSigned(smoothRightHandX, hands.rightX, 0.24f, 0.018f)
                : smoothRightHandX * 0.86f;
        float rightHandYTarget = hands.rightFound
                ? stabilizeSigned(smoothRightHandY, hands.rightY, 0.24f, 0.018f)
                : smoothRightHandY * 0.86f;
        smoothLeftArm = smooth(smoothLeftArm, leftArmTarget, hands.leftFound ? 0.30f : 0.10f);
        smoothRightArm = smooth(smoothRightArm, rightArmTarget, hands.rightFound ? 0.30f : 0.10f);
        smoothLeftHandX = smooth(smoothLeftHandX, leftHandXTarget, hands.leftFound ? 0.28f : 0.10f);
        smoothLeftHandY = smooth(smoothLeftHandY, leftHandYTarget, hands.leftFound ? 0.28f : 0.10f);
        smoothRightHandX = smooth(smoothRightHandX, rightHandXTarget, hands.rightFound ? 0.28f : 0.10f);
        smoothRightHandY = smooth(smoothRightHandY, rightHandYTarget, hands.rightFound ? 0.28f : 0.10f);

        float leftLegTarget = stabilizeUnit(smoothLeftLeg, legScratch[0], 0.24f, 0.018f);
        float rightLegTarget = stabilizeUnit(smoothRightLeg, legScratch[1], 0.24f, 0.018f);
        smoothLeftLeg = smooth(smoothLeftLeg, leftLegTarget, 0.22f);
        smoothRightLeg = smooth(smoothRightLeg, rightLegTarget, 0.22f);

        float handQuality = Math.min(1f, (hands.leftCount + hands.rightCount) / 50f);
        float confidence = clampUnit(face.confidence * (0.72f + 0.28f * handQuality));
        return new Result(
                smoothBodyX, smoothBodyY, smoothCrouch,
                smoothLeftArm, smoothRightArm,
                smoothLeftHandX, smoothLeftHandY,
                smoothRightHandX, smoothRightHandY,
                smoothLeftLeg, smoothRightLeg,
                confidence);
    }


    void reset() {
        hasSkin = false;
        skinU = skinV = 0f;
        neutralReady = false;
        neutralFrames = 0;
        neutralX = neutralY = neutralSize = 0f;
        smoothBodyX = smoothBodyY = smoothCrouch = 0f;
        smoothLeftArm = smoothRightArm = 0f;
        smoothLeftHandX = smoothLeftHandY = smoothRightHandX = smoothRightHandY = 0f;
        smoothLeftLeg = smoothRightLeg = 0f;
        previousLuma = null;
        lumaDeltaScratch = null;
        previousGridW = previousGridH = 0;
        frameCounter = 0;
        leftHandLocked = rightHandLocked = false;
        leftHandAcquireFrames = rightHandAcquireFrames = 0;
        leftHandMissFrames = rightHandMissFrames = 0;
        legScratch[0] = legScratch[1] = 0f;
    }

    private void updateNeutral(float x, float y, float size) {
        if (!neutralReady) {
            neutralFrames++;
            float a = neutralFrames <= 1 ? 1f : 0.18f;
            neutralX = smooth(neutralX, x, a);
            neutralY = smooth(neutralY, y, a);
            neutralSize = smooth(neutralSize, size, a);
            if (neutralFrames >= 10) neutralReady = true;
            return;
        }
        // Very slow drift correction prevents normal seating changes from becoming a permanent pose,
        // while deliberate short movements still reach the Live2D model immediately.
        neutralX = smooth(neutralX, x, 0.004f);
        neutralY = smooth(neutralY, y, 0.004f);
        neutralSize = smooth(neutralSize, size, 0.004f);
    }

    private void updateSkinModel(Image image, FaceGeometry face) {
        Image.Plane uPlane = image.getPlanes()[1];
        Image.Plane vPlane = image.getPlanes()[2];
        ByteBuffer u = uPlane.getBuffer();
        ByteBuffer v = vPlane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();

        int x0 = clampInt((int) ((face.centerX - face.width * 0.22f) * width), 0, width - 1);
        int x1 = clampInt((int) ((face.centerX + face.width * 0.22f) * width), 0, width - 1);
        int y0 = clampInt((int) ((face.centerY - face.height * 0.05f) * height), 0, height - 1);
        int y1 = clampInt((int) ((face.centerY + face.height * 0.30f) * height), 0, height - 1);
        if (x1 <= x0 || y1 <= y0) return;

        long sumU = 0L;
        long sumV = 0L;
        int count = 0;
        int step = Math.max(5, Math.min(width, height) / 88);
        for (int y = y0; y <= y1; y += step) {
            for (int x = x0; x <= x1; x += step) {
                int uu = sampleChroma(u, uPlane, x, y);
                int vv = sampleChroma(v, vPlane, x, y);
                if (uu < 0 || vv < 0) continue;
                sumU += uu;
                sumV += vv;
                count++;
            }
        }
        if (count < 8) return;
        float targetU = sumU / (float) count;
        float targetV = sumV / (float) count;
        if (!hasSkin) {
            skinU = targetU;
            skinV = targetV;
            hasSkin = true;
        } else {
            skinU = smooth(skinU, targetU, 0.08f);
            skinV = smooth(skinV, targetV, 0.08f);
        }
    }

    private HandEstimate estimateHands(Image image, float faceX, float faceY, float faceSize,
                                       int rotation, boolean frontFacing) {
        Image.Plane yPlane = image.getPlanes()[0];
        Image.Plane uPlane = image.getPlanes()[1];
        Image.Plane vPlane = image.getPlanes()[2];
        ByteBuffer yBuffer = yPlane.getBuffer();
        ByteBuffer uBuffer = uPlane.getBuffer();
        ByteBuffer vBuffer = vPlane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int step = Math.max(6, Math.min(width, height) / 64);
        float threshold2 = 25f * 25f;

        Accumulator left = new Accumulator();
        Accumulator right = new Accumulator();
        float shoulderY = faceY + faceSize * 0.78f;
        float leftShoulderX = faceX - faceSize * 0.82f;
        float rightShoulderX = faceX + faceSize * 0.82f;

        for (int py = step; py < height - step; py += step) {
            for (int px = step; px < width - step; px += step) {
                int lum = sampleLuma(yBuffer, yPlane, px, py);
                if (lum < 28 || lum > 248) continue;
                int uu = sampleChroma(uBuffer, uPlane, px, py);
                int vv = sampleChroma(vBuffer, vPlane, px, py);
                if (uu < 0 || vv < 0) continue;
                float du = uu - skinU;
                float dv = vv - skinV;
                float dist2 = du * du + dv * dv;
                if (dist2 > threshold2) continue;

                toUpright(px / (float) width, py / (float) height, rotation, frontFacing, pointScratch);
                float ux = pointScratch[0];
                float uy = pointScratch[1];
                if (uy < faceY - faceSize * 0.45f || uy > Math.min(0.98f, faceY + faceSize * 4.8f)) continue;
                float horizontal = Math.abs(ux - faceX);
                if (horizontal < faceSize * 0.58f || horizontal > faceSize * 4.8f) continue;

                // Weight better skin matches slightly more. It stabilizes the centroid when hair or
                // warm background colors are near the calibrated chroma.
                float weight = 1f - Math.min(0.75f, dist2 / threshold2);
                if (ux < faceX) left.add(ux, uy, weight);
                else right.add(ux, uy, weight);
            }
        }

        boolean leftFound = left.count >= 5;
        boolean rightFound = right.count >= 5;
        float lx = leftFound ? left.meanX() : 0f;
        float ly = leftFound ? left.meanY() : 0f;
        float rx = rightFound ? right.meanX() : 0f;
        float ry = rightFound ? right.meanY() : 0f;

        float leftArmUp = leftFound
                ? clampUnit((shoulderY - ly) / Math.max(0.06f, faceSize * 1.8f) + 0.18f)
                : 0f;
        float rightArmUp = rightFound
                ? clampUnit((shoulderY - ry) / Math.max(0.06f, faceSize * 1.8f) + 0.18f)
                : 0f;
        float leftX = leftFound
                ? clampSigned((lx - leftShoulderX) / Math.max(0.08f, faceSize * 2.8f))
                : 0f;
        float leftY = leftFound
                ? clampSigned((shoulderY - ly) / Math.max(0.08f, faceSize * 2.4f))
                : 0f;
        float rightX = rightFound
                ? clampSigned((rx - rightShoulderX) / Math.max(0.08f, faceSize * 2.8f))
                : 0f;
        float rightY = rightFound
                ? clampSigned((shoulderY - ry) / Math.max(0.08f, faceSize * 2.4f))
                : 0f;

        return new HandEstimate(leftFound, rightFound,
                leftArmUp, rightArmUp, leftX, leftY, rightX, rightY,
                left.count, right.count);
    }

    private HandEstimate stabilizeHandTargets(HandEstimate raw) {
        boolean leftFound = raw.leftFound;
        boolean rightFound = raw.rightFound;

        if (leftFound) {
            float distance = (float) Math.hypot(raw.leftX - smoothLeftHandX, raw.leftY - smoothLeftHandY);
            if (leftHandLocked && distance > 0.58f) leftFound = false;
        }
        if (rightFound) {
            float distance = (float) Math.hypot(raw.rightX - smoothRightHandX, raw.rightY - smoothRightHandY);
            if (rightHandLocked && distance > 0.58f) rightFound = false;
        }

        if (!leftHandLocked) {
            leftHandAcquireFrames = leftFound ? leftHandAcquireFrames + 1 : 0;
            if (leftHandAcquireFrames >= 2) { leftHandLocked = true; leftHandMissFrames = 0; }
            else leftFound = false;
        } else if (leftFound) {
            leftHandMissFrames = 0;
        } else if (++leftHandMissFrames >= 5) {
            leftHandLocked = false;
            leftHandAcquireFrames = 0;
            leftHandMissFrames = 0;
        }

        if (!rightHandLocked) {
            rightHandAcquireFrames = rightFound ? rightHandAcquireFrames + 1 : 0;
            if (rightHandAcquireFrames >= 2) { rightHandLocked = true; rightHandMissFrames = 0; }
            else rightFound = false;
        } else if (rightFound) {
            rightHandMissFrames = 0;
        } else if (++rightHandMissFrames >= 5) {
            rightHandLocked = false;
            rightHandAcquireFrames = 0;
            rightHandMissFrames = 0;
        }

        return new HandEstimate(
                leftFound, rightFound,
                leftFound ? raw.leftArmUp : 0f, rightFound ? raw.rightArmUp : 0f,
                leftFound ? raw.leftX : 0f, leftFound ? raw.leftY : 0f,
                rightFound ? raw.rightX : 0f, rightFound ? raw.rightY : 0f,
                leftFound ? raw.leftCount : 0, rightFound ? raw.rightCount : 0);
    }

    private void estimateLowerBodyMotion(Image image, int rotation, boolean frontFacing, float[] out) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int step = Math.max(10, Math.min(width, height) / 36);
        int gw = Math.max(1, (width + step - 1) / step);
        int gh = Math.max(1, (height + step - 1) / step);
        int cells = gw * gh;
        if (previousLuma == null || previousGridW != gw || previousGridH != gh) {
            previousLuma = new byte[cells];
            lumaDeltaScratch = new short[cells];
            previousGridW = gw;
            previousGridH = gh;
        } else if (lumaDeltaScratch == null || lumaDeltaScratch.length != cells) {
            lumaDeltaScratch = new short[cells];
        }

        long globalSignedDelta = 0L;
        int globalCount = 0;
        int gy = 0;
        for (int py = 0; py < height; py += step, gy++) {
            int gx = 0;
            for (int px = 0; px < width; px += step, gx++) {
                int index = gy * gw + gx;
                int current = sampleLuma(buffer, plane, px, py);
                int previous = previousLuma[index] & 0xff;
                previousLuma[index] = (byte) current;
                if (previous == 0) {
                    lumaDeltaScratch[index] = 0;
                    continue;
                }
                int signedDelta = current - previous;
                lumaDeltaScratch[index] = (short) signedDelta;
                toUpright(px / (float) width, py / (float) height, rotation, frontFacing, pointScratch);
                if (pointScratch[1] < 0.48f) continue;
                globalSignedDelta += signedDelta;
                globalCount++;
            }
        }

        float exposureShift = globalCount == 0 ? 0f : globalSignedDelta / (float) globalCount;
        // A large same-direction frame-wide shift is auto exposure / white-balance settling, not
        // body movement. Subtract it before measuring local energy. This was a major source of
        // random full-body kicks in the previous implementation.
        float leftEnergy = 0f;
        float rightEnergy = 0f;
        int leftCount = 0;
        int rightCount = 0;
        gy = 0;
        for (int py = 0; py < height; py += step, gy++) {
            int gx = 0;
            for (int px = 0; px < width; px += step, gx++) {
                int index = gy * gw + gx;
                int signedDelta = lumaDeltaScratch[index];
                if (signedDelta == 0) continue;
                toUpright(px / (float) width, py / (float) height, rotation, frontFacing, pointScratch);
                if (pointScratch[1] < 0.56f) continue;
                float residual = Math.abs(signedDelta - exposureShift);
                if (residual < 3.5f) continue;
                float diff = Math.min(48f, residual);
                if (pointScratch[0] < 0.5f) {
                    leftEnergy += diff;
                    leftCount++;
                } else {
                    rightEnergy += diff;
                    rightCount++;
                }
            }
        }
        float left = leftCount == 0 ? 0f : clampUnit((leftEnergy / leftCount - 3.0f) / 20f);
        float right = rightCount == 0 ? 0f : clampUnit((rightEnergy / rightCount - 3.0f) / 20f);
        // Exposure/focus pulses usually hit both halves almost equally. Suppress the common-mode
        // component and keep only localized/asymmetric movement plus a small genuine shared term.
        float common = Math.min(left, right);
        if (common > 0.28f && Math.abs(left - right) < 0.16f) {
            left *= 0.18f;
            right *= 0.18f;
        } else {
            left = clampUnit(left - common * 0.35f);
            right = clampUnit(right - common * 0.35f);
        }
        out[0] = left;
        out[1] = right;
    }

    private static int sampleLuma(ByteBuffer buffer, Image.Plane plane, int x, int y) {
        int index = y * plane.getRowStride() + x * plane.getPixelStride();
        if (index < 0 || index >= buffer.limit()) return -1;
        return buffer.get(index) & 0xff;
    }

    private static int sampleChroma(ByteBuffer buffer, Image.Plane plane, int x, int y) {
        int cx = x >> 1;
        int cy = y >> 1;
        int index = cy * plane.getRowStride() + cx * plane.getPixelStride();
        if (index < 0 || index >= buffer.limit()) return -1;
        return buffer.get(index) & 0xff;
    }

    private static void toUpright(float x, float y, int degrees, boolean frontFacing, float[] out) {
        float cx = x * 2f - 1f;
        float cy = y * 2f - 1f;
        float rx;
        float ry;
        switch ((degrees % 360 + 360) % 360) {
            case 90:
                rx = -cy;
                ry = cx;
                break;
            case 180:
                rx = -cx;
                ry = -cy;
                break;
            case 270:
                rx = cy;
                ry = -cx;
                break;
            default:
                rx = cx;
                ry = cy;
                break;
        }
        float ux = (rx + 1f) * 0.5f;
        float uy = (ry + 1f) * 0.5f;
        if (frontFacing) ux = 1f - ux;
        out[0] = ux;
        out[1] = uy;
    }

    private static float stabilizeSigned(float current, float target, float maxStep, float deadZone) {
        float boundedTarget = clampSigned(target);
        float delta = boundedTarget - current;
        if (Math.abs(delta) <= deadZone) return current;
        if (delta > maxStep) delta = maxStep;
        else if (delta < -maxStep) delta = -maxStep;
        return clampSigned(current + delta);
    }

    private static float stabilizeUnit(float current, float target, float maxStep, float deadZone) {
        float boundedTarget = clampUnit(target);
        float delta = boundedTarget - current;
        if (Math.abs(delta) <= deadZone) return current;
        if (delta > maxStep) delta = maxStep;
        else if (delta < -maxStep) delta = -maxStep;
        return clampUnit(current + delta);
    }

    private static float smooth(float current, float target, float alpha) {
        return current + (target - current) * alpha;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampSigned(float value) {
        return Math.max(-1f, Math.min(1f, value));
    }

    private static float clampUnit(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static final class Accumulator {
        float sumX;
        float sumY;
        float weight;
        int count;

        void add(float x, float y, float w) {
            sumX += x * w;
            sumY += y * w;
            weight += w;
            count++;
        }

        float meanX() {
            return weight <= 0f ? 0f : sumX / weight;
        }

        float meanY() {
            return weight <= 0f ? 0f : sumY / weight;
        }
    }

    private static final class HandEstimate {
        static final HandEstimate EMPTY = new HandEstimate(false, false,
                0f, 0f, 0f, 0f, 0f, 0f, 0, 0);

        final boolean leftFound;
        final boolean rightFound;
        final float leftArmUp;
        final float rightArmUp;
        final float leftX;
        final float leftY;
        final float rightX;
        final float rightY;
        final int leftCount;
        final int rightCount;

        HandEstimate(boolean leftFound, boolean rightFound,
                     float leftArmUp, float rightArmUp,
                     float leftX, float leftY, float rightX, float rightY,
                     int leftCount, int rightCount) {
            this.leftFound = leftFound;
            this.rightFound = rightFound;
            this.leftArmUp = leftArmUp;
            this.rightArmUp = rightArmUp;
            this.leftX = leftX;
            this.leftY = leftY;
            this.rightX = rightX;
            this.rightY = rightY;
            this.leftCount = leftCount;
            this.rightCount = rightCount;
        }
    }
}
