package com.memory.engine.manager;

import com.memory.model.MemoryRecord;
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
 * JSON 反序列化通过 {@link MemoryRecord} 委托给 Jackson。
 */
public class DecayMgr {

    private static final Logger LOG = LoggerFactory.getLogger(DecayMgr.class);

    // 衰减公式默认参数（作为 getter fallback 值的兜底常量）
    private static final double FALLBACK_DAILY_DECAY = 0.92;
    private static final double FALLBACK_ACCESS_GAIN = 0.05;
    private static final double FALLBACK_MIN_IMPORTANCE = 0.1;

    // 一天秒数
    private static final long SECONDS_PER_DAY = 86400;
    // 重要性变化可忽略阈值（低于此值不触发写回）
    private static final double IMPORTANCE_EPSILON = 0.0001;

    private final MemoryRuntimeContext ctx;

    public DecayMgr(MemoryRuntimeContext ctx) {
        this.ctx = ctx;
    }

    private String getStoreName() {
        return ctx.getMetaModel().getGlobals().getStorage().getEngine().getValue();
    }

    /**
     * Run decay calculation on all memories.
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
            String raw = store.load(id);
            if (raw == null) continue;

            MemoryRecord record = MemoryRecord.fromJson(raw);
            if (applyDecay(id, record, decayPolicy)) {
                store.save(id, record.toJson());
                decayed.add(id);
            }
        }

        LOG.debug("Decay complete: {} processed, {} decayed", allIds.size(), decayed.size());
        return decayed;
    }

    /**
     * Check lifecycle status (纯查询，无副作用)。
     */
    public LifecycleStatus checkLifecycle(String memoryId) {
        MetaModel model = ctx.getMetaModel();
        MemoryStore store = ctx.getStore(getStoreName());
        LifecycleConfig lifecycle = model.getDecay().getLifecycle();

        String raw = store.load(memoryId);
        if (raw == null) {
            return new LifecycleStatus("not_found");
        }

        MemoryRecord record = MemoryRecord.fromJson(raw);
        long lastAccessed = getEffectiveLastAccess(memoryId, record);
        double importance = record.getImportance();
        long now = Instant.now().getEpochSecond();
        long daysSinceAccess = (now - lastAccessed) / SECONDS_PER_DAY;

        // Purge 条件检查（仅判断，删除由 runLifecycleCheck 负责）
        if (importance < lifecycle.getPurgeWhenImportanceBelow()) {
            return new LifecycleStatus("purged",
                "importance=" + importance + " < " + lifecycle.getPurgeWhenImportanceBelow());
        }
        if (daysSinceAccess > lifecycle.getPurgeWhenStaleDays()) {
            return new LifecycleStatus("purged", "stale for " + daysSinceAccess + " days");
        }
        if (daysSinceAccess > lifecycle.getArchiveAfterDays()) {
            return new LifecycleStatus("archive", "stale for " + daysSinceAccess + " days");
        }
        if (daysSinceAccess > lifecycle.getStaleAfterDays()) {
            return new LifecycleStatus("stale", "inactive for " + daysSinceAccess + " days");
        }

        return new LifecycleStatus("active");
    }

    /**
     * Run lifecycle check on all memories, performing purge deletions.
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
                    store.delete(id);
                    ctx.evictHot(id);
                    purged++;
                }
            }
        }
        LOG.debug("Lifecycle: total={}, stale={}, archived={}, purged={}",
            allIds.size(), stale, archived, purged);
        return new LifecycleSummary(allIds.size(), stale, archived, purged);
    }

    /**
     * Apply decay formula to a single memory record.
     * Modifies the record in place, returns true if changed.
     */
    private boolean applyDecay(String id, MemoryRecord record, DecayPolicy decayPolicy) {
        long lastAccessed = getEffectiveLastAccess(id, record);
        double currentImportance = record.getImportance();
        long now = Instant.now().getEpochSecond();
        long deltaDays = Math.max(0, (now - lastAccessed) / SECONDS_PER_DAY);

        // 获取类型专属衰减配置（含 fallback 到 default）
        DecayConfig config = decayPolicy.getConfigForType(record.getType());
        if (config == null) return false;

        double newImportance = currentImportance
            * Math.pow(config.getDailyDecay(FALLBACK_DAILY_DECAY), deltaDays)
            + config.getAccessGain(FALLBACK_ACCESS_GAIN);

        // 保底：max(min_importance, type.importance_floor)
        double floor = config.getMinImportance(FALLBACK_MIN_IMPORTANCE);
        String typeKind = record.getType();
        if (typeKind != null) {
            MemoryType type = ctx.getMetaModel().getType(typeKind).orElse(null);
            if (type != null && type.getMeta() != null) {
                floor = Math.max(floor, type.getMeta().getImportanceFloor());
            }
        }
        newImportance = Math.max(floor, Math.min(1.0, Math.max(0, newImportance)));

        if (Math.abs(newImportance - currentImportance) < IMPORTANCE_EPSILON) return false;

        record.setImportance(newImportance);
        return true;
    }

    /**
     * 获取有效的最后访问时间：优先内存追踪器，其次 MemoryRecord 中的值。
     */
    private long getEffectiveLastAccess(String memoryId, MemoryRecord record) {
        long tracked = ctx.getTrackedAccess(memoryId);
        return tracked > 0 ? tracked : record.getLastAccessed();
    }

    // ── 数据记录 ────────────────────────────────────────────

    public record LifecycleStatus(String status, String reason) {
        public LifecycleStatus(String status) {
            this(status, "");
        }
    }

    public record LifecycleSummary(int total, int stale, int archived, int purged) {}
}
