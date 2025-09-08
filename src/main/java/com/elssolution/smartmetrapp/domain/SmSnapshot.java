package com.elssolution.smartmetrapp.domain;

/** Immutable meter snapshot: raw words + read timestamp. */
public final class SmSnapshot {
    public final short[] data;        // raw Modbus words (unchanged layout)
    public final long updatedAtMs;    // System.currentTimeMillis() at successful read
    public SmSnapshot(short[] data, long updatedAtMs) {
        this.data = data;
        this.updatedAtMs = updatedAtMs;
    }
}
