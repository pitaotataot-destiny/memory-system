package com.memory.model.agent;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Agent 类型提示 — types.*.agent DSL 节点。
 * 帮助 IntentClassifier 和 InformationExtractor 理解每种记忆类型。
 */
public class AgentTypeHint {

    private String prompt;
    private List<String> examples = Collections.emptyList();
    private Map<String, String> fieldHints = Collections.emptyMap();

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public List<String> getExamples() { return examples; }
    public void setExamples(List<String> examples) { this.examples = examples; }

    public Map<String, String> getFieldHints() { return fieldHints; }
    public void setFieldHints(Map<String, String> fieldHints) { this.fieldHints = fieldHints; }
}
