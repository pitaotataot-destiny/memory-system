package com.memory.model.decay;

import java.util.HashMap;
import java.util.Map;

/**
 * 衰减策略 Meta Model
 */
public class DecayPolicy {
    private DecayConfig defaultConfig;
    private Map<String, DecayConfig> typeOverrides = new HashMap<>();
    private LifecycleConfig lifecycle = new LifecycleConfig();

    public DecayConfig getDefaultConfig() { return defaultConfig; }
    public void setDefaultConfig(DecayConfig defaultConfig) { this.defaultConfig = defaultConfig; }

    public Map<String, DecayConfig> getTypeOverrides() { return typeOverrides; }
    public void setTypeOverrides(Map<String, DecayConfig> typeOverrides) { this.typeOverrides = typeOverrides; }

    public LifecycleConfig getLifecycle() { return lifecycle; }
    public void setLifecycle(LifecycleConfig lifecycle) { this.lifecycle = lifecycle; }

    /**
     * 获取指定类型的衰减配置（含 fallback 到 default）
     */
    public DecayConfig getConfigForType(String typeKind) {
        DecayConfig override = typeOverrides.get(typeKind);
        if (override == null) {
            return defaultConfig;
        }
        DecayConfig merged = new DecayConfig();
        merged.setDailyDecay(override.getDailyDecay());
        merged.setAccessGain(override.getAccessGain());
        merged.setMinImportance(override.getMinImportance());
        return merged;
    }
}
