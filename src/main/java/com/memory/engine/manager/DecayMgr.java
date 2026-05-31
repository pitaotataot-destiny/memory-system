package com.memory.engine.manager;

import com.memory.model.MetaModel;
import com.memory.model.decay.DecayConfig;
import com.memory.model.decay.DecayPolicy;
import com.memory.model.decay.LifecycleConfig;
import com.memory.model.type.MemoryType;
import com.memory.runtime.MemoryRuntimeContext;
import com.memory.spi.MemoryStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decay manager — handles importance decay calculation and lifecycle management.
 *
 * Decay formula: new_importance = old_importance * (daily_decay ^ delta_days) + access_gain
 * Lifecycle: stale → archive → purge based on thresholds.
 */
public class DecayMgr {

    private static final Logger LOG = LoggerFactory.getLogger(DecayMgr.class);

    // 衰减公式默认参数（作为 getter fallback 值的兜底常量）
    private static final double FALLBACK_DAILY_DECAY = 0.92;
    private static final double FALLBACK_ACCESS_GAIN = 0.05;
    private static final double FALLBACK_MIN_IMPORTANCE = 0.1;

    private final MemoryRuntimeContext ctx;

    public DecayMgr(MemoryRuntimeContext ctx) {
        this.ctx = ctx;
    }

    /**
     * 获取配置的存储引擎名称（从 DSL globals.storage.engine 读取，不硬编码）。
     */
    private String getStoreName() {
        return ctx.getMetaModel().getGlobals().getStorage().getEngine().getValue();
    }

    /**
     * Run decay calculation on all memories.
     * Iterates all stored memories, calculates days since last access,
     * applies decay formula, and enforces min_importance floor.
     *
     * @return list of memory IDs that were decayed
     */
    public List<String> runDecay() {
        MetaModel model = ctx.getMetaModel();
        MemoryStore store = ctx.getStore(getStoreName());
        DecayPolicy decayPolicy = model.getDecay();
        if (decayPolicy == null) {
            return List.of();
        }

        Set<String> allIds = store.listAll();
        List<String> decayed = new ArrayList<>();

        for (String id : allIds) {
            String data = store.load(id);
            if (data == null) continue;

            DecayResult result = applyDecay(id, data, decayPolicy);
            if (result != null && result.changed) {
                store.save(id, result.newData);
                decayed.add(id);
            }
        }

        LOG.debug("Decay calculation complete: {} memories processed, {} decayed",
            allIds.size(), decayed.size());
        return decayed;
    }

    /**
     * Check lifecycle status for a specific memory (纯查询，无副作用)。
     * Determines if memory should be marked stale, archived, or purged.
     *
     * @param memoryId memory ID
     * @return lifecycle status
     */
    public LifecycleStatus checkLifecycle(String memoryId) {
        MetaModel model = ctx.getMetaModel();
        MemoryStore store = ctx.getStore(getStoreName());
        LifecycleConfig lifecycle = model.getDecay().getLifecycle();

        String data = store.load(memoryId);
        if (data == null) {
            return new LifecycleStatus("not_found");
        }

        long lastAccessed = extractLastAccessed(memoryId, data);
        double importance = extractImportance(data);
        long now = Instant.now().getEpochSecond();
        long daysSinceAccess = (now - lastAccessed) / 86400;

        // Purge 条件检查（仅判断，不执行删除 — 删除由 runLifecycleCheck 负责）
        if (importance < lifecycle.getPurgeWhenImportanceBelow()) {
            return new LifecycleStatus("purged",
                "importance=" + importance + " < " + lifecycle.getPurgeWhenImportanceBelow());
        }
        if (daysSinceAccess > lifecycle.getPurgeWhenStaleDays()) {
            return new LifecycleStatus("purged", "stale for " + daysSinceAccess + " days");
        }

        // Archive 检查
        if (daysSinceAccess > lifecycle.getArchiveAfterDays()) {
            return new LifecycleStatus("archive", "stale for " + daysSinceAccess + " days");
        }

        // Stale 检查
        if (daysSinceAccess > lifecycle.getStaleAfterDays()) {
            return new LifecycleStatus("stale", "inactive for " + daysSinceAccess + " days");
        }

        return new LifecycleStatus("active");
    }

    /**
     * Run lifecycle check on all memories, performing purge deletions.
     * 遍历所有记忆，检查生命周期状态，对符合 purge 条件的执行物理删除。
     *
     * @return summary of actions taken
     */
    public LifecycleSummary runLifecycleCheck() {
        MemoryStore store = ctx.getStore(getStoreName());
        Set<String> allIds = store.listAll();

        int stale = 0, archived = 0, purged = 0;
        for (String id : allIds) {
            LifecycleStatus status = checkLifecycle(id);
            switch (status.status()) {
                case "stale" -> stale++;
                case "archive" -> archived++;
                case "purged" -> {
                    // 副作用统一在此执行，不在 checkLifecycle 中做
                    store.delete(id);
                    ctx.evictHot(id);
                    purged++;
                }
            }
        }
        LOG.debug("Lifecycle check complete: total={}, stale={}, archived={}, purged={}",
            allIds.size(), stale, archived, purged);
        return new LifecycleSummary(allIds.size(), stale, archived, purged);
    }

    /**
     * Apply decay formula to a single memory entry.
     * Formula: new_importance = old_importance * (daily_decay ^ delta_days) + access_gain
     * Uses type-specific decay config (从 DSL type_overrides 获取，含 fallback 到 default)。
     * Floors at max(min_importance, type.importance_floor)。
     */
    private DecayResult applyDecay(String id, String data, DecayPolicy decayPolicy) {
        long lastAccessed = extractLastAccessed(id, data);
        double currentImportance = extractImportance(data);

        long now = Instant.now().getEpochSecond();
        long deltaDays = Math.max(0, (now - lastAccessed) / 86400);

        // 提取记忆类型，获取类型专属衰减配置（会 fallback 到 default）
        String typeKind = extractType(data);
        DecayConfig config = decayPolicy.getConfigForType(typeKind);
        if (config == null) {
            return null;
        }

        // 应用衰减公式（含 fallback 默认值兜底）
        double newImportance = currentImportance
            * Math.pow(config.getDailyDecay(FALLBACK_DAILY_DECAY), deltaDays);

        // 添加访问增益
        newImportance += config.getAccessGain(FALLBACK_ACCESS_GAIN);

        // 计算保底值：取 min_importance 与 type.importance_floor 的较大值
        double floor = config.getMinImportance(FALLBACK_MIN_IMPORTANCE);
        if (typeKind != null) {
            MemoryType type = ctx.getMetaModel().getType(typeKind).orElse(null);
            if (type != null && type.getMeta() != null) {
                // type.importance_floor 与 decay.min_importance 取较大值作为保底
                floor = Math.max(floor, type.getMeta().getImportanceFloor());
            }
        }
        newImportance = Math.max(floor, newImportance);

        // Clamp to [0, 1]
        newImportance = Math.min(1.0, Math.max(0, newImportance));

        // Update data
        String newData = updateImportanceInJson(data, newImportance);

        return new DecayResult(true, newData);
    }

    // ── JSON 字段提取辅助方法 ──────────────────────────────

    /**
     * Extract _last_accessed timestamp from wrapped JSON.
     * Returns 0 if not found.
     */
    /**
     * 从 JSON 提取 _last_accessed，优先用内存中的访问追踪器。
     */
    private long extractLastAccessed(String id, String data) {
        // 优先使用内存访问追踪器（read() 更新的最新值）
        long tracked = ctx.getTrackedAccess(id);
        if (tracked > 0) return tracked;

        // fallback 到 JSON 中存储的时间戳
        int idx = data.indexOf("\"_last_accessed\":");
        if (idx == -1) return 0;
        int start = idx + "\"_last_accessed\":".length();
        int end = data.indexOf(',', start);
        if (end == -1) end = data.indexOf('}', start);
        if (end == -1) return 0;
        try {
            return Long.parseLong(data.substring(start, end).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Extract _importance from wrapped JSON.
     * Returns 1.0 if not found.
     */
    private double extractImportance(String data) {
        int idx = data.indexOf("\"_importance\":");
        if (idx == -1) return 1.0;
        int start = idx + "\"_importance\":".length();
        int end = data.indexOf(',', start);
        if (end == -1) end = data.indexOf('}', start);
        if (end == -1) return 1.0;
        try {
            return Double.parseDouble(data.substring(start, end).trim());
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    /**
     * Extract _type from wrapped JSON.
     * Returns null if not found (兼容旧数据或格式变化).
     */
    private String extractType(String data) {
        int idx = data.indexOf("\"_type\":\"");
        if (idx == -1) return null;
        int start = idx + "\"_type\":\"".length();
        int end = data.indexOf('"', start);
        if (end == -1) return null;
        return data.substring(start, end);
    }

    /**
     * Update _importance value in wrapped JSON.
     */
    private String updateImportanceInJson(String data, double newImportance) {
        int idx = data.indexOf("\"_importance\":");
        if (idx == -1) return data;
        int start = idx + "\"_importance\":".length();
        int end = data.indexOf(',', start);
        if (end == -1) end = data.indexOf('}', start);
        if (end == -1) return data;

        String before = data.substring(0, start);
        String after = data.substring(end);
        return before + newImportance + after;
    }

    public record DecayResult(boolean changed, String newData) {}

    public record LifecycleStatus(String status, String reason) {
        public LifecycleStatus(String status) {
            this(status, "");
        }
    }

    public record LifecycleSummary(int total, int stale, int archived, int purged) {}
}
