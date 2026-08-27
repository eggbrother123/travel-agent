package com.travel.agent.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;

/**
 * M3 知识层：旅行知识库（Agentic RAG 的「离线建库」半场）。
 *
 * 管道（和 spring-ai-hello 的 notesVectorStore 同一套已验证模式）：
 *   resources/knowledge/*.md → TextReader → TokenTextSplitter 切块 → bge-m3 向量化 → SimpleVectorStore
 *
 * 三个关键设计（对应 Day9 设计题的「放大点」，小规模亲手做一遍）：
 *  - 真实文档源：6 城市知识库 Markdown，不是硬编码字符串——新增城市只要丢一个 md 文件进来
 *  - 切块：城市文件约 1000~1500 字，TokenTextSplitter 默认 400 token/块，切块后每个 chunk 语义聚焦
 *  - 溯源：城市名写进 metadata（tokyo.md → "东京"），检索结果能标注「信息来自东京知识库」
 *
 * 放大点（面试讲）：SimpleVectorStore → Milvus/PGVector；全量重建 → 增量索引；
 * 纯向量 → 混合检索（BM25+向量）+ rerank。
 */
@Configuration
public class TravelRagConfig {

    /** classpath 下的知识库目录（打包后在 jar 里，用 PatternResolver 读） */
    private static final String KNOWLEDGE_PATTERN = "classpath:knowledge/*.md";

    /** 文件名 → 城市中文名（metadata 溯源用；新增城市文件在这里加一行） */
    private static final java.util.Map<String, String> FILE_TO_CITY = java.util.Map.of(
            "tokyo.md", "东京",
            "beijing.md", "北京",
            "hangzhou.md", "杭州",
            "chengdu.md", "成都",
            "paris.md", "巴黎",
            "london.md", "伦敦"
    );

    @Bean
    public VectorStore travelVectorStore(EmbeddingModel embeddingModel) {
        VectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        int totalChunks = 0;

        try {
            // 1. 扫描 knowledge/*.md（jar 内资源用 PatternResolver 才能枚举）
            Resource[] files = new PathMatchingResourcePatternResolver()
                    .getResources(KNOWLEDGE_PATTERN);

            for (Resource file : files) {
                String filename = file.getFilename();
                String city = FILE_TO_CITY.getOrDefault(filename, filename);

                // 2. 读文档 → 城市名进 metadata（检索结果可溯源）
                TextReader reader = new TextReader(file);
                List<Document> rawDocs = reader.get();
                rawDocs.forEach(d -> {
                    d.getMetadata().put("city", city);
                    d.getMetadata().put("source", filename);
                });

                // 3. 切块（默认 400 token/块 + 重叠，防语义割裂）
                TextSplitter splitter = new TokenTextSplitter();
                List<Document> chunks = splitter.apply(rawDocs);

                // 4. bge-m3 向量化入库
                store.add(chunks);
                totalChunks += chunks.size();
                System.out.println(">>> [旅行知识库] " + city + "（" + filename + "）→ " + chunks.size() + " 块");
            }
            System.out.println(">>> [旅行知识库] 建库完成，共 " + totalChunks + " 个 chunk（"
                    + FILE_TO_CITY.size() + " 个城市）");
        } catch (Exception e) {
            // 建库失败不让应用挂掉：知识层是增强，不是依赖（Agent 还能靠常识兜底）
            System.out.println(">>> [旅行知识库] 建库失败，Agent 将以纯工具+常识模式运行：" + e.getMessage());
        }
        return store;
    }
}
