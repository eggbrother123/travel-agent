package com.travel.agent.observability;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * E3 成本打点 Advisor：包在整个请求最外层，一次请求产出一份「账单」。
 *
 * 捕获指标（一次生成请求的完整画像）：
 *   墙钟延迟 | token 输入/输出 | 估算成本(¥) | 工具调用轮数
 *
 * 三条出口：
 *   1. 日志：>>> [成本] 一行账单（开发/面试演示直接看）
 *   2. Micrometer：travel.tokens / travel.cost.cny / travel.request.duration / travel.tool.calls
 *      → /actuator/metrics 与 /actuator/prometheus（Grafana 直接接入）
 *   3. ThreadLocal 快照：Controller 请求后取走，塞进响应头（http response header）/响应体给前端展示
 *
 * 边界与诚实声明：
 *   - ChatResponse 的 Usage 是【最后一轮】模型调用的用量——中间的工具轮次用量
 *     在框架内部循环里，Advisor 拿不到全量。全量 token 依赖 Spring AI 原生
 *     per-call observation（/actuator/metrics 里每次模型 HTTP 调用都有记录）
 *   - 成本按 yml 配置的单价估算（travel.cost.*），官方调价只改配置
 *   - 只实现 CallAdvisor：流式接口不走这里（StreamAdvisor + usage 聚合是 TODO）
 */
@Component
public class CostTrackingAdvisor implements CallAdvisor {

    private final MeterRegistry registry;
    private final double inputPricePerMillion;    // ¥ / 百万 token
    private final double outputPricePerMillion;

    /** 请求级快照：Controller 读取后随响应透出（record 序列化友好） */
    public record CostSnapshot(long latencyMs, long promptTokens, long completionTokens,
                               double costCny, int toolCalls) {

        public String brief() {
            return latencyMs + "ms · 输入" + promptTokens + "/输出" + completionTokens
                    + " tok · 工具" + toolCalls + "轮 · ≈¥" + String.format("%.4f", costCny);

        }

        /**
         * HTTP 响应头专用格式：必须纯 ASCII。
         * 踩坑：Tomcat 对含非 ASCII 字符（中文/·/¥）的 header 值会【静默丢弃整个 header】
         * （实测：同样位置 setHeader，ASCII 值出现、中文值消失，无任何报错日志）。
         * HTTP 头本质是 ISO-8859-1 字节流，中文内容想进 header 必须 RFC 5987 编码或干脆用 ASCII。
         */
        public String briefAscii() {
            return latencyMs + "ms | in:" + promptTokens + " out:" + completionTokens
                    + " tok | tools:" + toolCalls + " | ~CNY:" + String.format("%.4f", costCny);
        }
    }

    private static final ThreadLocal<CostSnapshot> LAST = new ThreadLocal<>();

    public CostTrackingAdvisor(MeterRegistry registry,
                               @Value("${travel.cost.input-per-million:2.0}") double inputPricePerMillion,
                               @Value("${travel.cost.output-per-million:8.0}") double outputPricePerMillion) {
        this.registry = registry;
        this.inputPricePerMillion = inputPricePerMillion;
        this.outputPricePerMillion = outputPricePerMillion;
    }

    @Override
    public String getName() {
        return "CostTrackingAdvisor";
    }

    /** 最外层拦截（数值越小越靠前），确保覆盖记忆读写+工具执行+全部模型轮次的墙钟时间 */
    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long t0 = System.nanoTime();
        ToolCallTracker.reset();

        ChatClientResponse response = chain.nextCall(request);

        long latencyMs = (System.nanoTime() - t0) / 1_000_000;
        int toolCalls = ToolCallTracker.getAndReset();

        long promptTokens = 0, completionTokens = 0;
        ChatResponse chatResponse = response != null ? response.chatResponse() : null;
        if (chatResponse != null && chatResponse.getMetadata() != null) {
            Usage usage = chatResponse.getMetadata().getUsage();
            if (usage != null) {
                promptTokens = nvl(usage.getPromptTokens());
                completionTokens = nvl(usage.getCompletionTokens());
            }
        }
        double cost = promptTokens / 1_000_000.0 * inputPricePerMillion
                    + completionTokens / 1_000_000.0 * outputPricePerMillion;

        // 1) 日志账单
        System.out.printf(">>> [成本] %dms | 输入 %d tok | 输出 %d tok | 工具 %d 轮 | ≈¥%.4f%n",
                latencyMs, promptTokens, completionTokens, toolCalls, cost);

        // 2) Micrometer 指标（Prometheus 可抓）
        registry.counter("travel.tokens", "type", "input").increment(promptTokens);
        registry.counter("travel.tokens", "type", "output").increment(completionTokens);
        DistributionSummary.builder("travel.cost.cny")
                .baseUnit("cny").register(registry).record(cost);
        Timer.builder("travel.request.duration")
                .register(registry).record(latencyMs, TimeUnit.MILLISECONDS);

        // 3) 请求级快照（Controller 透出用）
        LAST.set(new CostSnapshot(latencyMs, promptTokens, completionTokens, cost, toolCalls));

        return response;
    }

    /** Controller 调：取走本请求的账单快照（取完即清，防串请求） */
    public static CostSnapshot takeSnapshot() {
        CostSnapshot s = LAST.get();
        LAST.remove();
        return s;
    }

    private static long nvl(Number n) {
        return n == null ? 0L : n.longValue();
    }
}
