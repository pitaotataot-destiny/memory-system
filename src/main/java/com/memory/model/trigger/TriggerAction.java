package com.memory.model.trigger;

import com.memory.model.enums.ActionKind;

/**
 * 触发器动作
 */
public class TriggerAction {
    private ActionKind action;
    private String target = "all";
    private String orderBy;
    private Integer limit;
    private String rule;
    private String condition;

    public TriggerAction() {}

    public ActionKind getAction() { return action; }
    public void setAction(ActionKind action) { this.action = action; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getOrderBy() { return orderBy; }
    public void setOrderBy(String orderBy) { this.orderBy = orderBy; }

    public Integer getLimit() { return limit; }
    public void setLimit(Integer limit) { this.limit = limit; }

    public String getRule() { return rule; }
    public void setRule(String rule) { this.rule = rule; }

    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }
}
