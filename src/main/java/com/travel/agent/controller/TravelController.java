package com.travel.agent.controller;

import com.travel.agent.domain.Itinerary;
import com.travel.agent.service.ItinerarySessionService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 攻略生成接口（M1~M4 演进）：
 *  /plan          纯文本 Markdown（M0）
 *  /plan/struct   Itinerary record 树 JSON（M1）——M4 起存入会话状态
 *  /plan/stream   SSE 流式打字机（M1）
 *  /plan/adjust   多轮调整（M4）：cid 会话 + 自然语言请求 → 基于当前行程增量修改
 *
 * M4 关键：对话记忆（ChatMemory）和行程状态（Itinerary 对象）是两回事——
 * 记忆管"说过什么"（ Advisor 自动读写），状态管"最新攻略长什么样"（这里显式存取）。
 */
@RestController
public class TravelController {

    private final ChatClient travelChatClient;
    private final ChatClient memoryChatClient;   // 带记忆 Advisor 的实例：多轮调整专用
    private final ItinerarySessionService sessionService;

    public TravelController(ChatClient travelChatClient,
                             ItinerarySessionService sessionService) {
        this.travelChatClient = travelChatClient;
        this.sessionService = sessionService;
        // 带记忆的 ChatClient：复用全局默认（系统提示词+工具），叠加记忆 Advisor
        this.memoryChatClient = travelChatClient.mutate()
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(sessionService.chatMemory()).build())
                .build();
    }

    /** M0 纯文本版（Markdown）。 */
    @GetMapping("/plan")
    public String plan(@RequestParam String destination,
                       @RequestParam(defaultValue = "3") int days,
                       @RequestParam(defaultValue = "5000") double budget,
                       @RequestParam(defaultValue = "不限") String preferences) {
        return travelChatClient.prompt()
                .user(buildPrompt(destination, days, budget, preferences))
                .call()
                .content();
    }

    /**
     * M1 结构化输出（M4 增强：cid 参数 + 行程存入会话状态，供后续 /plan/adjust 增量修改）。
     * cid 不传则不记忆（单次使用场景不受影响）。
     */
    @GetMapping("/plan/struct")
    public Itinerary planStruct(@RequestParam String destination,
                                @RequestParam(defaultValue = "3") int days,
                                @RequestParam(defaultValue = "5000") double budget,
                                @RequestParam(defaultValue = "不限") String preferences,
                                @RequestParam(required = false) String cid) {
        // 防御性结构化输出：工具调用+entity 组合下 DeepSeek 偶尔先输出思考再给 JSON（实测两种接口都踩过），
        // 统一走 BeanOutputConverter + extractJson 剥离思考文字
        BeanOutputConverter<Itinerary> converter = new BeanOutputConverter<>(Itinerary.class);
        String raw = travelChatClient.prompt()
                .user(buildPrompt(destination, days, budget, preferences)
                        + "\n\n请以结构化的行程格式输出，" + converter.getFormat())
                .call()
                .content();
        Itinerary it = converter.convert(extractJson(raw));

        if (cid != null && !cid.isBlank()) {
            sessionService.saveItinerary(cid, it);
            // 初始需求也写进对话记忆，后续调整才有上下文（"上次说的博物馆"有指代）
            memoryChatClient.prompt()
                    .user("（用户刚生成了" + destination + days + "日攻略，偏好：" + preferences + "，预算" + budget + "元）")
                    .call()
                    .content();
        }
        return it;
    }

    /** M1 流式输出。 */
    @GetMapping(value = "/plan/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public Flux<String> planStream(@RequestParam String destination,
                                   @RequestParam(defaultValue = "3") int days,
                                   @RequestParam(defaultValue = "5000") double budget,
                                   @RequestParam(defaultValue = "不限") String preferences) {
        return travelChatClient.prompt()
                .user(buildPrompt(destination, days, budget, preferences))
                .stream()
                .content();
    }

    /**
     * M4 多轮调整：cid + 自然语言请求（"第二天太赶了，换个轻松的"）。
     *
     * 增量设计（省 token 的关键）：
     *  - 把【当前行程 JSON】注入 prompt，模型不需要从对话历史里"回忆"行程
     *  - 指示模型只输出完整的新 Itinerary（含未变化的天）——反正 record 树不大，
     *    但上下文里不用塞 20 条历史+长攻略原文，已经是数量级的节省
     *  - 工具仍然可用：调整涉及新查天气/景点时模型自己会调
     */
    @PostMapping("/plan/adjust")
    public Map<String, Object> adjust(@RequestBody AdjustRequest req) {
        Itinerary current = sessionService.getItinerary(req.cid());
        if (current == null) {
            return Map.of("error", "会话 " + req.cid() + " 没有已生成的行程，请先调 /plan/struct?cid=... 生成");
        }

        // 不用 .entity()：调整场景 prompt 里注入了大段行程 JSON，DeepSeek 偶尔会在 JSON 前
        // 先输出思考文字（"Based on the request..."），entity 直接解析就炸（实测踩坑）。
        // 改用 BeanOutputConverter（entity 的底层机制）+ 防御性 JSON 提取：
        // 截取第一个 { 到最后一个 }，思考文字/代码块围栏都被剥掉。
        BeanOutputConverter<Itinerary> converter = new BeanOutputConverter<>(Itinerary.class);
        String raw = memoryChatClient.prompt()
                .user("""
                        用户对当前行程提出了调整请求：%s

                        当前行程（JSON）：
                        %s

                        请根据调整请求修改行程，输出修改后的【完整】行程（未提及的天保持原样）。
                        用户没明确要改的地方不要动。
                        %s
                        """.formatted(req.request(), toJson(current), converter.getFormat()))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, req.cid()))
                .call()
                .content();

        Itinerary updated;
        try {
            updated = converter.convert(extractJson(raw));
        } catch (Exception e) {
            return Map.of("error", "行程解析失败，请换个说法再试（原始输出片段："
                    + raw.substring(0, Math.min(120, raw.length())) + "…）");
        }
        // 防御：模型偶尔会把未改动天的 estimatedCost 抹成 0——从当前行程回填丢失字段
        updated = backfillMissing(current, updated);

        sessionService.saveItinerary(req.cid(), updated);
        return Map.of(
                "cid", req.cid(),
                "itinerary", updated,
                "记忆条数", sessionService.memorySize(req.cid())
        );
    }

    /** 防御性 JSON 提取：剥掉模型的前置思考/后置解释/```json 围栏，只留 JSON 本体 */
    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("输出中找不到 JSON：" + raw.substring(0, Math.min(80, raw.length())));
        }
        return raw.substring(start, end + 1);
    }

    /**
     * 回填防御：模型输出完整新行程时，偶尔会把"没让你动"的天弄丢字段（实测 estimatedCost 抹 0）。
     * 策略：day 序号相同的，若新值是 0/null 而旧值有数，回填旧值——未变化的字段以旧为准。
     * record 不可变 → 用流式重建 dayPlans 列表，最后整体换一个新的 Itinerary 返回。
     */
    private Itinerary backfillMissing(Itinerary oldIt, Itinerary newIt) {
        if (oldIt == null || newIt == null || oldIt.dayPlans() == null || newIt.dayPlans() == null) {
            return newIt;
        }
        var repaired = newIt.dayPlans().stream().map(nd -> {
            for (Itinerary.DayPlan od : oldIt.dayPlans()) {
                if (od != null && od.day() == nd.day()) {
                    boolean costLost = nd.estimatedCost() == 0 && od.estimatedCost() != 0;
                    boolean mealLost = (nd.mealSuggestion() == null || nd.mealSuggestion().isBlank())
                            && od.mealSuggestion() != null;
                    boolean weatherLost = (nd.weather() == null || nd.weather().isBlank())
                            && od.weather() != null;
                    if (costLost || mealLost || weatherLost) {
                        return new Itinerary.DayPlan(nd.day(), nd.theme(),
                                weatherLost ? od.weather() : nd.weather(), nd.spots(),
                                mealLost ? od.mealSuggestion() : nd.mealSuggestion(),
                                costLost ? od.estimatedCost() : nd.estimatedCost());
                    }
                    break;
                }
            }
            return nd;
        }).toList();
        return new Itinerary(newIt.destination(), newIt.days(), newIt.totalBudget(), repaired, newIt.tips());
    }

    /** 调整请求体 */
    public record AdjustRequest(String cid, String request) {}

    /**
     * 行程 → JSON（给 prompt 用）。
     * 踩坑记录：第一版手写拼接漏了 estimatedCost/mealSuggestion/tips 字段——模型看不到
     * 当前费用，调整后全部填 0（前端 diff 又被 0≠550 连累成"全都调整了"）。
     * 教训：给模型的"当前状态"必须完整，缺字段 = 模型编造默认值。改用 Jackson 全量序列化。
     */
    private String toJson(Itinerary it) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(it);
        } catch (Exception e) {
            throw new IllegalStateException("行程序列化失败", e);
        }
    }

    /** 三种输出形态共用的需求 → prompt 构造 */
    private String buildPrompt(String destination, int days, double budget, String preferences) {
        return """
                请为我制定一份旅行攻略：
                - 目的地：%s
                - 天数：%d 天
                - 预算：约 %.0f 元
                - 偏好：%s

                按天分段安排（上午/下午/晚上）。每天的第一行先写「当日天气」：
                调用 getWeather 拿逐日预报，把该日的天气（温度区间/降雨概率）和对应注意事项
                （带伞/防晒/穿衣/是否宜户外）写在标题下；预报覆盖不到的行程日按季节常识写。
                每天结尾给出当日花费估算，最后给整体花费合计和实用贴士。
                """.formatted(destination, days, budget, preferences);
    }
}
