package com.travel.agent.service;

import com.travel.agent.domain.Itinerary;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M4 会话服务：把「两种记忆」分开管——这是本里程碑的核心设计。
 *
 *  1. 对话记忆（ChatMemory）：用户和小旅【说过什么】——MessageWindowChatMemory 窗口 20 条，
 *     防多轮对话无限膨胀烧 token。请求前读历史塞 prompt，响应后自动写回（Advisor 干）。
 *  2. 行程状态（Map<cid, Itinerary>）：当前会话【最新版本的攻略对象】。
 *     对话记忆里翻旧聊天记录拼行程既慢又不可靠——直接持有结构化对象，
 *     调整时"改哪天只重生成哪天"才有抓手。
 *
 * 面试讲点：
 *  - 对话记忆 ≠ 业务状态：记忆是过程，状态是结果。两者生命周期也不同
 *    （记忆可以滑窗丢弃，状态要保留到会话结束）
 *  - 放大点：Map → Redis（多实例部署共享）；窗口 20 条 → 窗口+摘要组合（超长会话）
 *  - cid 隔离：不同会话互不串话，敏感内容不落库（内存版演示）
 */
@Service
public class ItinerarySessionService {

    /** 对话记忆：Spring AI 内存实现，窗口 20 条（10 轮问答）自动滑窗 */
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .maxMessages(20)
            .build();

    /** 行程状态：cid → 最新版本攻略（注意和 chatMemory 的 key 是同一个 cid） */
    private final Map<String, Itinerary> itineraries = new ConcurrentHashMap<>();

    public ChatMemory chatMemory() {
        return chatMemory;
    }

    public void saveItinerary(String cid, Itinerary itinerary) {
        itineraries.put(cid, itinerary);
    }

    public Itinerary getItinerary(String cid) {
        return itineraries.get(cid);
    }

    /** 透明化用：当前会话记忆条数（前端展示，也方便调试） */
    public int memorySize(String cid) {
        return chatMemory.get(cid).size();
    }
}
