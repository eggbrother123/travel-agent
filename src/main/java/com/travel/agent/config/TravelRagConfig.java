package com.travel.agent.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E2 向量库升级：SimpleVectorStore（内存，重启全丢、启动全量重建）→ PgVectorStore（持久化）。
 *
 * 为什么选 PGVector 而不是 Milvus（面试点）：
 *  - 体量匹配：12 chunks 的知识库用 Milvus 是高射炮打蚊子（Milvus standalone 要拉
 *    etcd+minio 三个容器）；PGVector 一个 Postgres 全搞定，还白送 SQL 生态（备份/运维）
 *  - 迁移路径：放大到千万级向量、需要分布式时再换 Milvus——选型跟数据量走，不跟热度走
 *
 * dimensions 必须 1024（bge-m3 输出维度）——默认 1536 是 OpenAI text-embedding 的，
 * 不改会在写入时炸维度不匹配。
 *
 * 建库时序（踩坑）：initializeSchema 的建表在 afterPropertiesSet 生命周期回调里执行，
 * 若在 @Bean 方法体内直接 COUNT/写入会赶在建表前 → relation "vector_store" does not exist。
 * 所以灌数逻辑放 KnowledgeLoader 的 ApplicationReadyEvent（启动完成后跑，不阻塞启动）。
 */
@Configuration
public class TravelRagConfig {

    @Bean
    public VectorStore travelVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1024)          // bge-m3 输出维度（默认 1536 是 OpenAI 的，会炸）
                .initializeSchema(true)    // afterPropertiesSet 时自动建 vector_store 表
                .build();
    }
}
