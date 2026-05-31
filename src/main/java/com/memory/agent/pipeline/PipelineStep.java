package com.memory.agent.pipeline;

/**
 * 管道步骤接口。
 * 每个步骤读取 PipelineContext，处理后在 context 中写入结果。
 */
public interface PipelineStep {

    /** 步骤名称，用于日志和调试 */
    String name();

    /** 执行此步骤 */
    void execute(PipelineContext ctx);
}
