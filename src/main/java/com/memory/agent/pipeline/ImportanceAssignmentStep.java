package com.memory.agent.pipeline;

import com.memory.agent.spi.ImportanceAssigner;
import com.memory.model.MetaModel;

/**
 * 同步步骤 3：重要性分配。
 */
class ImportanceAssignmentStep implements PipelineStep {

    private final ImportanceAssigner assigner;
    private final MetaModel model;

    ImportanceAssignmentStep(ImportanceAssigner assigner, MetaModel model) {
        this.assigner = assigner;
        this.model = model;
    }

    @Override
    public String name() { return "importance-assign"; }

    @Override
    public void execute(PipelineContext ctx) {
        double imp = assigner.assign(ctx.getTypeKind(), ctx.getExtractedFields(),
            ctx.getExtractedTags(), model);
        ctx.setImportance(imp);
    }
}
