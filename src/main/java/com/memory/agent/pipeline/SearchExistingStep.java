package com.memory.agent.pipeline;

import com.memory.MemoryClient;
import com.memory.engine.manager.SearchMgr;
import com.memory.model.MemoryRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * 异步步骤 5：搜索已有记忆（候选冲突检测）。
 */
class SearchExistingStep implements PipelineStep {

    // 最多取回的候选记忆数
    private static final int MAX_CANDIDATES = 5;

    private final MemoryClient client;

    SearchExistingStep(MemoryClient client) {
        this.client = client;
    }

    @Override
    public String name() { return "search-existing"; }

    @Override
    public void execute(PipelineContext ctx) {
        // 用 content 字段值作为搜索关键词
        Object content = ctx.getExtractedFields().get("content");
        if (content == null) return;

        String query = content.toString();
        List<SearchMgr.SearchResult> results = client.search(query);

        List<MemoryRecord> records = new ArrayList<>();
        int count = 0;
        for (SearchMgr.SearchResult r : results) {
            if (count >= MAX_CANDIDATES) break;
            // 排除自身
            if (r.memoryId().equals(ctx.getMemoryId())) continue;

            try {
                String raw = client.read(r.memoryId());
                if (raw != null) {
                    records.add(MemoryRecord.fromJson(raw));
                    count++;
                }
            } catch (Exception ignored) {
                // 读取失败跳过
            }
        }
        ctx.setExistingRecords(records);
    }
}
