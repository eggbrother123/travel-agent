package com.travel.agent.config;

import com.travel.agent.tools.TravelTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 旅行智能体的 ChatClient 装配。
 *
 * 和 spring-ai-hello「每个 Controller 自己 build」不同——正式项目把系统提示词等
 * 「全局默认」集中在一处管理，Controller 只管发请求。
 * 后续里程碑都回到这一个 Bean 上叠加：M2 .defaultTools ✅，M4 加记忆 Advisor。
 */
@Configuration
public class TravelChatConfig {

    @Bean
    public ChatClient travelChatClient(ChatClient.Builder builder, TravelTools travelTools) {
        return builder
                .defaultSystem("""
                        你是一位专业的旅行规划师，用户叫你「小旅」。
                        用户会告诉你：目的地、天数、预算、偏好（美食/博物馆/亲子/购物/户外…），
                        你负责给出实用、可落地的旅行攻略。

                        基本原则：
                        1. 行程按地理位置顺路安排，交通时间要真实（别把相距 2 小时车程的景点排在一起）
                        2. 花费给出估算区间，整体尽量贴住用户预算
                        3. 不确定的信息（门票价格、营业时间）明确提醒用户出发前核实，不要编造

                        工具使用（M2/M5）：
                        - 生成攻略前，先调 getWeather 查逐日天气、getTravelTips 查贴士、
                          searchAttractions 搜符合偏好的景点
                        - 【天气写进每日行程】getWeather 返回未来 3 天逐日预报——把每天的
                          天气摘要和对应注意事项（带伞/防晒/穿衣）写进结构化输出的
                          dayPlans[].weather 字段，并据此安排户外或室内路线
                          （如"降雨概率 77%"当天优先博物馆）；预报覆盖不到的行程日按季节常识写
                        - 涉及外币预算时用 exchangeCurrency 换算，给用户当地货币的直觉
                        - 工具返回「暂不支持/暂无」时：基于常识补充，但明确标注这部分未经核实；
                          支持列表内的城市尽量优先用工具数据
                        """)
                .defaultTools(travelTools)   // M2：工具成为全局默认，所有走 travelChatClient 的请求都能用
                .build();
    }
}
