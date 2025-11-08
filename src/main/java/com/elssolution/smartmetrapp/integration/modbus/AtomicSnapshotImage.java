package com.elssolution.smartmetrapp.integration.modbus;

import com.serotonin.modbus4j.BasicProcessImage;

/**
 * Read-only process image backed by a single volatile short[] snapshot.
 * Reads are coherent; publishing a new frame is an atomic pointer swap.
 */
public final class AtomicSnapshotImage extends BasicProcessImage {
    private volatile short[] published;

    /** @param slaveId Modbus unit id; @param initialLen initial zeroed frame length (e.g. 400). */
    public AtomicSnapshotImage(int slaveId, int initialLen) {
        super(slaveId);
        this.published = new short[Math.max(0, initialLen)];
    }

    /** Atomically publish a whole frame. Caller should provide the complete image. */
    public void publish(short[] frame) {
        this.published = (frame == null) ? new short[0] : frame.clone(); // clone so caller can reuse its buffer
    }

    /** Optional: read current published reference (debug/metrics only, do NOT modify). */
    public short[] current() { return published; }

    // ---- Serve Function 03 (Holding) & 04 (Input) from the same snapshot ----
    @Override public short getHoldingRegister(int index) {
        short[] p = published;
        return (index >= 0 && index < p.length) ? p[index] : 0;
    }
    @Override public short getInputRegister(int index) {
        short[] p = published;
        return (index >= 0 && index < p.length) ? p[index] : 0;
    }

    // Make it effectively read-only for the slave
    @Override public void setHoldingRegister(int index, short value) { /* ignore */ }
    @Override public void setInputRegister(int index, short value)   { /* ignore */ }
}

