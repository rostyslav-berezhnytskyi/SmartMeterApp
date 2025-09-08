package com.elssolution.smartmetrapp.domain;

public final class Maths {
    private static final double EPS = 1e-9;
    public static double safeDiv(double num, double den) {
        return Math.abs(den) < EPS ? 0.0 : num / den;
    }
    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
