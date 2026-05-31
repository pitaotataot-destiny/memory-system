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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(TriggerMgr.class);

    private final MemoryRuntimeContext ctx;
    private final MemoryMgr memoryMgr;
    private final DecayMgr decayMgr;

    /**
     * @param ctx       运行时上下文
     * @param memoryMgr 记忆管理器（注入复用，避免每次 new）
     * @param decayMgr  衰减管理器
     */
    public TriggerMgr(MemoryRuntimeContext ctx, MemoryMgr memoryMgr, DecayMgr decayMgr) {
        this.ctx = ctx;
        this.memoryMgr = memoryMgr;
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
        LOG.info("Registered {} triggers from MetaModel", triggers.size());
    }

    /**
     * 热重载触发器：清除旧绑定，从当前 MetaModel 重新注册。
     * 由 MemoryClient.updateModel() / DSLWatcher.reloadModel() 调用。
     */
    public void reloadAllTriggers() {
        LOG.info("Reloading triggers for hot update...");
        ctx.getEventBus().clearSubscriptions();
        ctx.getScheduler().cancelAll();
        registerAllTriggers();
        LOG.info("Triggers reloaded successfully");
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
                LOG.error("[TriggerMgr] Event trigger error ({}): {}", trigger.getName(), e.getMessage());
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
                LOG.error("[TriggerMgr] Schedule trigger error ({}): {}", trigger.getName(), e.getMessage());
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
                LOG.error("[TriggerMgr] Condition trigger error ({}): {}", trigger.getName(), e.getMessage());
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
                // embedding 模型尚未接入，占位待实现
                break;
            case NORMALIZE_TAGS:
                // 标签规范化功能尚未实现
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
        LOG.debug("Decay action complete: {} decayed, {} purged", decayed.size(), summary.purged());
    }

    /**
     * Run purge action — deletes memories matching the action's condition.
     * 使用注入的 memoryMgr 实例，不重复创建。
     */
    private void runPurgeAction(TriggerAction action) {
        int count = memoryMgr.count();
        int maxSize = ctx.getMetaModel().getGlobals().getMaxMemorySize();

        if (count > maxSize) {
            // 溢出清理：触发衰减计算和生命周期检查
            LOG.info("Memory overflow detected: {} / {} (max), triggering purge", count, maxSize);
            runDecayAction();
        }
    }

    /**
     * Build the variable context for condition evaluation.
     * Includes memory_count, globals, etc.
     */
    private Map<String, Object> buildConditionVariables() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("memory_count", memoryMgr.count());

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
