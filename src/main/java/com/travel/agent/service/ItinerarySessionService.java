package com.travel.agent.service;

import com.travel.agent.domain.Itinerary;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

/**
 * M4 会话服务（E1 重构）：把「两种记忆」分开管——本里程碑的核心设计不变，变的是底层。
 *
 *  1. 对话记忆（ChatMemory）：用户和小旅【说过什么】——现在 = RedisChatMemory
 *     （Redis 集中存储 + 窗口 20 条 + TTL 24h + 实例内存降级）
 *  2. 行程状态（ItineraryStore）：当前会话【最新版本的攻略对象】——现在 = RedisItineraryStore
 *     （同样的集中存储 + 降级策略）
 *
 * E1 重构要点：从「自己 new 内存实现」改为「构造器注入」——
 * 存储策略从写死变成可替换（接口化），这就是依赖倒置在小项目里的落法。
 * 多实例部署下两个 Store 都指向 Redis，任何一台实例都能服务任何会话。
 */
@Service
public class ItinerarySessionService {

    private final ChatMemory chatMemory;
    private final ItineraryStore itineraryStore;

    public ItinerarySessionService(ChatMemory chatMemory, ItineraryStore itineraryStore) {
        this.chatMemory = chatMemory;
        this.itineraryStore = itineraryStore;
    }

    public ChatMemory chatMemory() {
        return chatMemory;
    }

    public void saveItinerary(String cid, Itinerary itinerary) {
        itineraryStore.save(cid, itinerary);
    }

    public Itinerary getItinerary(String cid) {
        return itineraryStore.get(cid);
    }

    /** 透明化用：当前会话记忆条数（前端展示，也方便调试） */
    public int memorySize(String cid) {
        return chatMemory.get(cid).size();
    }
}
