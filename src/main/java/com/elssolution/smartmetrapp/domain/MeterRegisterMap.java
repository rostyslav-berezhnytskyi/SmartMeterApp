package com.elssolution.smartmetrapp.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Word offsets for each register. Use -1 for “not mapped”. */
@Component
public class MeterRegisterMap {

    // SDM630 defaults (adjust when you move to Acrel)
    @Value("${meterMap.vL1:0}")     private int vL1;
    @Value("${meterMap.iL1:6}")     private int iL1;
    @Value("${meterMap.pTotal:52}") private int pTotal;

    // 3-phase placeholders (adjust as you confirm)
    @Value("${meterMap.vL2:2}")     private int vL2;
    @Value("${meterMap.vL3:4}")     private int vL3;
    @Value("${meterMap.iL2:8}")     private int iL2;
    @Value("${meterMap.iL3:10}")    private int iL3;
    @Value("${meterMap.pL1:12}")    private int pL1;
    @Value("${meterMap.pL2:14}")    private int pL2;
    @Value("${meterMap.pL3:16}")    private int pL3;

    @Value("${smartmetr.fHz:-1}") private int fHz;  // (optional)

    // Accessors with the short, readable names PowerController expects
    public int vL1()    { return vL1; }
    public int vL2()    { return vL2; }
    public int vL3()    { return vL3; }
    public int iL1()    { return iL1; }
    public int iL2()    { return iL2; }
    public int iL3()    { return iL3; }
    public int pTotal() { return pTotal; }
    public int pL1()    { return pL1; }
    public int pL2()    { return pL2; }
    public int pL3()    { return pL3; }
    public int fHz() { return fHz; }
}
