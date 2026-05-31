package com.memory.dsl;

import com.memory.model.MetaModel;
import com.memory.model.decay.DecayConfig;
import com.memory.model.decay.DecayPolicy;
import com.memory.model.decay.LifecycleConfig;
import com.memory.model.enums.ActionKind;
import com.memory.model.enums.MemoryTypeKind;
import com.memory.model.enums.MergeStrategy;
import com.memory.model.enums.SearchEngineKind;
import com.memory.model.enums.StorageEngine;
import com.memory.model.enums.TriggerEvent;
import com.memory.model.globals.Globals;
import com.memory.model.globals.StorageConfig;
import com.memory.model.search.EngineConfig;
import com.memory.model.search.SearchConfig;
import com.memory.model.search.SearchStep;
import com.memory.model.search.SearchStrategy;
import com.memory.model.constraint.FieldConstraint;
import com.memory.model.constraint.TagConstraint;
import com.memory.model.constraint.TypeMeta;
import com.memory.model.trigger.Trigger;
import com.memory.model.trigger.TriggerAction;
import com.memory.model.trigger.TriggerCondition;
import com.memory.model.type.MemoryType;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DSL 解析器 — YAML → MetaModel。
 * 职责：解析 + 校验 + 默认值填充。引擎不接触原始 YAML。
 */
public class DSLParser {

    private static final Set<String> SUPPORTED_VERSIONS = Set.of("1.0");

    /**
     * 从文件路径解析
     */
    public MetaModel parse(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            Yaml yaml = new Yaml();
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = yaml.load(is);
            return parse(raw);
        } catch (Exception e) {
            throw new DSLParseException("Failed to parse DSL file: " + path, e);
        }
    }

    /**
     * 从 YAML 字符串解析
     */
    public MetaModel parseString(String content) {
        Yaml yaml = new Yaml();
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = yaml.load(content);
        return parse(raw);
    }

    @SuppressWarnings("unchecked")
    public MetaModel parse(Map<String, Object> raw) {
        String version = String.valueOf(raw.getOrDefault("version", "1.0"));
        if (!SUPPORTED_VERSIONS.contains(version)) {
            throw new DSLParseException("Unsupported DSL version: " + version);
        }

        MetaModel model = new MetaModel();
        model.setVersion(version);
        model.setGlobals(parseGlobals((Map<String, Object>) raw.getOrDefault("globals", Collections.emptyMap())));
        model.setTypes(parseTypes((Map<String, Object>) raw.getOrDefault("types", Collections.emptyMap())));
        model.setDecay(parseDecay((Map<String, Object>) raw.getOrDefault("decay", Collections.emptyMap())));
        model.setSearch(parseSearch((Map<String, Object>) raw.getOrDefault("search", Collections.emptyMap())));
        model.setTriggers(parseTriggers((List<Object>) raw.getOrDefault("triggers", Collections.emptyList())));

        validate(model);
        return model;
    }

    // ── Globals ──────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Globals parseGlobals(Map<String, Object> raw) {
        Globals g = new Globals();
        g.setDefaultType(MemoryTypeKind.fromValue((String) raw.getOrDefault("default_type", "fact")));
        g.setMaxMemorySize(asInt(raw.get("max_memory_size"), 5000));
        g.setDefaultTtlDays(asInt(raw.get("default_ttl_days"), 30));

        Map<String, Object> storageRaw = (Map<String, Object>) raw.getOrDefault("storage", Collections.emptyMap());
        StorageConfig sc = new StorageConfig();
        sc.setEngine(StorageEngine.fromValue((String) storageRaw.getOrDefault("engine", "json")));
        sc.setPath((String) storageRaw.getOrDefault("path", "./data"));
        sc.setEncoding((String) storageRaw.getOrDefault("encoding", "utf-8"));
        g.setStorage(sc);

        return g;
    }

    // ── Types ────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, MemoryType> parseTypes(Map<String, Object> raw) {
        Map<String, MemoryType> types = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String kind = entry.getKey();
            Map<String, Object> spec = (Map<String, Object>) entry.getValue();

            MemoryType mt = new MemoryType();
            mt.setKind(MemoryTypeKind.fromValue(kind));
            mt.setDescription((String) spec.getOrDefault("description", ""));
            mt.setFields(parseFields((Map<String, Object>) spec.getOrDefault("fields", Collections.emptyMap())));
            mt.setTags(parseTags((Map<String, Object>) spec.getOrDefault("tags", Collections.emptyMap())));
            mt.setMeta(parseMeta((Map<String, Object>) spec.getOrDefault("meta", Collections.emptyMap())));

            types.put(kind, mt);
        }
        return types;
    }

    @SuppressWarnings("unchecked")
    private Map<String, FieldConstraint> parseFields(Map<String, Object> raw) {
        Map<String, FieldConstraint> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            Map<String, Object> spec = (Map<String, Object>) entry.getValue();
            FieldConstraint fc = new FieldConstraint();
            fc.setType((String) spec.getOrDefault("type", "string"));
            fc.setRequired(asBoolean(spec.get("required"), false));
            fc.setMin(spec.containsKey("min") ? asDouble(spec.get("min")) : null);
            fc.setMax(spec.containsKey("max") ? asDouble(spec.get("max")) : null);
            fc.setDefaultValue(spec.get("default"));
            fc.setFormat((String) spec.get("format"));
            fc.setEnumValues((List<String>) spec.get("enum"));
            fields.put(entry.getKey(), fc);
        }
        return fields;
    }

    @SuppressWarnings("unchecked")
    private TagConstraint parseTags(Map<String, Object> raw) {
        TagConstraint tc = new TagConstraint();
        tc.setMax(asInt(raw.get("max"), 10));
        tc.setAllowedPattern((String) raw.get("allowed_pattern"));
        return tc;
    }

    @SuppressWarnings("unchecked")
    private TypeMeta parseMeta(Map<String, Object> raw) {
        TypeMeta tm = new TypeMeta();
        tm.setImmutableFields((List<String>) raw.getOrDefault("immutable_fields", Collections.emptyList()));
        tm.setUniqueBy((List<String>) raw.getOrDefault("unique_by", Collections.emptyList()));
        tm.setImportanceFloor(asDouble(raw.get("importance_floor"), 0.1));
        tm.setEphemeral(asBoolean(raw.get("ephemeral"), false));
        return tm;
    }

    // ── Decay ────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private DecayPolicy parseDecay(Map<String, Object> raw) {
        DecayPolicy policy = new DecayPolicy();

        Map<String, Object> defaultRaw = (Map<String, Object>) raw.getOrDefault("default", Collections.emptyMap());
        DecayConfig defaultConfig = new DecayConfig();
        defaultConfig.setDailyDecay(asDouble(defaultRaw.get("daily_decay"), 0.92));
        defaultConfig.setAccessGain(asDouble(defaultRaw.get("access_gain"), 0.05));
        defaultConfig.setMinImportance(asDouble(defaultRaw.get("min_importance"), 0.1));
        policy.setDefaultConfig(defaultConfig);

        Map<String, Object> overridesRaw = (Map<String, Object>) raw.getOrDefault("type_overrides", Collections.emptyMap());
        Map<String, DecayConfig> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : overridesRaw.entrySet()) {
            Map<String, Object> spec = (Map<String, Object>) entry.getValue();
            DecayConfig oc = new DecayConfig();
            oc.setDailyDecay(asDouble(spec.get("daily_decay"), defaultConfig.getDailyDecay()));
            oc.setAccessGain(asDouble(spec.get("access_gain"), defaultConfig.getAccessGain()));
            oc.setMinImportance(asDouble(spec.get("min_importance"), defaultConfig.getMinImportance()));
            overrides.put(entry.getKey(), oc);
        }
        policy.setTypeOverrides(overrides);

        Map<String, Object> lifecycleRaw = (Map<String, Object>) raw.getOrDefault("lifecycle", Collections.emptyMap());
        LifecycleConfig lc = new LifecycleConfig();
        lc.setStaleAfterDays(asInt(lifecycleRaw.get("stale_after_days"), 14));
        lc.setArchiveAfterDays(asInt(lifecycleRaw.get("archive_after_days"), 30));

        Map<String, Object> purgeRaw = (Map<String, Object>) lifecycleRaw.getOrDefault("purge_when", Collections.emptyMap());
        lc.setPurgeWhenImportanceBelow(asDouble(purgeRaw.get("importance_below"), 0.1));
        lc.setPurgeWhenStaleDays(asInt(purgeRaw.get("or_stale_days"), 60));
        policy.setLifecycle(lc);

        return policy;
    }

    // ── Search ───────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private SearchConfig parseSearch(Map<String, Object> raw) {
        SearchConfig sc = new SearchConfig();

        // engines
        Map<String, Object> enginesRaw = (Map<String, Object>) raw.getOrDefault("engines", Collections.emptyMap());
        Map<String, EngineConfig> engines = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : enginesRaw.entrySet()) {
            Map<String, Object> spec = (Map<String, Object>) entry.getValue();
            EngineConfig ec = new EngineConfig();
            ec.setKind(SearchEngineKind.fromValue(entry.getKey()));
            ec.setEnabled(asBoolean(spec.get("enabled"), true));
            ec.setDescription((String) spec.getOrDefault("description", ""));
            ec.setParams((Map<String, Object>) spec.getOrDefault("params", Collections.emptyMap()));
            engines.put(entry.getKey(), ec);
        }
        sc.setEngines(engines);

        // strategies
        Map<String, Object> strategiesRaw = (Map<String, Object>) raw.getOrDefault("strategies", Collections.emptyMap());
        Map<String, SearchStrategy> strategies = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : strategiesRaw.entrySet()) {
            Map<String, Object> spec = (Map<String, Object>) entry.getValue();
            SearchStrategy ss = new SearchStrategy();
            ss.setName(entry.getKey());
            ss.setDescription((String) spec.getOrDefault("description", ""));
            ss.setMerge(MergeStrategy.fromValue((String) spec.getOrDefault("merge", "weighted_score")));
            ss.setLimit(asInt(spec.get("limit"), 10));

            List<SearchStep> steps = new ArrayList<>();
            for (Map<String, Object> stepRaw : (List<Map<String, Object>>) spec.getOrDefault("steps", Collections.emptyList())) {
                SearchStep step = new SearchStep();
                step.setEngine(SearchEngineKind.fromValue((String) stepRaw.get("engine")));
                step.setWeight(asDouble(stepRaw.get("weight"), 1.0));
                step.setTopK(asInt(stepRaw.get("top_k"), 20));
                step.setFallback(asBoolean(stepRaw.get("fallback"), false));
                steps.add(step);
            }
            ss.setSteps(steps);
            strategies.put(entry.getKey(), ss);
        }
        sc.setStrategies(strategies);

        // type filters
        Map<String, Object> typeFilters = (Map<String, Object>) raw.getOrDefault("type_filters", Collections.emptyMap());
        sc.setTypeFiltersInclude((List<String>) typeFilters.getOrDefault("include", Collections.emptyList()));
        sc.setTypeFiltersExclude((List<String>) typeFilters.getOrDefault("exclude", Collections.emptyList()));

        return sc;
    }

    // ── Triggers ─────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Trigger> parseTriggers(List<Object> rawList) {
        List<Trigger> triggers = new ArrayList<>();
        for (Object item : rawList) {
            Map<String, Object> spec = (Map<String, Object>) item;
            Trigger t = new Trigger();
            t.setName((String) spec.get("name"));

            Map<String, Object> whenRaw = (Map<String, Object>) spec.getOrDefault("when", Collections.emptyMap());
            TriggerCondition tc = new TriggerCondition();
            tc.setSchedule((String) whenRaw.get("schedule"));
            tc.setCondition((String) whenRaw.get("condition"));
            if (whenRaw.containsKey("event")) {
                tc.setEvent(TriggerEvent.fromValue((String) whenRaw.get("event")));
            }
            tc.setField((String) whenRaw.get("field"));
            t.setWhen(tc);

            Map<String, Object> thenRaw = (Map<String, Object>) spec.getOrDefault("then", Collections.emptyMap());
            TriggerAction ta = new TriggerAction();
            ta.setAction(ActionKind.fromValue((String) thenRaw.get("action")));
            ta.setTarget((String) thenRaw.getOrDefault("target", "all"));
            ta.setOrderBy((String) thenRaw.get("order_by"));
            ta.setLimit(asInt(thenRaw.get("limit"), 100));
            ta.setRule((String) thenRaw.get("rule"));
            ta.setCondition((String) thenRaw.get("condition"));
            t.setThen(ta);

            triggers.add(t);
        }
        return triggers;
    }

    // ── Validation ───────────────────────────────────────

    private void validate(MetaModel model) {
        if (model.getGlobals() == null) {
            throw new DSLParseException("globals section is required");
        }
        if (model.getTypes() == null || model.getTypes().isEmpty()) {
            throw new DSLParseException("At least one memory type must be defined");
        }
        if (model.getDecay() == null) {
            throw new DSLParseException("decay section is required");
        }
        if (model.getSearch() == null) {
            throw new DSLParseException("search section is required");
        }
        // Validate decay overrides reference valid types
        for (String typeKey : model.getDecay().getTypeOverrides().keySet()) {
            if (!model.getTypes().containsKey(typeKey)) {
                throw new DSLParseException("decay.type_overrides references unknown type: " + typeKey);
            }
        }
        // Validate search strategy steps reference valid engines
        if (model.getSearch() != null) {
            for (SearchStrategy strategy : model.getSearch().getStrategies().values()) {
                for (SearchStep step : strategy.getSteps()) {
                    if (!model.getSearch().getEngines().containsKey(step.getEngine().getValue())) {
                        throw new DSLParseException("search strategy '" + strategy.getName()
                                + "' references unknown engine: " + step.getEngine().getValue());
                    }
                }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────

    private int asInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        return Integer.parseInt(String.valueOf(value));
    }

    private double asDouble(Object value, double defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).doubleValue();
        return Double.parseDouble(String.valueOf(value));
    }

    private double asDouble(Object value) {
        if (value == null) throw new DSLParseException("Value is null for required double");
        if (value instanceof Number) return ((Number) value).doubleValue();
        return Double.parseDouble(String.valueOf(value));
    }

    private boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
