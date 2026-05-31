package com.memory.agent.pipeline;

import com.memory.agent.spi.IntentClassifier;
import com.memory.model.MetaModel;

/**
 * 同步步骤 1：意图分类。
 */
class IntentClassificationStep implements PipelineStep {

    private final IntentClassifier classifier;
    private final MetaModel model;

    IntentClassificationStep(IntentClassifier classifier, MetaModel model) {
        this.classifier = classifier;
        this.model = model;
    }

    @Override
    public String name() { return "intent-classify"; }

    @Override
    public void execute(PipelineContext ctx) {
        IntentClassifier.ClassifyResult result = classifier.classify(ctx.getRawText(), model);
        ctx.setTypeKind(result.typeKind());
        ctx.setConfidence(result.confidence());
    }
}
