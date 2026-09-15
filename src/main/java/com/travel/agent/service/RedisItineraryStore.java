package com.travel.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.domain.Itinerary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * E1：行程状态存 Redis（String 结构，JSON 序列化，TTL 与会话记忆对齐 24h）。
 *
 * 为什么行程状态也要进 Redis（和对话记忆同一个理由）：
 *   内存 Map 重启即失、多实例各一份——负载均衡后"调整行程"可能打到没状态的那台。
 *   记忆和状态共用一套可用性设计：Redis 为主 + 实例内存影子副本降级。
 *
 * 面试对比点（数据结构选型）：
 *   对话记忆用 List（天然有序、可滑窗 LTRIM）；行程状态用 String（整存整取，
 *   一次读整个 record 树，无需增量操作）。结构跟着访问模式走，不为炫技。
 */
@Component
public class RedisItineraryStore implements ItineraryStore {

    private static final String KEY_PREFIX = "travel:itinerary:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** 影子副本：Redis 故障时本实例仍可读写（代价同 RedisChatMemory 注释） */
    private final Map<String, Itinerary> shadow = new ConcurrentHashMap<>();

    public RedisItineraryStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(String cid, Itinerary itinerary) {
        shadow.put(cid, itinerary);
        try {
            redis.opsForValue().set(key(cid), objectMapper.writeValueAsString(itinerary), TTL);
        } catch (Exception e) {
            System.out.println(">>> [RedisItineraryStore] Redis 写入失败，降级为实例内存：" + e.getMessage());
        }
    }

    @Override
    public Itinerary get(String cid) {
        try {
            String json = redis.opsForValue().get(key(cid));
            if (json == null) {
                return null;   // Redis 里确实没有（新会话/已过期），这不是故障，不碰影子副本
            }
            return objectMapper.readValue(json, Itinerary.class);
        } catch (Exception e) {
            System.out.println(">>> [RedisItineraryStore] Redis 读取失败，降级读实例内存：" + e.getMessage());
            return shadow.get(cid);
        }
    }

    private String key(String cid) {
        return KEY_PREFIX + cid;
    }
}
