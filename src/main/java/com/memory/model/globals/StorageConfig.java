package com.memory.model.globals;

import com.memory.model.enums.StorageEngine;

/**
 * 存储配置
 */
public class StorageConfig {
    private StorageEngine engine = StorageEngine.JSON;
    private String path = "./data";
    private String encoding = "utf-8";

    public StorageConfig() {}

    public StorageEngine getEngine() { return engine; }
    public void setEngine(StorageEngine engine) { this.engine = engine; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getEncoding() { return encoding; }
    public void setEncoding(String encoding) { this.encoding = encoding; }
}
