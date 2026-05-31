package com.memory.agent.spi;

import com.memory.model.MemoryRecord;
import com.memory.model.MetaModel;
import com.memory.spi.SPI;

import java.util.List;
import java.util.Map;

/**
 * 冲突检测器 SPI 扩展点。
 *
 * 检测新提取的信息是否与已有记忆冲突（重复、矛盾、取代等）。
 */
@SPI(name = "conflict-detector", description = "冲突检测器")
public interface ConflictDetector {

    /** 检测器标识，对应 DSL agent.conflict.engine */
    String name();

    /** 初始化（从 DSL 接收引擎参数） */
    void init(Map<String, Object> params);

    /**
     * 检测新信息与已有记忆之间的冲突。
     */
    ConflictResult detect(Map<String, Object> newFields, List<MemoryRecord> existing,
                          MetaModel model);

    record ConflictResult(boolean hasConflict, String description,
                          List<String> conflictingIds, double severity) {}
}
