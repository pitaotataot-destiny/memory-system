package com.memory.model.trigger;

import com.memory.model.enums.TriggerEvent;

/**
 * 触发器条件
 */
public class TriggerCondition {
    private String schedule;       // cron 表达式
    private String condition;      // 布尔表达式
    private TriggerEvent event;
    private String field;

    public TriggerCondition() {}

    public String getSchedule() { return schedule; }
    public void setSchedule(String schedule) { this.schedule = schedule; }

    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }

    public TriggerEvent getEvent() { return event; }
    public void setEvent(TriggerEvent event) { this.event = event; }

    public String getField() { return field; }
    public void setField(String field) { this.field = field; }
}
