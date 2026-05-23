package com.memory.engine.manager;

import com.memory.model.MetaModel;
import com.memory.model.decay.DecayConfig;
import com.memory.model.decay.DecayPolicy;
import com.memory.model.decay.LifecycleConfig;
import com.memory.runtime.MemoryRuntimeContext;
import com.memory.spi.MemoryStore;

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

    private final MemoryRuntimeContext ctx;

    public DecayMgr(MemoryRuntimeContext ctx) {
        this.ctx = ctx;
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
        MemoryStore store = ctx.getStore("json");
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

        return decayed;
    }

    /**
     * Check lifecycle status for a specific memory.
     * Determines if memory should be marked stale, archived, or purged.
     *
     * @param memoryId memory ID
     * @return lifecycle status
     */
    public LifecycleStatus checkLifecycle(String memoryId) {
        MetaModel model = ctx.getMetaModel();
        MemoryStore store = ctx.getStore("json");
        LifecycleConfig lifecycle = model.getDecay().getLifecycle();

        String data = store.load(memoryId);
        if (data == null) {
            return new LifecycleStatus("not_found");
        }

        long lastAccessed = extractLastAccessed(data);
        double importance = extractImportance(data);
        long now = Instant.now().getEpochSecond();
        long daysSinceAccess = (now - lastAccessed) / 86400;

        // Purge check (highest priority)
        if (importance < lifecycle.getPurgeWhenImportanceBelow()) {
            store.delete(memoryId);
            return new LifecycleStatus("purged", "importance=" + importance + " < " + lifecycle.getPurgeWhenImportanceBelow());
        }
        if (daysSinceAccess > lifecycle.getPurgeWhenStaleDays()) {
            store.delete(memoryId);
            return new LifecycleStatus("purged", "stale for " + daysSinceAccess + " days");
        }

        // Archive check
        if (daysSinceAccess > lifecycle.getArchiveAfterDays()) {
            return new LifecycleStatus("archive", "stale for " + daysSinceAccess + " days");
        }

        // Stale check
        if (daysSinceAccess > lifecycle.getStaleAfterDays()) {
            return new LifecycleStatus("stale", "inactive for " + daysSinceAccess + " days");
        }

        return new LifecycleStatus("active");
    }

    /**
     * Run lifecycle check on all memories.
     *
     * @return summary of actions taken
     */
    public LifecycleSummary runLifecycleCheck() {
        MemoryStore store = ctx.getStore("json");
        Set<String> allIds = store.listAll();

        int stale = 0, archived = 0, purged = 0;
        for (String id : allIds) {
            LifecycleStatus status = checkLifecycle(id);
            switch (status.status()) {
                case "stale" -> stale++;
                case "archive" -> archived++;
                case "purged" -> purged++;
            }
        }
        return new LifecycleSummary(allIds.size(), stale, archived, purged);
    }

    /**
     * Apply decay formula to a single memory entry.
     * Formula: new_importance = old_importance * (daily_decay ^ delta_days) + access_gain
     * Floors at min_importance.
     */
    private DecayResult applyDecay(String id, String data, DecayPolicy decayPolicy) {
        long lastAccessed = extractLastAccessed(data);
        double currentImportance = extractImportance(data);

        long now = Instant.now().getEpochSecond();
        long deltaDays = Math.max(0, (now - lastAccessed) / 86400);

        // Get decay config for this memory's type
        DecayConfig config = decayPolicy.getDefaultConfig();
        if (config == null) {
            return null;
        }

        // Apply decay formula
        double newImportance = currentImportance * Math.pow(config.getDailyDecay(), deltaDays);

        // Add access gain (small bonus to prevent rapid decay)
        newImportance += config.getAccessGain();

        // Floor at min_importance
        newImportance = Math.max(config.getMinImportance(), newImportance);

        // Clamp to [0, 1]
        newImportance = Math.min(1.0, Math.max(0, newImportance));

        // Update data
        String newData = updateImportanceInJson(data, newImportance);

        return new DecayResult(true, newData);
    }

    /**
     * Extract _last_accessed timestamp from wrapped JSON.
     * Returns 0 if not found.
     */
    private long extractLastAccessed(String data) {
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
