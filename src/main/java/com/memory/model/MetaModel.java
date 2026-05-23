package com.memory.model;

import com.memory.model.decay.DecayPolicy;
import com.memory.model.globals.Globals;
import com.memory.model.search.SearchConfig;
import com.memory.model.trigger.Trigger;
import com.memory.model.type.MemoryType;

import java.util.*;

/**
 * 顶层 Meta Model — 所有 DSL 规则解析后的统一内部模型。
 * 引擎只与 MetaModel 交互，不直接读 YAML。
 */
public class MetaModel {
    private String version;
    private Globals globals;
    private Map<String, MemoryType> types = new HashMap<>();
    private DecayPolicy decay;
    private SearchConfig search;
    private List<Trigger> triggers = new ArrayList<>();

    public MetaModel() {}

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public Globals getGlobals() { return globals; }
    public void setGlobals(Globals globals) { this.globals = globals; }

    public Map<String, MemoryType> getTypes() { return types; }
    public void setTypes(Map<String, MemoryType> types) { this.types = types; }

    public DecayPolicy getDecay() { return decay; }
    public void setDecay(DecayPolicy decay) { this.decay = decay; }

    public SearchConfig getSearch() { return search; }
    public void setSearch(SearchConfig search) { this.search = search; }

    public List<Trigger> getTriggers() { return triggers; }
    public void setTriggers(List<Trigger> triggers) { this.triggers = triggers; }

    /**
     * 按类型名获取 MemoryType
     */
    public Optional<MemoryType> getType(String name) {
        return Optional.ofNullable(types.get(name));
    }

    /**
     * 按名称获取 SearchStrategy
     */
    public Optional<com.memory.model.search.SearchStrategy> getSearchStrategy(String name) {
        return Optional.ofNullable(search != null ? search.getStrategies().get(name) : null);
    }
}
