package com.travel.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.domain.Itinerary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Optional;

/**
 * E5 生成结果缓存（"缓存直达"省成本的第一级）：
 * 相同参数（目的地/天数/预算/偏好）的生成请求，TTL 内直接返回缓存行程，模型调用 1→0。
 *
 * 设计要点：
 *  - key = 参数指纹 md5：参数必须先【脱敏再入 key】——否则含 PII 的变体请求会打穿缓存，
 *    且原始 PII 会进 Redis key（泄漏面）
 *  - TTL 配置化：天气是生成时刻的快照，缓存期内不刷新——旅行攻略场景可接受
 *    （生成时本来就是预报），要更严可调短
 *  - 命中也要绑定会话（保存行程到 session）——用户后续 adjust 才有状态，这些操作零模型调用
 *  - hit/miss 进 metrics：缓存是否值得，看命中率说话（面试：缓存效果要量化）
 *
 * 诚实边界：FAQ 缓存对"生成器"类应用的收益取决于重复请求占比——本场景（同参数重试/
 * 多人查同一城市）有真实命中；个性化参数为主的场景收益低，别为缓存而缓存。
 */
@Component
public class GenerationCache {

    private static final String KEY_PREFIX = "travel:cache:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final MeterRegistry registry;
    private final Duration ttl;

    public GenerationCache(StringRedisTemplate redis, ObjectMapper objectMapper, MeterRegistry registry,
                           @Value("${travel.cache.ttl-hours:24}") long ttlHours) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.ttl = Duration.ofHours(ttlHours);
    }

    /** 查缓存。参数应是【脱敏后】的值（与 put 侧一致，否则互相打不中） */
    public Optional<Itinerary> get(String destination, int days, double budget, String preferences) {
        String key = key(destination, days, budget, preferences);
        try {
            String json = redis.opsForValue().get(key);
            registry.counter("travel.cache", "result", json != null ? "hit" : "miss").increment();
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, Itinerary.class));
        } catch (Exception e) {
            System.out.println(">>> [缓存] Redis 读失败，降级直生成（fail-open）：" + e.getMessage());
            return Optional.empty();   // 缓存是优化不是依赖：故障放行走模型
        }
    }

    public void put(String destination, int days, double budget, String preferences, Itinerary it) {
        try {
            redis.opsForValue().set(key(destination, days, budget, preferences),
                    objectMapper.writeValueAsString(it), ttl);
        } catch (Exception e) {
            System.out.println(">>> [缓存] Redis 写失败（忽略）：" + e.getMessage());
        }
    }

    /** 参数指纹：md5(目的地|天数|预算|偏好) —— 稳定且不含原始 PII */
    private String key(String destination, int days, double budget, String preferences) {
        return KEY_PREFIX + md5(destination + "|" + days + "|" + budget + "|" + preferences);
    }

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d)  {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
