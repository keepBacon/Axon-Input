package com.axon.input;

/** 键盘和鼠标状态的 JNI 入口。位运算由 C++ 处理。 */
public final class NativeKeyEngine {
    public static final int W = 1 << 0;
    public static final int A = 1 << 1;
    public static final int S = 1 << 2;
    public static final int D = 1 << 3;
    public static final int SPACE = 1 << 4;

    public static final int MOUSE_LEFT = 0;
    public static final int MOUSE_RIGHT = 1;

    static {
        System.loadLibrary("keyengine");
    }

    private NativeKeyEngine() {}

    public static native int nativeUpdateKey(int keyCode, boolean pressed);
    public static native int nativeReset();
    public static native boolean nativeIsTrackedKey(int keyCode);

    /** 更新单个鼠标按键。button=0 左键，button=1 右键。eventTimeMs 为单调毫秒时间。 */
    public static native long nativeUpdateMouseButton(int button, boolean pressed, long eventTimeMs);

    /** 返回鼠标状态：0-1 位为按下状态，8-15 位为左键 DPS，16-23 位为右键 DPS。 */
    public static native long nativeGetMouseStats(long nowMs);

    public static native long nativeResetMouse(long nowMs);
}
