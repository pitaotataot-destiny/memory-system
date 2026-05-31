package com.memory.model.agent;

/**
 * Agent 管道顶层配置 — agent DSL 节点。
 */
public class AgentConfig {

    private boolean enabled = true;
    private IntentConfig intent = new IntentConfig();
    private ExtractionConfig extraction = new ExtractionConfig();
    private ConflictConfig conflict = new ConflictConfig();
    private ImportanceConfig importance = new ImportanceConfig();
    private ConsolidationConfig consolidation = new ConsolidationConfig();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public IntentConfig getIntent() { return intent; }
    public void setIntent(IntentConfig intent) { this.intent = intent; }

    public ExtractionConfig getExtraction() { return extraction; }
    public void setExtraction(ExtractionConfig extraction) { this.extraction = extraction; }

    public ConflictConfig getConflict() { return conflict; }
    public void setConflict(ConflictConfig conflict) { this.conflict = conflict; }

    public ImportanceConfig getImportance() { return importance; }
    public void setImportance(ImportanceConfig importance) { this.importance = importance; }

    public ConsolidationConfig getConsolidation() { return consolidation; }
    public void setConsolidation(ConsolidationConfig consolidation) { this.consolidation = consolidation; }
}
