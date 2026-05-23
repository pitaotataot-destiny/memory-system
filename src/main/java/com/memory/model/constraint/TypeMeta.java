package com.memory.model.constraint;

import java.util.Collections;

/**
 * 类型元数据约束
 */
public class TypeMeta {
    private java.util.List<String> immutableFields = Collections.emptyList();
    private java.util.List<String> uniqueBy = Collections.emptyList();
    private double importanceFloor = 0.1;
    private boolean ephemeral = false;

    public java.util.List<String> getImmutableFields() { return immutableFields; }
    public void setImmutableFields(java.util.List<String> immutableFields) { this.immutableFields = immutableFields; }

    public java.util.List<String> getUniqueBy() { return uniqueBy; }
    public void setUniqueBy(java.util.List<String> uniqueBy) { this.uniqueBy = uniqueBy; }

    public double getImportanceFloor() { return importanceFloor; }
    public void setImportanceFloor(double importanceFloor) { this.importanceFloor = importanceFloor; }

    public boolean isEphemeral() { return ephemeral; }
    public void setEphemeral(boolean ephemeral) { this.ephemeral = ephemeral; }
}
