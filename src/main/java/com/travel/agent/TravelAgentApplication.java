package com.travel.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 旅行攻略智能体 · 主启动类。
 *
 * 项目主线（M0~M6 里程碑见 README.md）：
 *  M0 骨架 → M1 结构化输出+流式 → M2 工具调用 → M3 Agentic RAG → M4 多轮调整 → M5 前端/真实API → M6 收尾
 */
@SpringBootApplication
public class TravelAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(TravelAgentApplication.class, args);
    }
}
