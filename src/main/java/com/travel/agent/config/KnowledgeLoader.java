package com.travel.agent.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * E2 知识库装载器：ApplicationReadyEvent 时检查 PGVector，表空才灌全量。
 *
 * 「增量索引」的诚实版本：PG 持久化后重启不再重建（旧版每次启动都要 bge-m3
 * 重新向量化全量文档）。真正的增量（文件变更检测 + 按文档 id upsert + 删除同步）
 * 是 TODO——当前文档集不变，变更检测收益为零，不提前上复杂度（YAGNI）。
 */
@Component
public class KnowledgeLoader {

    private static final String KNOWLEDGE_PATTERN = "classpath:knowledge/*.md";

    private final VectorStore travelVectorStore;
    private final JdbcTemplate jdbcTemplate;

    public KnowledgeLoader(VectorStore travelVectorStore, JdbcTemplate jdbcTemplate) {
        this.travelVectorStore = travelVectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadIfEmpty() {
        Integer existing;
        try {
            existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vector_store", Integer.class);
        } catch (Exception e) {
            System.out.println(">>> [旅行知识库] 计数查询失败（表未就绪？）：" + e.getMessage());
            existing = null;
        }
        if (existing != null && existing > 0) {
            System.out.println(">>> [旅行知识库] PGVector 已有 " + existing
                    + " 条向量，跳过建库（增量索引·简版）");
            return;
        }

        int totalChunks = 0;
        try {
            Resource[] files = new PathMatchingResourcePatternResolver()
                    .getResources(KNOWLEDGE_PATTERN);
            for (Resource file : files) {
                String filename = file.getFilename();
                String city = cityOf(filename);

                TextReader reader = new TextReader(file);
                List<Document> rawDocs = reader.get();
                rawDocs.forEach(d -> {
                    d.getMetadata().put("city", city);
                    d.getMetadata().put("source", filename);
                });

                TextSplitter splitter = new TokenTextSplitter();
                List<Document> chunks = splitter.apply(rawDocs);

                travelVectorStore.add(chunks);   // 写入 PG（持久化，重启不丢）
                totalChunks += chunks.size();
                System.out.println(">>> [旅行知识库] " + city + "（" + filename + "）→ " + chunks.size() + " 块");
            }
            System.out.println(">>> [旅行知识库] 全量建库完成，共 " + totalChunks + " 个 chunk 入 PGVector");
        } catch (Exception e) {
            System.out.println(">>> [旅行知识库] 建库失败：" + e.getMessage());
        }
    }

    /** 文件名 → 城市中文名（metadata 溯源 + filterExpression 按它过滤） */
    private String cityOf(String filename) {
        return switch (filename == null ? "" : filename) {
            case "tokyo.md" -> "东京";
            case "beijing.md" -> "北京";
            case "hangzhou.md" -> "杭州";
            case "chengdu.md" -> "成都";
            case "paris.md" -> "巴黎";
            case "london.md" -> "伦敦";
            default -> filename;
        };
    }
}
