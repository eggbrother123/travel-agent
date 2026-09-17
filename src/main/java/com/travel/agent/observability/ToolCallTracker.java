package com.travel.agent.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * E3 工具调用计数器：每个 @Tool 方法进来第一行调 record()。
 *
 * 两层用途：
 *  1. ThreadLocal 计数——CostTrackingAdvisor 在请求前 reset、请求后读取，
 *     得到「本次请求共几轮工具调用」（ReAct 轮次的直接证据，面试讲"5 连调"就是它数的）
 *  2. MeterRegistry 计数——travel.tool.calls{tool=xx} 按工具名累计，看哪个工具最热
 *
 * ThreadLocal 成立的前提：.call() 是同步单线程链路（工具方法在请求线程内执行）。
 * 流式 .stream() 的响应线程不一定同线程，计数可能不准——诚实标注，流式不走此指标。
 */
@Component
public class ToolCallTracker {

    private static final ThreadLocal<Integer> COUNTER = ThreadLocal.withInitial(() -> 0);

    private final MeterRegistry registry;

    public ToolCallTracker(MeterRegistry registry) {
        this.registry = registry;
    }

    /** @Tool 方法第一行调用：本请求计数 +1，全局按工具名计数 +1 */
    public void record(String toolName) {
        COUNTER.set(COUNTER.get() + 1);
        registry.counter("travel.tool.calls", "tool", toolName).increment();
    }

    /** 请求开始前调（Advisor 责任） */
    public static void reset() {
        COUNTER.remove();
    }

    /** 请求结束后读取并清空（Advisor 责任） */
    public static int getAndReset() {
        int v = COUNTER.get();
        COUNTER.remove();
        return v;
    }
}
