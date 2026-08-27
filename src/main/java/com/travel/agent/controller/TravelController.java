package com.travel.agent.controller;

import com.travel.agent.domain.Itinerary;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 攻略生成接口，三种输出形态（M1）：
 *  /plan          纯文本 Markdown —— 人看（浏览器/前端 marked 渲染）
 *  /plan/struct   Itinerary record 树 JSON —— 机器看（前端行程卡片/存库/M4 状态管理的基础）
 *  /plan/stream   SSE 流式 —— 打字机，降感知延迟
 *
 * 三者共用同一个 buildPrompt()，保证不同形态下的「输入→攻略」语义一致。
 */
@RestController
public class TravelController {

    private final ChatClient travelChatClient;

    public TravelController(ChatClient travelChatClient) {
        this.travelChatClient = travelChatClient;
    }

    /**
     * M0 纯文本版（Markdown）。浏览器裸看是一坨原始符号，配 static/index.html 渲染。
     * 试：http://localhost:8081/plan?destination=东京&days=3&budget=8000&preferences=美食,博物馆
     */
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
     * M1 结构化输出：.entity(Itinerary.class) 自动生成 JSON Schema 注入 prompt，
     * 模型按 schema 返回 JSON，框架反序列化成 record 树——AI 输出从此可被程序直接消费。
     * 试：http://localhost:8081/plan/struct?destination=东京&days=2
     * 观察返回的是 {"destination":"东京","days":2,...,"dayPlans":[...]} 而不是一段话。
     */
    @GetMapping("/plan/struct")
    public Itinerary planStruct(@RequestParam String destination,
                                @RequestParam(defaultValue = "3") int days,
                                @RequestParam(defaultValue = "5000") double budget,
                                @RequestParam(defaultValue = "不限") String preferences) {
        return travelChatClient.prompt()
                .user(buildPrompt(destination, days, budget, preferences)
                        + "\n\n请以结构化的行程格式输出。")
                .call()
                .entity(Itinerary.class);
    }

    /**
     * M1 流式输出：.call() 换 .stream()，返回 Flux<String>（每个元素一小段 token），
     * produces=text/event-stream 让浏览器以 SSE 接收——几乎立刻看到第一个字。
     * 对比 /plan 要等全部生成完才一次性返回（3 天攻略约 30~60s，感知差距巨大）。
     * 试：http://localhost:8081/plan/stream?destination=东京&days=1
     */
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

    /** 三种输出形态共用的需求 → prompt 构造 */
    private String buildPrompt(String destination, int days, double budget, String preferences) {
        return """
                请为我制定一份旅行攻略：
                - 目的地：%s
                - 天数：%d 天
                - 预算：约 %.0f 元
                - 偏好：%s

                按天分段安排（上午/下午/晚上），每天结尾给出当日花费估算，最后给整体花费合计和实用贴士。
                """.formatted(destination, days, budget, preferences);
    }
}
