package org.wetype.quick.integration;

/** Pure policy so privacy behavior can be checked without Android. */
public final class InputPolicy {
    private InputPolicy() { }
    public static boolean allowQuick(int inputType) {
        if ((inputType & 15) != 1) return false;
        int variation = inputType & 4080;
        return variation != 128 && variation != 144 && variation != 224;
    }
    public static boolean privateMode(int inputType, int imeOptions) {
        int variation = inputType & 4080;
        return !allowQuick(inputType) || (imeOptions & 0x1000000) != 0
                || variation == 16 || variation == 32 || variation == 208;
    }
}
