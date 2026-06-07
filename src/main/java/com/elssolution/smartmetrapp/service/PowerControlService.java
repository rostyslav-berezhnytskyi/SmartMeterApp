package com.elssolution.smartmetrapp.service;

import com.elssolution.smartmetrapp.domain.SmSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PowerControlService {

    // ====== Config (from application.yml) ======
    @Value("${smartmetr.scale.pt:1.0}")        private double pt;
    @Value("${smartmetr.scale.ct:1.0}")        private double ct;
    @Value("${smartmetr.staleToZeroMs:300000}")private long   maxAgeMs;

    /** A phase is considered "alive" if its phase-to-neutral V >= this. */
    @Value("${smartmetr.phaseMinVolt:100.0}")  private double phaseMinVolt;

    /** Guard in I = P/(V*pf) to avoid huge currents when V is tiny. */
    @Value("${smartmetr.safeDivMinVolt:100.0}")private double safeDivMinVolt;

    /** Max change speed we allow the published setpoint to move (kW/s). */
    @Value("${smartmetr.publish.rateLimitKwPerSec:3.0}")
    private double publishRateLimitKwPerSec;

    /** Max change speed when COMPENSATION is neutral/off (kW/s). */
    @Value("${smartmetr.publish.rateLimitNeutralKwPerSec:1.0}")
    private double publishRateLimitNeutralKwPerSec;

    /**
     * Hard cap: never publish more than this many kW of apparent import to the inverter.
     * In this meter's sign convention, import is negative P, so the cap is pTotPub >= -(this * 1000).
     * Set just below the inverter's rated capacity — 29 kW for a 30 kW unit.
     */
    @Value("${smartmetr.publish.maxApparentImportKw:29.0}")
    private double maxApparentImportKw;

    /**
     * Near-zero bias (W). If |pTotPub| < this value, clamp to -nearZeroBiasW to prevent
     * the published setpoint from hovering exactly at 0 and causing inverter oscillation.
     */
    @Value("${smartmetr.publish.nearZeroBiasW:80.0}")
    private double nearZeroBiasW;

    // ====== Acrel register addresses ======
    private static final int REG_V1   =  97;
    private static final int REG_V2   =  98;
    private static final int REG_V3   =  99;
    private static final int REG_I1   = 100;
    private static final int REG_I2   = 101;
    private static final int REG_I3   = 102;
    private static final int REG_P1   = 356; // i32 MSW
    private static final int REG_P2   = 358; // i32 MSW
    private static final int REG_P3   = 360; // i32 MSW
    private static final int REG_PTOT = 362; // i32 MSW

    // ---- Median-3 history (raw samples) ----
    private volatile double p1Prev1 = Double.NaN, p1Prev2 = Double.NaN;
    private volatile double p2Prev1 = Double.NaN, p2Prev2 = Double.NaN;
    private volatile double p3Prev1 = Double.NaN, p3Prev2 = Double.NaN;
    private volatile double ptPrev1 = Double.NaN, ptPrev2 = Double.NaN;

    // ---- Publish-side slew limiter state ----
    private volatile long   lastPubMs   = 0L;
    private volatile double lastPubP1W  = Double.NaN;
    private volatile double lastPubP2W  = Double.NaN;
    private volatile double lastPubP3W  = Double.NaN;
    private volatile double lastPubTotW = Double.NaN;

    /**
     * Build the output frame for the inverter using Acrel native registers only.
     *
     * @param snapshot     last meter frame (Acrel raw registers)
     * @param compensateKw positive kW we want to add to site load (split across phases)
     * @return the register image to expose to the inverter (same layout as input, with edits)
     */
    public short[] prepareOutputWords(SmSnapshot snapshot, double compensateKw) {
        short[] base = (snapshot != null && snapshot.data != null) ? snapshot.data : new short[0];
        short[] out  = ensureCapacity(base, 364);

        final double PT = (pt > 0 && Double.isFinite(pt)) ? pt : 1.0;
        final double CT = (ct > 0 && Double.isFinite(ct)) ? ct : 1.0;

        // If meter is stale or offline — keep previous publish (or pass-through) and reset slew
        final long age = (snapshot == null || snapshot.updatedAtMs == 0)
                ? Long.MAX_VALUE : (System.currentTimeMillis() - snapshot.updatedAtMs);
        if (age > maxAgeMs || acrelOffline(out, PT)) {
            resetSlew();
            return out; // feeder will republish last good frame if configured
        }

        // Read raw (W)
        double rawP1W   = i32be(out, REG_P1  ) * PT * CT;
        double rawP2W   = i32be(out, REG_P2  ) * PT * CT;
        double rawP3W   = i32be(out, REG_P3  ) * PT * CT;
        double rawPTotW = i32be(out, REG_PTOT) * PT * CT;

        // Median-3 spike filter (we keep histories of RAW samples)
        double p1W   = median3WithNaN(rawP1W,   p1Prev1, p1Prev2);
        double p2W   = median3WithNaN(rawP2W,   p2Prev1, p2Prev2);
        double p3W   = median3WithNaN(rawP3W,   p3Prev1, p3Prev2);
        double pTotW = median3WithNaN(rawPTotW, ptPrev1, ptPrev2);

        // shift history
        p1Prev2 = p1Prev1; p1Prev1 = rawP1W;
        p2Prev2 = p2Prev1; p2Prev1 = rawP2W;
        p3Prev2 = p3Prev1; p3Prev1 = rawP3W;
        ptPrev2 = ptPrev1; ptPrev1 = rawPTotW;

        // Alive phases decision
        final double v1 = 0.1 * u16(out, REG_V1) * PT;
        final double v2 = 0.1 * u16(out, REG_V2) * PT;
        final double v3 = 0.1 * u16(out, REG_V3) * PT;
        boolean a1 = v1 >= phaseMinVolt, a2 = v2 >= phaseMinVolt, a3 = v3 >= phaseMinVolt;
        int alive = (a1?1:0) + (a2?1:0) + (a3?1:0);
        if (alive == 0) {
            resetSlew();
            return out;
        }

        // ----- NEUTRAL MODE (compensation <= 0): FOLLOW METER *WITH SLEW* -----
        if (!Double.isFinite(compensateKw) || compensateKw <= 0.0) {
            double targetP1 = p1W, targetP2 = p2W, targetP3 = p3W;
            // Slew using the neutral (conservative) rate
            long now = System.currentTimeMillis();
            double dtSec = (lastPubMs == 0L) ? 1.0 : Math.max(0.2, (now - lastPubMs) / 1000.0);
            double stepMaxW = Math.max(0.0, publishRateLimitNeutralKwPerSec) * 1000.0 * dtSec;

            double p1Pub = limitSlew(lastPubP1W, targetP1, stepMaxW);
            double p2Pub = limitSlew(lastPubP2W, targetP2, stepMaxW);
            double p3Pub = limitSlew(lastPubP3W, targetP3, stepMaxW);
            double pTotPub = p1Pub + p2Pub + p3Pub;

            // Near-zero bias: avoid hovering exactly at 0 W which can cause inverter oscillation
            if (nearZeroBiasW > 0 && Math.abs(pTotPub) < nearZeroBiasW) pTotPub = -nearZeroBiasW;

            // Safety cap: never show more than maxApparentImportKw to the inverter
            double capW = maxApparentImportKw * 1000.0;
            if (capW > 0 && pTotPub < -capW) {
                double scale = capW / (-pTotPub);
                p1Pub *= scale; p2Pub *= scale; p3Pub *= scale;
                pTotPub = p1Pub + p2Pub + p3Pub;
            }

            if (a1) writeI32be(out, REG_P1,   toRawPower(p1Pub,  PT, CT));
            if (a2) writeI32be(out, REG_P2,   toRawPower(p2Pub,  PT, CT));
            if (a3) writeI32be(out, REG_P3,   toRawPower(p3Pub,  PT, CT));
            writeI32be(out, REG_PTOT, toRawPower(pTotPub, PT, CT));

            lastPubMs   = now;
            lastPubP1W  = p1Pub;
            lastPubP2W  = p2Pub;
            lastPubP3W  = p3Pub;
            lastPubTotW = pTotPub;

            return out;
        }

        // ----- COMPENSATION MODE -----
        final double biasW  = compensateKw * 1000.0;
        double perAlive = -biasW / alive;   // equal share of the bias per alive phase

        double dP1 = a1 ? (p1W + perAlive) : p1W;
        double dP2 = a2 ? (p2W + perAlive) : p2W;
        double dP3 = a3 ? (p3W + perAlive) : p3W;

        long now = System.currentTimeMillis();
        double dtSec = (lastPubMs == 0L) ? 1.0 : Math.max(0.2, (now - lastPubMs) / 1000.0);
        double stepMaxW = Math.max(0.0, publishRateLimitKwPerSec) * 1000.0 * dtSec;

        double p1Pub = limitSlew(lastPubP1W, dP1, stepMaxW);
        double p2Pub = limitSlew(lastPubP2W, dP2, stepMaxW);
        double p3Pub = limitSlew(lastPubP3W, dP3, stepMaxW);
        double pTotPub = p1Pub + p2Pub + p3Pub;

        // Near-zero bias: avoid hovering exactly at 0 W which can cause inverter oscillation
        if (nearZeroBiasW > 0 && Math.abs(pTotPub) < nearZeroBiasW) pTotPub = -nearZeroBiasW;

        // Safety cap: never show more than maxApparentImportKw to the inverter
        double capW = maxApparentImportKw * 1000.0;
        if (capW > 0 && pTotPub < -capW) {
            double scale = capW / (-pTotPub);
            p1Pub *= scale; p2Pub *= scale; p3Pub *= scale;
            pTotPub = p1Pub + p2Pub + p3Pub;
        }

        if (a1) writeI32be(out, REG_P1,   toRawPower(p1Pub,  PT, CT));
        if (a2) writeI32be(out, REG_P2,   toRawPower(p2Pub,  PT, CT));
        if (a3) writeI32be(out, REG_P3,   toRawPower(p3Pub,  PT, CT));
        writeI32be(out, REG_PTOT, toRawPower(pTotPub, PT, CT));

        lastPubMs   = now;
        lastPubP1W  = p1Pub;
        lastPubP2W  = p2Pub;
        lastPubP3W  = p3Pub;
        lastPubTotW = pTotPub;

        return out;
    }


    // ====== helpers ======

    private void resetSlew() {
        lastPubMs = 0L;
        lastPubP1W = lastPubP2W = lastPubP3W = lastPubTotW = Double.NaN;
    }

    private static double median3WithNaN(double a, double b, double c) {
        // handle NaNs: use the finite subset's median/mean
        boolean fa = Double.isFinite(a), fb = Double.isFinite(b), fc = Double.isFinite(c);
        int cnt = (fa?1:0) + (fb?1:0) + (fc?1:0);
        if (cnt >= 3) return med3(a,b,c);
        if (cnt == 2) {
            double x = fa ? a : (fb ? b : c);
            double y = (fa && fb) ? b : (fa && fc) ? c : (fb && fc) ? c : x; // pick the other finite
            return 0.5*(x+y);
        }
        return fa ? a : fb ? b : c; // best-effort
    }

    private static double med3(double a,double b,double c){
        if (a>b){double t=a;a=b;b=t;} if (b>c){double t=b;b=c;c=t;} if (a>b){double t=a;a=b;b=t;}
        return b;
    }

    private static short[] ensureCapacity(short[] src, int minLen) {
        if (src == null) return new short[minLen];
        if (src.length >= minLen) return src.clone();
        short[] dst = new short[minLen];
        System.arraycopy(src, 0, dst, 0, src.length);
        return dst;
    }

    private static int u16(short[] a, int i) {
        if (a == null || i < 0 || i >= a.length) return 0;
        return a[i] & 0xFFFF;
    }

    private static int i32be(short[] a, int msw) {
        if (a == null || msw < 0 || msw + 1 >= a.length) return 0;
        int hi = u16(a, msw);
        int lo = u16(a, msw + 1);
        return (hi << 16) | lo;
    }

    private static void writeI32be(short[] a, int msw, int value) {
        if (a == null || msw < 0 || msw + 1 >= a.length) return;
        a[msw]     = (short)((value >>> 16) & 0xFFFF);
        a[msw + 1] = (short)( value         & 0xFFFF);
    }

    private int toRawPower(double watts, double PT, double CT) {
        final double den = Math.max(1e-6, PT * CT);
        final double raw = watts / den;
        if (raw > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (raw < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) Math.round(raw);
    }

    private boolean acrelOffline(short[] w, double PT) {
        final double v1 = 0.1 * u16(w, REG_V1) * PT;
        final double v2 = 0.1 * u16(w, REG_V2) * PT;
        final double v3 = 0.1 * u16(w, REG_V3) * PT;
        return (v1 < 1.0 && v2 < 1.0 && v3 < 1.0);
    }

    private double limitSlew(double prev, double target, double stepMaxW) {
        if (!Double.isFinite(prev)) return target; // first publish after reset
        double lo = prev - stepMaxW, hi = prev + stepMaxW;
        return Math.max(lo, Math.min(hi, target));
    }
}
