package com.memory.model.decay;

/**
 * 单个类型的衰减配置
 */
public class DecayConfig {
    private double dailyDecay = 0.92;
    private double accessGain = 0.05;
    private double minImportance = 0.1;

    public double getDailyDecay() { return dailyDecay; }
    public void setDailyDecay(double dailyDecay) { this.dailyDecay = dailyDecay; }

    public double getAccessGain() { return accessGain; }
    public void setAccessGain(double accessGain) { this.accessGain = accessGain; }

    public double getMinImportance() { return minImportance; }
    public void setMinImportance(double minImportance) { this.minImportance = minImportance; }
}
