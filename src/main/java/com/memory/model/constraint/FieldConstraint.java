package com.memory.model.constraint;

/**
 * 字段约束定义
 */
public class FieldConstraint {
    private String type = "string";
    private boolean required = false;
    private Double min;
    private Double max;
    private Object defaultValue;
    private String format;
    private java.util.List<String> enumValues;

    public FieldConstraint() {}

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }

    public Double getMin() { return min; }
    public void setMin(Double min) { this.min = min; }

    public Double getMax() { return max; }
    public void setMax(Double max) { this.max = max; }

    public Object getDefaultValue() { return defaultValue; }
    public void setDefaultValue(Object defaultValue) { this.defaultValue = defaultValue; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public java.util.List<String> getEnumValues() { return enumValues; }
    public void setEnumValues(java.util.List<String> enumValues) { this.enumValues = enumValues; }
}
