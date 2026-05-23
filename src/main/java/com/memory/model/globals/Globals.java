package com.memory.model.globals;

import com.memory.model.enums.MemoryTypeKind;

/**
 * 全局设置 Meta Model
 */
public class Globals {
    private MemoryTypeKind defaultType = MemoryTypeKind.FACT;
    private int maxMemorySize = 5000;
    private int defaultTtlDays = 30;
    private StorageConfig storage = new StorageConfig();

    public Globals() {}

    public MemoryTypeKind getDefaultType() { return defaultType; }
    public void setDefaultType(MemoryTypeKind defaultType) { this.defaultType = defaultType; }

    public int getMaxMemorySize() { return maxMemorySize; }
    public void setMaxMemorySize(int maxMemorySize) { this.maxMemorySize = maxMemorySize; }

    public int getDefaultTtlDays() { return defaultTtlDays; }
    public void setDefaultTtlDays(int defaultTtlDays) { this.defaultTtlDays = defaultTtlDays; }

    public StorageConfig getStorage() { return storage; }
    public void setStorage(StorageConfig storage) { this.storage = storage; }
}
