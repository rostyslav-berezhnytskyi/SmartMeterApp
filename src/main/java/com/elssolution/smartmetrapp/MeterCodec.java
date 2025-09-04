package com.elssolution.smartmetrapp;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encodes/decodes IEEE-754 floats to/from Modbus 16-bit register arrays (two words per float).
 * Word order:
 *   BE (Big Endian) = high word first (w[off] = HI, w[off+1] = LO)
 *   LE (Little Endian) = low word first  (w[off] = LO, w[off+1] = HI)
 */
@Component
public class MeterCodec {

    public enum WordOrder { BE, LE }

    @Value("${smartmetr.floatOrder:BE}")
    private WordOrder wordOrder;

    // cached boolean for fast hot-path checks
    private boolean littleEndian;

    @PostConstruct
    void init() {
        if (wordOrder == null) {
            throw new IllegalArgumentException("smartmetr.floatOrder must be BE or LE");
        }
        this.littleEndian = (wordOrder == WordOrder.LE);
    }

    /** Read a float from two consecutive 16-bit words at the given word offset. */
    public float readFloat(short[] words, int wordOffset) {
        requireTwoWords(words, wordOffset);
        int w0 = words[wordOffset]     & 0xFFFF;
        int w1 = words[wordOffset + 1] & 0xFFFF;
        int bits = littleEndian ? ((w1 << 16) | w0) : ((w0 << 16) | w1);
        return Float.intBitsToFloat(bits);
    }

    /**
     * Safe read: if out of bounds or array null, returns fallback.
     * Handy when parsing partially filled frames.
     */
    public float readFloatOrDefault(short[] words, int wordOffset, float fallback) {
        if (words == null || wordOffset < 0 || wordOffset + 1 >= words.length) return fallback;
        return readFloat(words, wordOffset);
    }

    /** Write a float into two consecutive 16-bit words at the given word offset. */
    public void writeFloat(short[] words, int wordOffset, float value) {
        requireTwoWords(words, wordOffset);
        int bits = Float.floatToIntBits(value);
        short hi = (short) ((bits >>> 16) & 0xFFFF);
        short lo = (short) (bits & 0xFFFF);
        if (littleEndian) {
            words[wordOffset]     = lo;
            words[wordOffset + 1] = hi; // )
        } else {
            words[wordOffset]     = hi;
            words[wordOffset + 1] = lo;
        }
    }

    // ---- internal guard ----
    private static void requireTwoWords(short[] words, int wordOffset) {
        if (words == null) {
            throw new IllegalArgumentException("words array is null");
        }
        if (wordOffset < 0 || wordOffset + 1 >= words.length) {
            throw new IllegalArgumentException(
                    "need two words starting at index " + wordOffset + " (len=" + words.length + ")"
            );
        }
    }
}
