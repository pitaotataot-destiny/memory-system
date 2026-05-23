package com.memory.engine.manager;

import com.memory.model.MetaModel;
import com.memory.model.enums.ActionKind;
import com.memory.model.enums.TriggerEvent;
import com.memory.model.trigger.Trigger;
import com.memory.model.trigger.TriggerAction;
import com.memory.model.trigger.TriggerCondition;
import com.memory.runtime.MemoryRuntimeContext;
import com.memory.spi.EventBus;
import com.memory.spi.ExpressionEngine;
import com.memory.spi.Scheduler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Trigger manager — manages trigger registration, event matching, and action execution.
 *
 * Three trigger types:
 * 1. Event-driven: matches MEMORY_CREATED, MEMORY_UPDATED events via EventBus
 * 2. Schedule-driven: runs on cron schedule via Scheduler
 * 3. Condition-driven: evaluates condition expressions via ExpressionEngine
 */
public class TriggerMgr {

    private final MemoryRuntimeContext ctx;
    private final DecayMgr decayMgr;

    public TriggerMgr(MemoryRuntimeContext ctx, DecayMgr decayMgr) {
        this.ctx = ctx;
        this.decayMgr = decayMgr;
    }

    /**
     * Register all triggers defined in the current MetaModel.
     * Should be called during startup after Registry assembly.
     */
    public void registerAllTriggers() {
        MetaModel model = ctx.getMetaModel();
        List<Trigger> triggers = model.getTriggers();
        if (triggers == null) return;

        for (Trigger trigger : triggers) {
            registerTrigger(trigger);
        }
    }

    /**
     * Register a single trigger.
     * Routes to the appropriate registration method based on trigger type.
     */
    private void registerTrigger(Trigger trigger) {
        TriggerCondition when = trigger.getWhen();
        if (when == null) return;

        // Event-driven trigger
        if (when.getEvent() != null) {
            registerEventTrigger(trigger, when.getEvent());
        }

        // Schedule-driven trigger
        if (when.getSchedule() != null) {
            registerScheduleTrigger(trigger, when.getSchedule());
        }

        // Condition-driven trigger (evaluated on schedule)
        if (when.getCondition() != null) {
            registerConditionTrigger(trigger, when.getCondition());
        }
    }

    /**
     * Register an event-driven trigger.
     * Subscribes to the EventBus and executes actions when matching events arrive.
     */
    private void registerEventTrigger(Trigger trigger, TriggerEvent event) {
        EventBus eventBus = ctx.getEventBus();
        String eventType = event.getValue();

        eventBus.subscribe(eventType, (evt) -> {
            try {
                executeAction(trigger.getThen(), Map.of(
                    "memory_id", evt.memoryId(),
                    "event_type", evt.type(),
                    "event_payload", evt.payload()
                ));
            } catch (Exception e) {
                System.err.println("[TriggerMgr] Event trigger error (" + trigger.getName() + "): " + e.getMessage());
            }
        });
    }

    /**
     * Register a schedule-driven trigger.
     * Schedules a cron task that executes the trigger's action.
     */
    private void registerScheduleTrigger(Trigger trigger, String cronExpression) {
        Scheduler scheduler = ctx.getScheduler();

        scheduler.schedule(cronExpression, () -> {
            try {
                executeAction(trigger.getThen(), Map.of(
                    "trigger_name", trigger.getName(),
                    "timestamp", System.currentTimeMillis()
                ));
            } catch (Exception e) {
                System.err.println("[TriggerMgr] Schedule trigger error (" + trigger.getName() + "): " + e.getMessage());
            }
        });
    }

    /**
     * Register a condition-driven trigger.
     * Schedules periodic evaluation of the condition expression.
     * When condition becomes true, executes the action.
     */
    private void registerConditionTrigger(Trigger trigger, String conditionExpression) {
        Scheduler scheduler = ctx.getScheduler();
        ExpressionEngine expressionEngine = ctx.getExpressionEngine();

        // Evaluate every minute
        scheduler.schedule("*/1 * * * *", () -> {
            try {
                Map<String, Object> variables = buildConditionVariables();
                boolean result = expressionEngine.evaluate(conditionExpression, variables);
                if (result) {
                    executeAction(trigger.getThen(), variables);
                }
            } catch (Exception e) {
                System.err.println("[TriggerMgr] Condition trigger error (" + trigger.getName() + "): " + e.getMessage());
            }
        });
    }

    /**
     * Execute a trigger action based on its type.
     */
    private void executeAction(TriggerAction action, Map<String, Object> variables) {
        if (action == null || action.getAction() == null) return;

        ActionKind kind = action.getAction();
        switch (kind) {
            case RUN_DECAY:
                runDecayAction();
                break;
            case PURGE:
                runPurgeAction(action);
                break;
            case GENERATE_EMBEDDING:
                // TODO: implement when embedding provider has real model
                break;
            case NORMALIZE_TAGS:
                // TODO: implement tag normalization
                break;
        }
    }

    /**
     * Run decay action — triggers decay calculation and lifecycle check.
     */
    private void runDecayAction() {
        List<String> decayed = decayMgr.runDecay();
        DecayMgr.LifecycleSummary summary = decayMgr.runLifecycleCheck();
        ctx.incrementWrites();
    }

    /**
     * Run purge action — deletes memories matching the action's condition.
     */
    private void runPurgeAction(TriggerAction action) {
        MemoryMgr memoryMgr = new MemoryMgr(ctx);
        int count = memoryMgr.count();

        // Check if overflow (exceeds max_memory_size)
        int maxSize = ctx.getMetaModel().getGlobals().getMaxMemorySize();
        if (action.getCondition() != null && count > maxSize) {
            // TODO: implement overflow purge — sort by importance ASC, delete lowest
        }
    }

    /**
     * Build the variable context for condition evaluation.
     * Includes memory_count, globals, etc.
     */
    private Map<String, Object> buildConditionVariables() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("memory_count", new MemoryMgr(ctx).count());

        // Add globals
        var globals = ctx.getMetaModel().getGlobals();
        if (globals != null) {
            Map<String, Object> globalVars = new HashMap<>();
            globalVars.put("max_memory_size", globals.getMaxMemorySize());
            globalVars.put("default_ttl_days", globals.getDefaultTtlDays());
            globalVars.put("default_type", globals.getDefaultType().getValue());
            vars.put("globals", globalVars);
        }

        // Add metrics
        vars.put("total_queries", ctx.getTotalQueries());
        vars.put("total_writes", ctx.getTotalWrites());
        vars.put("total_errors", ctx.getTotalErrors());

        return vars;
    }
}
