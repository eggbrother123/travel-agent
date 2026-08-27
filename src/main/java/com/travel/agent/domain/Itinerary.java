package com.travel.agent.domain;

import java.util.List;

/**
 * 攻略的领域模型 —— M1 结构化输出的目标格式。
 *
 * 设计要点：
 *  - record 树：.entity(Itinerary.class) 会自动生成 JSON Schema 注入 prompt，
 *    模型照 schema 返回 JSON，框架反序列化成这棵树——这是「AI 输出对接业务」的关键一步。
 *  - Spot.reason 保留「为什么推荐」字段：M3 接 RAG 后可顺带标注信息来源，面试讲溯源的抓手。
 *  - M0 只是占位定义，/plan 还没用它；M1 换上。
 */
public record Itinerary(
        String destination,
        int days,
        double totalBudget,
        List<DayPlan> dayPlans,
        List<String> tips) {

    /** 单日安排：主题 + 点位列表 + 用餐建议 + 当日花费估算 */
    public record DayPlan(
            int day,
            String theme,
            List<Spot> spots,
            String mealSuggestion,
            double estimatedCost) {}

    /** 单个点位：推荐理由 + 到下一站的交通方式 */
    public record Spot(
            String name,
            String type,
            String reason,
            String transportToNext) {}
}
