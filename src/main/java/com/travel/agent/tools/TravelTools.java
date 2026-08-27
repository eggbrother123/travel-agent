package com.travel.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * M2 工具层：给模型用的「手和眼」。模型只决策（选工具+填参数），方法由【我的代码】执行。
 *
 * 粒度原则（README 架构）：不做「一键生成攻略」大工具，也不拆太碎——
 * 4 个工具让模型有机会决定：查不查、查什么、按什么顺序、要不要多查几轮（ReAct 决策）。
 *
 * 否判断设计（spring-ai-hello AgentTools 实测经验）：工具返回「查不到+为什么+还能查什么」，
 * 比抛异常/返回空串好——模型能据此换关键词重查或调整策略，而不是直接失败。
 *
 * M3 预告：searchAttractions 的数据源从内存 Map 换成 RAG 向量检索（bge-m3）。
 */
@Component
public class TravelTools {

    /** 城市天气表：M2 用 Mock 数据（M5 可换和风天气真实 API，接口签名不变） */
    private static final Map<String, String> WEATHER = Map.of(
            "东京", "22°C，晴转多云，湿度 60%，微风。适合户外活动，早晚温差约 5°C。",
            "北京", "18°C，晴，空气质量良。秋季最佳游览季节，注意早晚添衣。",
            "杭州", "24°C，小雨转阴，湿度 75%。建议带伞；雨中西湖别有韵味。",
            "成都", "20°C，阴，湿度 80%。舒适但少日照，火锅季节。",
            "巴黎", "15°C，多云间晴，偶有阵雨。经典巴黎天气，备轻便雨衣。",
            "伦敦", "12°C，阴有小雨，湿度 85%。典型伦敦天气，必带伞。"
    );

    /** 汇率表：1 CNY 能兑换多少该货币（M2 固定值；M5 可换真实汇率 API） */
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

    /** 城市贴士库（M2 内存版；结构参考 spring-ai-hello 的公司知识库思路） */
    private static final Map<String, List<String>> TIPS = Map.of(
            "东京", List.of(
                    "交通：机场买 Suica（西瓜卡），地铁 JR 通用，随用随充；退卡可退押金 500 日元",
                    "现金：老店/拉面店/神社多只收现金，建议带 2~3 万日元",
                    "门票：热门博物馆和涩谷 SKY 需提前 1~2 天官网预约，周一多有闭馆",
                    "垃圾：街头垃圾桶极少，随身带小塑料袋"
            ),
            "北京", List.of(
                    "交通：地铁覆盖所有主要景点，刷手机亿通行/一卡通均可",
                    "预约：故宫、国家博物馆必须提前在官方公众号实名预约，现场无票",
                    "餐饮：烤鸭建议避开景区店，本地客多的店更实在",
                    "行程：景点间距离大，每天按同方向片区安排（东城/西城分天）"
            ),
            "杭州", List.of(
                    "交通：西湖景区周末机动车限行，地铁+共享单车最方便",
                    "预约：灵隐寺、雷峰塔旺季需提前预约门票",
                    "餐饮：楼外楼等老字号高峰排队久，可错峰或选本地小馆",
                    "天气：多变，随身折叠伞"
            ),
            "成都", List.of(
                    "交通：地铁+共享单车覆盖市区，都江堰/熊猫基地有景区直通车",
                    "熊猫：基地要早上开园就去（7:30~9:00 熊猫最活跃）",
                    "餐饮：火锅微辣也辣，肠胃弱备药；苍蝇馆子往往比网红店惊艳",
                    "行程：市区 2 天 + 周边一天（都江堰或青城山）节奏刚好"
            ),
            "巴黎", List.of(
                    "安全：地铁和景点区注意扒手，背包前背，手机别放外袋",
                    "交通：地铁十次票（carnet）比单次划算；机场 RER B 线直达市区",
                    "门票：卢浮宫、埃菲尔铁塔、凡尔赛宫都需提前官网选时段票",
                    "餐饮：餐厅午餐套餐（formule）比晚餐便宜近一半"
            ),
            "伦敦", List.of(
                    "交通：直接刷银行卡进地铁（contactless），有每日封顶；别买单程票",
                    "门票：大英博物馆、国家美术馆免费；伦敦塔、西区音乐剧要提前买",
                    "天气：一天四季，薄外套+折叠伞是标配",
                    "餐饮：下午茶选酒店或老店，需预约"
            )
    );

    /** 热门景点库：目的地 → 按主题分组（M3 换成向量检索，这份数据将成为知识库文档） */
    private static final Map<String, Map<String, List<String>>> ATTRACTIONS = Map.of(
            "东京", Map.of(
                    "美食", List.of("筑地场外市场（海鲜丼/玉子烧）", "阿美横町（平民小吃街）", "新宿思い出横丁（昭和风烤串巷）", "银座地下食品街"),
                    "博物馆", List.of("东京国立博物馆", "国立科学博物馆", "三菱一号馆美术馆", "teamLab 无界"),
                    "地标", List.of("浅草寺&雷门", "东京塔", "涩谷十字路口", "明治神宫"),
                    "户外", List.of("上野公园", "新宿御苑", "代代木公园")
            ),
            "北京", Map.of(
                    "美食", List.of("牛街（清真小吃）", "簋街（夜宵一条街）", "南锣鼓巷胡同小吃", "护国寺小吃街"),
                    "博物馆", List.of("故宫博物院", "国家博物馆", "首都博物馆"),
                    "地标", List.of("天安门广场", "天坛公园", "颐和园", "景山公园（俯瞰故宫全景）"),
                    "户外", List.of("什刹海胡同骑行", "香山（秋季红叶）", "奥林匹克森林公园")
            ),
            "杭州", Map.of(
                    "美食", List.of("楼外楼（西湖醋鱼/龙井虾仁）", "知味观（杭州小笼）", "游埠豆浆（早餐）", "龙井村茶农家菜"),
                    "博物馆", List.of("浙江省博物馆", "中国茶叶博物馆", "中国丝绸博物馆"),
                    "地标", List.of("西湖十景", "灵隐寺&飞来峰", "雷峰塔", "京杭大运河拱宸桥"),
                    "户外", List.of("西湖环湖骑行", "九溪烟树徒步", "西溪湿地")
            ),
            "成都", Map.of(
                    "美食", List.of("玉林路苍蝇馆子聚集区", "建设巷小吃街", "魁星楼街（火锅一条街）", "宽窄巷子旁的泡桐树街"),
                    "博物馆", List.of("成都博物馆", "金沙遗址博物馆", "武侯祠（三国文化）"),
                    "地标", List.of("宽窄巷子", "锦里", "杜甫草堂", "人民公园（鹤鸣茶社）"),
                    "户外", List.of("大熊猫繁育研究基地", "青城山一日游", "都江堰一日游")
            ),
            "巴黎", Map.of(
                    "美食", List.of("圣日耳曼德佩区老咖啡馆", "勒玛黑区面包房", "里沃利街天使奶油泡芙", "蒙帕纳斯可丽饼"),
                    "博物馆", List.of("卢浮宫", "奥赛博物馆", "蓬皮杜中心", "罗丹美术馆"),
                    "地标", List.of("埃菲尔铁塔", "凯旋门&香榭丽舍", "巴黎圣母院", "蒙马特高地&圣心堂"),
                    "户外", List.of("塞纳河游船", "卢森堡公园", "杜乐丽花园")
            ),
            "伦敦", Map.of(
                    "美食", List.of("博罗市场（Borough Market）", "Brick Lane 贝果", "Soho 区各国餐馆", "传统 Fish & Chips 老店"),
                    "博物馆", List.of("大英博物馆", "国家美术馆", "自然历史博物馆", "泰特现代美术馆"),
                    "地标", List.of("伦敦塔&塔桥", "大本钟&议会大厦", "威斯敏斯特教堂", "白金汉宫换岗"),
                    "户外", List.of("海德公园", "摄政公园", "泰晤士河沿岸步行")
            )
    );

    /**
     * 查目的地天气。生成攻略前调用，决定雨天方案（室内博物馆优先）还是户外路线。
     */
    @Tool(description = "查询目的地城市的天气。生成行程前调用，用于决定雨天备选方案或户外路线安排")
    public String getWeather(@ToolParam(description = "城市名称，如：东京、北京、杭州") String city) {
        System.out.println(">>> [工具] getWeather city=" + city);
        String w = WEATHER.get(city.trim());
        if (w == null) {
            // 否判断：告诉模型查不到 + 还能查哪些，让它自己决定下一步（换城市名或跳过）
            return "暂无 " + city + " 的天气数据，目前支持：" + String.join("、", WEATHER.keySet())
                    + "。如不在此列表，请基于常识给出通用建议并提醒用户出行前查看天气预报。";
        }
        return city + " 天气：" + w;
    }

    /**
     * 按主题搜景点/美食。关键词模糊匹配，模型可多次调用换不同关键词。
     */
    @Tool(description = "搜索目的地的景点、美食、体验推荐。可按主题搜索（如：美食、博物馆、地标、户外），可多次调用搜索不同主题")
    public String searchAttractions(
            @ToolParam(description = "目的地城市，如：东京、北京") String destination,
            @ToolParam(description = "主题关键词，如：美食、博物馆、地标、户外") String keyword) {
        System.out.println(">>> [工具] searchAttractions destination=" + destination + ", keyword=" + keyword);
        Map<String, List<String>> byTheme = ATTRACTIONS.get(destination.trim());
        if (byTheme == null) {
            return "暂无 " + destination + " 的景点库，目前支持：" + String.join("、", ATTRACTIONS.keySet())
                    + "。如不在此列表，请基于常识推荐并提醒用户信息可能不够新。";
        }
        // 模糊匹配主题（模型可能传"吃"而不是"美食"）
        List<String> hits = byTheme.entrySet().stream()
                .filter(e -> e.getKey().contains(keyword) || keyword.contains(e.getKey()))
                .flatMap(e -> e.getValue().stream())
                .toList();
        if (hits.isEmpty()) {
            return "目的地 " + destination + " 没有匹配「" + keyword + "」的主题，可选主题：" + String.join("、", byTheme.keySet());
        }
        return destination + "「" + keyword + "」推荐：" + String.join("；", hits);
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
     * 目的地旅行贴士。一次调用返回该城市全部贴士（交通/预约/现金/安全等）。
     */
    @Tool(description = "查询目的地的实用旅行贴士（交通卡、门票预约、现金、安全注意事项等）。生成攻略的贴士部分前调用")
    public String getTravelTips(@ToolParam(description = "目的地城市") String destination) {
        System.out.println(">>> [工具] getTravelTips destination=" + destination);
        List<String> tips = TIPS.get(destination.trim());
        if (tips == null) {
            return "暂无 " + destination + " 的贴士，目前支持：" + String.join("、", TIPS.keySet())
                    + "。请基于常识给出通用贴士。";
        }
        return destination + " 实用贴士：\n- " + String.join("\n- ", tips);
    }

    /** 给 buildPrompt 用不了但测试有用的当前日期（保持和 hello 工程一致的调试习惯） */
    @Tool(description = "获取今天的日期和星期。判断景点闭馆日（如周一闭馆）、季节推荐时使用")
    public String getCurrentDate() {
        System.out.println(">>> [工具] getCurrentDate");
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd (E)"));
    }
}
