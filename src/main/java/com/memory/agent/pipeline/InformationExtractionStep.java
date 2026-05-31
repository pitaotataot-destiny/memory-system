package com.memory.agent.pipeline;

import com.memory.agent.spi.InformationExtractor;
import com.memory.model.MetaModel;

/**
 * 同步步骤 2：信息提取。
 */
class InformationExtractionStep implements PipelineStep {

    private final InformationExtractor extractor;
    private final MetaModel model;

    InformationExtractionStep(InformationExtractor extractor, MetaModel model) {
        this.extractor = extractor;
        this.model = model;
    }

    @Override
    public String name() { return "info-extract"; }

    @Override
    public void execute(PipelineContext ctx) {
        InformationExtractor.ExtractedInfo info = extractor.extract(
            ctx.getRawText(), ctx.getTypeKind(), model);
        ctx.setExtractedFields(info.fields());
        ctx.setExtractedTags(info.tags());
    }
}
