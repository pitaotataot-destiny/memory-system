package com.memory.agent.spi;

import com.memory.model.MemoryRecord;
import com.memory.model.MetaModel;
import com.memory.spi.SPI;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 记忆合并器 SPI 扩展点。
 *
 * 发现可合并的记忆组并执行合并操作。
 */
@SPI(name = "memory-consolidator", description = "记忆合并器")
public interface MemoryConsolidator {

    /** 合并器标识，对应 DSL agent.consolidation.engine */
    String name();

    /** 初始化（从 DSL 接收引擎参数） */
    void init(Map<String, Object> params);

    /**
     * 寻找可合并的记忆组。
     */
    List<ConsolidationCandidate> findCandidates(List<MemoryRecord> memories,
                                                MetaModel model);

    record ConsolidationCandidate(List<String> memoryIds, double similarityScore,
                                   String mergeReason) {
        public ConsolidationCandidate {
            memoryIds = Collections.unmodifiableList(memoryIds);
        }
    }
}
