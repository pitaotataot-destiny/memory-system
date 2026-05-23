package com.memory.model.trigger;

/**
 * 触发器 Meta Model
 */
public class Trigger {
    private String name;
    private TriggerCondition when;
    private TriggerAction then;

    public Trigger() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public TriggerCondition getWhen() { return when; }
    public void setWhen(TriggerCondition when) { this.when = when; }

    public TriggerAction getThen() { return then; }
    public void setThen(TriggerAction then) { this.then = then; }
}
