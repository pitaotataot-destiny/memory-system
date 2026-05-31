package com.memory.model;

/**
 * Ingest 管道决策结果。
 */
public enum IngestDecision {
    /** 新建记忆 */
    STORE,
    /** 更新已有记忆 */
    UPDATE,
    /** 与已有记忆合并 */
    MERGE,
    /** 忽略（重复或低价值） */
    IGNORE,
    /** 需要外部确认后再决定 */
    CONFIRM
}
