package com.axon.input;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 入口密码只保存摘要，避免 APK 中直接暴露明文。它仍不是服务端认证边界。 */
final class EntryAccessPolicy {
    private static final byte[] EXPECTED_SHA256 = hex(
            "bfcddcb062a30e8480f5547aab7cc1303ef1aff914af72bd80f6b1f127e2a2a1");

    private EntryAccessPolicy() {}

    static boolean matches(String value) {
        if (value == null) return false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] actual = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(EXPECTED_SHA256, actual);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static byte[] hex(String value) {
        int count = value == null ? 0 : value.length();
        if ((count & 1) != 0) throw new IllegalArgumentException("hex length");
        byte[] out = new byte[count / 2];
        for (int i = 0; i < count; i += 2) {
            int hi = Character.digit(value.charAt(i), 16);
            int lo = Character.digit(value.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("hex digit");
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
