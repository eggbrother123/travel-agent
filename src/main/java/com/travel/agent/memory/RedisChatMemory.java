package com.travel.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * E1 企业级演进：Redis 集中对话记忆（自研，Spring AI 1.0.0 只有内存/JDBC 实现）。
 *
 * 为什么必须集中存储（面试核心一句话）：
 *   内存版 ChatMemory 各实例各存各的——负载均衡把第二轮对话打到另一台机器，
 *   记忆就"失忆"了（更糟的是各实例都写，数据分叉=串话）。Redis 是唯一真相源。
 *
 * Redis 结构设计：
 *   key   = travel:chat:{cid}          一个会话一个 List
 *   写入  = RPUSH + EXPIRE（滑动 TTL：有新消息就续命 24h）
 *   滑窗  = LTRIM -N..-1 只留最近 20 条（等价 MessageWindowChatMemory 的窗口语义）
 *   读取  = LRANGE -lastN..-1（天然 oldest→newest）
 *
 * 降级设计（影子副本）：
 *   写：Redis 成功后同时写本实例内存副本；Redis 异常→只写内存并告警日志。
 *   读：优先 Redis；异常→读内存副本。
 *   降级代价要讲清楚：单实例内会话仍可用，但【跨实例共享】和【重启存活】能力丢失——
 *   降级不是免费的，是拿一致性换可用性。
 *
 * 序列化取舍：role+text 而非完整 Message（tool calls/metadata 不落库）。
 * 我们的 Advisor 只持久化 user/assistant 文本消息，够用；完整序列化是过度设计。
 */
@Component
public class RedisChatMemory implements ChatMemory {

    private static final String KEY_PREFIX = "travel:chat:";
    private static final int WINDOW = 20;
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** 影子副本：Redis 故障时的降级读写目标（同窗口语义） */
    private final ChatMemory shadow = MessageWindowChatMemory.builder()
            .maxMessages(WINDOW)
            .build();

    /** 消息的精简序列化结构（取舍见类注释） */
    record MsgDto(String role, String text) {}

    public RedisChatMemory(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void add(String conversationId, Message message) {
        shadow.add(conversationId, message);   // 影子副本总是写（便宜，且降级时数据最新）
        try {
            String json = objectMapper.writeValueAsString(
                    new MsgDto(message.getMessageType().name(), message.getText()));
            String key = key(conversationId);
            redis.opsForList().rightPush(key, json);
            redis.opsForList().trim(key, -WINDOW, -1);   // 滑窗：只留最近 20 条
            redis.expire(key, TTL);                       // 滑动 TTL：活跃会话不过期
        } catch (Exception e) {
            System.out.println(">>> [RedisChatMemory] Redis 写入失败，降级为实例内存：" + e.getMessage());
        }
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        messages.forEach(m -> add(conversationId, m));
    }

    @Override
    public List<Message> get(String conversationId) {
        try {
            // 读最近 WINDOW 条（LRANGE 负下标天然 oldest→newest），窗口语义与写入侧 LTRIM 双保险
            List<String> raw = redis.opsForList().range(key(conversationId), -WINDOW, -1);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<Message> result = new ArrayList<>(raw.size());
            for (String json : raw) {
                Message m = deserialize(json);
                if (m != null) result.add(m);
            }
            return result;
        } catch (Exception e) {
            System.out.println(">>> [RedisChatMemory] Redis 读取失败，降级读实例内存：" + e.getMessage());
            return shadow.get(conversationId);
        }
    }

    @Override
    public void clear(String conversationId) {
        shadow.clear(conversationId);
        try {
            redis.delete(key(conversationId));
        } catch (Exception e) {
            System.out.println(">>> [RedisChatMemory] Redis 清除失败（忽略）：" + e.getMessage());
        }
    }

    private String key(String cid) {
        return KEY_PREFIX + cid;
    }

    /** role+text → Message 对象。TOOL 类型不落库（见类注释取舍），解析失败的条目跳过不炸整批 */
    private Message deserialize(String json) {
        try {
            MsgDto dto = objectMapper.readValue(json, MsgDto.class);
            return switch (dto.role()) {
                case "USER" -> new UserMessage(dto.text());
                case "ASSISTANT" -> new AssistantMessage(dto.text());
                case "SYSTEM" -> new SystemMessage(dto.text());
                default -> null;
            };
        } catch (Exception e) {
            System.out.println(">>> [RedisChatMemory] 单条消息解析失败，跳过：" + e.getMessage());
            return null;
        }
    }
}
