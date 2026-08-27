# travel-agent · 旅行攻略智能体

> Spring AI 实战主项目：输入「目的地 + 天数 + 预算 + 偏好」，
> Agent 自主查资料（天气/景点/汇率）→ 生成结构化每日行程 → 支持多轮调整（"第二天太赶了"）。
>
> 技术栈：Spring Boot 3.4.3 + Spring AI 1.0.0 + DeepSeek（chat）+ Ollama bge-m3（embedding）

## 架构（四层，对应 Day9 答题框架）

```
用户 → TravelController（SSE 流式）
   ├─ 对话层：ChatClient + 系统提示词集中装配 + ChatMemory（M4，cid 隔离）
   ├─ 知识层：RAG —— 城市景点/美食知识库（M3：bge-m3 + SimpleVectorStore，放大点=Milvus+增量索引）
   ├─ 工具层：@Tool —— 查天气 / 查景点 / 汇率 / 旅行贴士（M2，模型自主编排，ReAct）
   └─ 工程层：token·延迟打点 + 预算校验 + 前端渲染（M5）
```

## 里程碑

- [x] **M0 骨架**：独立工程 + 依赖复用 + `/plan` 单轮纯文本跑通
- [x] **M1 结构化输出**：`.entity(Itinerary.class)` record 树 + `/plan/stream` 流式 ✅ 2026-08-27
  - `/plan`（Markdown）/ `/plan/struct`（Itinerary JSON）/ `/plan/stream`（SSE 打字机）三形态共用一个 buildPrompt
  - 前端双模式：流式文档（EventSource 边收边渲染 + 打字机光标 + 首字延迟显示）/ 行程卡片（Day 卡片 + 点位时间线 + 贴士）
  - 实测：杭州 2 日 struct 8.2s 全字段正确（dayPlans 数=days、每天 5 点位、tips 6 条）；北京 1 日 stream 16.7s / 1661 个 SSE 事件
- [x] **M2 工具层**：5 个 `@Tool`，模型自主决策调用 ✅ 2026-08-27
  - getWeather / searchAttractions（主题模糊匹配，可多次调用）/ exchangeCurrency（CNY 桥接换算）/ getTravelTips / getCurrentDate（判闭馆日）
  - 挂载方式：TravelChatConfig `.defaultTools()`（全局默认，Controller 零改动）
  - 否判断兜底：工具返回「查不到+支持列表+建议」，模型据此降级为常识推荐并标注"出发前核实"
  - 实测：杭州 2 日 → 模型自主编排 5 次调用（日期→天气→贴士→美食→地标），输出引用工具数据（雨天方案/限行贴士/楼外楼）
  - 实测：伊斯坦布尔（库外城市）→ 天气/景点/贴士全走否判断，模型改用常识 + 主动换算 CNY→USD（TRY 不在表里，模型自己找了替代货币）
- [ ] **M3 知识层**：自建 2~3 个城市知识库 → 切块向量化 → 检索变成工具（Agentic RAG + 溯源）
- [ ] **M4 多轮调整**：ChatMemory + 行程状态持久化（改哪天只重生成哪天，省 token）
- [ ] **M5 体验升级**（可选）：前端页面 + 高德/和风真实 API + 导出 Markdown
- [ ] **M6 收尾**：架构图 + 录屏 + 踩坑记录 + 面试素材映射表

## 快速开始

前置：JDK 17+ / Maven / 环境变量 `DEEPSEEK_API_KEY`（M3 起还需本地 Ollama + bge-m3 模型）

```bash
mvn spring-boot:run
# 试：
# http://localhost:8081/plan?destination=东京&days=3&budget=8000&preferences=美食,博物馆
```

> 端口 8081（8080 留给 spring-ai-hello，两个项目可同时跑）

## 目录结构

```
src/main/java/com/travel/agent/
├── TravelAgentApplication.java   # 启动类
├── config/TravelChatConfig.java  # ChatClient 集中装配（系统提示词；M2 加工具、M4 加记忆都改这里）
├── controller/TravelController.java  # REST 接口（/plan）
├── domain/Itinerary.java         # 攻略 record 树（M1 结构化输出的目标格式）
└── tools/TravelTools.java        # M2 工具集占位
```

## 面试素材映射（M6 时回填实测数据）

| 素材 | 来源 |
|---|---|
| embedding 中文选型实测 | bge-m3 vs nomic（spring-ai-hello Day4） |
| Agentic RAG vs 固定管道 | M3：检索变成工具，模型自主决定查不查 |
| 多轮状态管理 | M4：对话记忆 ≠ 行程对象，增量重生成省 token |
| 溯源 | Spot.reason + 知识库来源标注 |
