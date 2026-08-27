package com.travel.agent.tools;

import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * M2→M3 工具层进化：数据源从「内存 Map 硬编码」升级为「RAG 向量检索」。
 *
 * 这就是 Agentic RAG（Day5 概念在主项目落地）：
 *  - M2 固定管道思路：查表 → 返回（代码写死查什么）
 *  - M3 检索变工具：模型自主决定查不查、查什么关键词、查几轮、怎么组合结果
 *
 * 面试讲点：
 *  1. 溯源：检索结果带 metadata（city/source），返回给模型时标注「信息来自 XX 知识库」
 *  2. 相似度阈值 0.0（先广撒网靠 topK 截断）——bge-m3 中文召回实测好，
 *     但旅游口语化查询相似度分数天然偏低，卡阈值容易全军覆没（Day4 实测经验）
 *  3. 兜底：检索为空 → 告诉模型没有资料，让它降级为常识推荐并标注未核实
 */
@Component
public class TravelTools {

    private final VectorStore travelVectorStore;

    /** wttr.in 只认英文城市名（中文实测 location not found）——知识库 6 城映射 + 常见城市兜底 */
    private static final Map<String, String> CITY_TO_EN = Map.ofEntries(
            Map.entry("东京", "Tokyo"),
            Map.entry("北京", "Beijing"),
            Map.entry("杭州", "Hangzhou"),
            Map.entry("成都", "Chengdu"),
            Map.entry("巴黎", "Paris"),
            Map.entry("伦敦", "London"),
            Map.entry("上海", "Shanghai"),
            Map.entry("西安", "Xian"),
            Map.entry("重庆", "Chongqing"),
            Map.entry("广州", "Guangzhou"),
            Map.entry("深圳", "Shenzhen"),
            Map.entry("香港", "Hong Kong"),
            Map.entry("首尔", "Seoul"),
            Map.entry("新加坡", "Singapore"),
            Map.entry("曼谷", "Bangkok"),
            Map.entry("大阪", "Osaka"),
            Map.entry("京都", "Kyoto"),
            Map.entry("纽约", "New York"),
            Map.entry("旧金山", "San Francisco"),
            Map.entry("罗马", "Rome"),
            Map.entry("伊斯坦布尔", "Istanbul"),
            Map.entry("迪拜", "Dubai"),
            Map.entry("悉尼", "Sydney"),
            Map.entry("莫斯科", "Moscow")
    );

    /** 汇率表：1 CNY 能兑换多少该货币（小而稳定，保留内存版；M5 可换真实汇率 API） */
    private static final Map<String, Double> RATES_FROM_CNY = Map.of(
            "CNY", 1.0,
            "JPY", 20.5,
            "USD", 0.14,
            "EUR", 0.13,
            "GBP", 0.11,
            "KRW", 192.0,
            "THB", 4.8,
            "HKD", 1.09
    );

    public TravelTools(VectorStore travelVectorStore) {
        this.travelVectorStore = travelVectorStore;
    }

    /**
     * 查目的地天气（M5：真实数据 + 逐日预报）。wttr.in 免费无 key。
     * 返回当前实况 + 未来 3 天逐日预报（温度区间/降雨概率/UV）——模型把每天的
     * 天气和注意事项写进对应 Day 的标题下（"Day1 ☔ 降雨概率 77%，安排室内博物馆"）。
     * 失败兜底：否判断文案——天气是「增强信息」不是「关键路径」，宁可告知查不到，
     * 不用编造的假数据糊弄用户。
     */
    @Tool(description = "查询目的地城市的实时天气和未来3天逐日天气预报（含每日温度区间、降雨概率、紫外线指数）。生成行程时调用，把每天的天气写进对应日期的行程安排和注意事项里")
    public String getWeather(@ToolParam(description = "城市名称，如：东京、北京、杭州") String city) {
        System.out.println(">>> [工具] getWeather city=" + city);
        String name = city.trim();
        String en = CITY_TO_EN.get(name);

        // 真实 API（英文城市名才认，映射表没有的直接试原名——部分英文城市模型会传英文）
        if (en != null || name.matches("[a-zA-Z ]+")) {
            try {
                String url = "https://wttr.in/" + (en != null ? en : name.replace(" ", "+"))
                        + "?format=j1&lang=zh";
                String json = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(5))
                        .build()
                        .send(java.net.http.HttpRequest.newBuilder()
                                        .uri(java.net.URI.create(url))
                                        .timeout(java.time.Duration.ofSeconds(8))
                                        .GET().build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString())
                        .body();
                var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);

                StringBuilder sb = new StringBuilder();
                // 当前实况
                var cur = root.path("current_condition").path(0);
                if (!cur.isMissingNode()) {
                    sb.append(String.format("%s 当前实况：%s，%s°C（体感 %s°C），湿度 %s%%\n",
                            name,
                            cur.path("lang_zh").path(0).path("value").asText(
                                    cur.path("weatherDesc").path(0).path("value").asText("")),
                            cur.path("temp_C").asText(), cur.path("FeelsLikeC").asText(),
                            cur.path("humidity").asText()));
                }
                // 未来 3 天逐日预报（模型按日期对齐到行程 Day，近的在前）
                var days = root.path("weather");
                int idx = 0;
                for (var d : days) {
                    if (idx++ >= 3) break;
                    int maxRain = 0;
                    for (var h : d.path("hourly")) {
                        maxRain = Math.max(maxRain, h.path("chanceofrain").asInt(0));
                    }
                    String noonDesc = d.path("hourly").path(4).path("weatherDesc").path(0)
                            .path("value").asText("");
                    sb.append(String.format("第%d天（%s）：%s，%s~%s°C，最大降雨概率 %d%%，UV 指数 %s\n",
                            idx, d.path("date").asText(), noonDesc,
                            d.path("mintempC").asText(), d.path("maxtempC").asText(),
                            maxRain, d.path("uvIndex").asText()));
                }
                if (sb.length() > 0) {
                    return "【wttr.in 天气预报】\n" + sb + "（说明：预报最多 3 天，更后面的行程日请按季节常识给建议）";
                }
            } catch (Exception e) {
                System.out.println(">>> [工具] wttr.in 调用失败：" + e.getMessage());
            }
        }

        // 否判断兜底（API 失败/城市不识别）——宁可告知查不到，不给假数据
        return "暂无 " + name + " 的实时天气（外部天气服务不可用或该城市不在支持列表）。"
                + "请基于常识给出通用建议并提醒用户出行前查看天气预报。";
    }

    /**
     * M3 核心改造：按主题检索目的地知识。数据源 = bge-m3 向量检索（6 城市知识库），
     * 替换 M2 的内存 Map——新增城市只需丢 md 文件进 knowledge/，不用改这行代码。
     */
    @Tool(description = "搜索目的地的景点、美食、交通、费用等旅行知识。可按主题搜索（如：美食、博物馆、户外、交通、注意事项），可多次调用搜索不同主题")
    public String searchAttractions(
            @ToolParam(description = "目的地城市，如：东京、北京") String destination,
            @ToolParam(description = "主题关键词，如：美食、博物馆、地标、户外、交通") String keyword) {
        System.out.println(">>> [工具] searchAttractions(RAG) destination=" + destination + ", keyword=" + keyword);

        // 查询 = 城市 + 主题：主题词负责语义匹配
        // 过滤 = metadata city 字段：把检索范围锁在该城市（不然 topK 全库搜会混进别的城市，
        //        实测搜"成都 美食"混进 2 块北京内容——跨城市污染）。同一机制即 Day9 的「知识库权限隔离」
        List<Document> hits = travelVectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(destination + " " + keyword + " 景点美食推荐交通费用注意事项")
                        .topK(4)
                        .similarityThreshold(0.0)
                        .filterExpression("city == '" + destination.trim() + "'")
                        .build());

        if (hits.isEmpty()) {
            return "知识库中没有找到 " + destination + " 的「" + keyword + "」相关内容。"
                    + "请基于常识推荐，并明确告知用户这部分信息未经知识库核实，建议出发前查证。";
        }

        // 拼chunks + 溯源标注（metadata 里的 city/source 是建库时打进去的）
        String body = hits.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));
        String sources = hits.stream()
                .map(d -> (String) d.getMetadata().getOrDefault("city", "未知来源"))
                .distinct()
                .collect(Collectors.joining("、"));
        System.out.println(">>> [工具] 检索命中 " + hits.size() + " 块，来源：" + sources);
        return "【以下信息来自旅行知识库（" + sources + "），可信度高】\n" + body;
    }

    /**
     * 汇率换算。以 CNY 为桥接货币（换算两次），支持任意已录入货币对。
     */
    @Tool(description = "货币汇率换算。用户给人民币预算时，用于换算成目的地当地货币金额")
    public String exchangeCurrency(
            @ToolParam(description = "源货币代码，如 CNY、JPY、EUR") String from,
            @ToolParam(description = "目标货币代码") String to,
            @ToolParam(description = "金额") double amount) {
        System.out.println(">>> [工具] exchangeCurrency " + amount + " " + from + " -> " + to);
        Double fromRate = RATES_FROM_CNY.get(from.toUpperCase());
        Double toRate = RATES_FROM_CNY.get(to.toUpperCase());
        if (fromRate == null || toRate == null) {
            return "暂不支持该货币对，目前支持：" + String.join("、", RATES_FROM_CNY.keySet());
        }
        double cny = amount / fromRate;              // 先换回人民币
        double result = cny * toRate;                // 再换成目标货币
        return String.format("%.2f %s ≈ %.2f %s（参考汇率，实际以银行/兑换点为准）", amount, from, result, to);
    }

    /**
     * 目的地贴士：M3 换 RAG 检索（贴士写在各城市知识库的「交通/注意事项」章节）。
     */
    @Tool(description = "查询目的地的实用旅行贴士（交通卡、门票预约、现金、安全注意事项等）。生成攻略的贴士部分前调用")
    public String getTravelTips(@ToolParam(description = "目的地城市") String destination) {
        System.out.println(">>> [工具] getTravelTips(RAG) destination=" + destination);
        List<Document> hits = travelVectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(destination + " 交通 门票预约 现金 安全 注意事项")
                        .topK(3)
                        .similarityThreshold(0.0)
                        .filterExpression("city == '" + destination.trim() + "'")
                        .build());

        if (hits.isEmpty()) {
            return "知识库中没有 " + destination + " 的贴士。请基于常识给出通用贴士并标注未经核实。";
        }
        String body = hits.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));
        return "【以下贴士来自旅行知识库，可信度高】\n" + body;
    }

    /** 判断闭馆日、季节推荐时使用 */
    @Tool(description = "获取今天的日期和星期。判断景点闭馆日（如周一闭馆）、季节推荐时使用")
    public String getCurrentDate() {
        System.out.println(">>> [工具] getCurrentDate");
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd (E)"));
    }
}
