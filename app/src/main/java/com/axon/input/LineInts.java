package com.axon.input;

/** 解析 native 文本协议中的整数，避免高频 String.split 分配。 */
final class LineInts {
    private LineInts() {}

    static boolean parse(String text, int offset, int[] output) {
        if (text == null || output == null || offset < 0 || offset > text.length()) return false;
        int length = text.length();
        int index = offset;
        for (int slot = 0; slot < output.length; slot++) {
            while (index < length && text.charAt(index) == ' ') index++;
            if (index >= length) return false;

            boolean negative = text.charAt(index) == '-';
            if (negative) index++;
            if (index >= length || text.charAt(index) < '0' || text.charAt(index) > '9') return false;

            int value = 0;
            while (index < length) {
                char ch = text.charAt(index);
                if (ch < '0' || ch > '9') break;
                int digit = ch - '0';
                if (value > (Integer.MAX_VALUE - digit) / 10) return false;
                value = value * 10 + digit;
                index++;
            }
            output[slot] = negative ? -value : value;
        }
        while (index < length && text.charAt(index) == ' ') index++;
        return index == length;
    }
}
