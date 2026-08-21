package com.axon.input;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.security.MessageDigest;

/**
 * Lightweight APK signing-certificate verification.
 * Runs once per process from AxonApplication and never participates in input/render hot paths.
 */
final class SignatureVerifier {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SignatureVerifier() {}

    @SuppressWarnings("deprecation")
    static boolean isValid(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(),
                    PackageManager.GET_SIGNATURES);
            Signature[] signatures = info.signatures;
            if (signatures == null || signatures.length == 0) return false;

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Signature signature : signatures) {
                String actual = toHex(digest.digest(signature.toByteArray()));
                if (actual.equals(BuildSignature.EXPECTED_SHA256)) return true;
                digest.reset();
            }
        } catch (Exception ignored) {
            // Fail closed: an unreadable or unexpected signing state is treated as invalid.
        }
        return false;
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            out[i * 2] = HEX[value >>> 4];
            out[i * 2 + 1] = HEX[value & 0x0f];
        }
        return new String(out);
    }
}
