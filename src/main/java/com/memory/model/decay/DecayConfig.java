package com.memory.model.decay;

/**
 * 单个类型的衰减配置。
 * 所有字段使用 Double（包装类型），null 表示"未设置，从 default 继承"。
 */
public class DecayConfig {
    private Double dailyDecay;
    private Double accessGain;
    private Double minImportance;

    public Double getDailyDecay() { return dailyDecay; }
    public void setDailyDecay(Double dailyDecay) { this.dailyDecay = dailyDecay; }

    public Double getAccessGain() { return accessGain; }
    public void setAccessGain(Double accessGain) { this.accessGain = accessGain; }

    public Double getMinImportance() { return minImportance; }
    public void setMinImportance(Double minImportance) { this.minImportance = minImportance; }

    // ── 便捷方法：获取有效值（含 fallback） ────────────────

    /** 获取 dailyDecay 有效值，未设置返回 defaultVal */
    public double getDailyDecay(double defaultVal) {
        return dailyDecay != null ? dailyDecay : defaultVal;
    }

    /** 获取 accessGain 有效值，未设置返回 defaultVal */
    public double getAccessGain(double defaultVal) {
        return accessGain != null ? accessGain : defaultVal;
    }

    /** 获取 minImportance 有效值，未设置返回 defaultVal */
    public double getMinImportance(double defaultVal) {
        return minImportance != null ? minImportance : defaultVal;
    }
}
