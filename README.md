# travel-agent · 旅行攻略智能体

> Spring AI 实战主项目：输入「目的地 + 天数 + 预算 + 偏好」，
> Agent 自主查资料（天气/景点/汇率）→ 生成结构化每日行程（含逐日天气）→ 支持多轮调整（"第二天太赶了"）→ 导出 Markdown。
>
> 技术栈：Spring Boot 3.4.3 + Spring AI 1.0.0 + DeepSeek（chat）+ Ollama bge-m3（embedding）+ wttr.in（真实天气）

## 架构（四层，对应 Day9 答题框架）

```
用户（浏览器 index.html：流式文档 / 行程卡片 双模式）
   │ HTTP / SSE
   ▼
┌─ TravelController ─────────────────────────────────────────────┐
│  /plan         Markdown 一次性返回（M0）                        │
│  /plan/struct  Itinerary JSON，结构化输出（M1）                  │
│  /plan/stream  SSE 打字机（M1）                                  │
│  /plan/adjust  多轮调整（M4，POST cid + 自然语言）               │
│  /plan/export  行程卡片导出 md │ /plan/export/md 流式原文导出（M5）│
└────────────────────────────────────────────────────────────────┘
   │
   ├─ 对话层 ─ ChatClient（TravelChatConfig 集中装配：系统提示词 + .defaultTools）
   │     └ ItinerarySessionService：ChatMemory（窗口 20 条，管"说过什么"）
   │                              + Map<cid,Itinerary>（管"最新攻略"）—— 记忆与状态分离
   │
   ├─ 工具层 ─ TravelTools 5 个 @Tool（模型自主编排，ReAct）：
   │     getWeather（wttr.in 真实逐日预报）/ searchAttractions / getTravelTips
   │     / exchangeCurrency / getCurrentDate
   │
   ├─ 知识层 ─ Agentic RAG（TravelRagConfig 建库 + 工具内检索）：
   │     knowledge/*.md → TextReader → TokenTextSplitter → bge-m3 → SimpleVectorStore
   │     检索时 filterExpression 按 city 过滤（治跨城市污染）+ 来源标注可溯源
   │
   └─ 工程层 ─ 防御性解析（extractJson）/ 字段回填（backfillMissing）
         / 三层降级设计 / 前端骨架屏 + diff 高亮 / 导出（RFC5987 中文名 + 防目录穿越）
```

**一次典型请求的完整链路**（以"生成杭州 3 日攻略"为例）：
```
浏览器 → /plan/struct → ChatClient（系统提示词+5工具）
  → 模型决策：getCurrentDate → getWeather(杭州) → getTravelTips(RAG检索)
     → searchAttractions(杭州,美食) → searchAttractions(杭州,地标)   ← 模型自主编排 5 连调
  → 模型按 DayPlan schema 生成 JSON（weather 字段含逐日天气+注意事项）
  → BeanOutputConverter + extractJson 防御性解析 → Itinerary record 树
  → 存入会话状态（供后续 /plan/adjust 增量修改）→ 前端渲染行程卡片
```

## 里程碑

- [x] **M0 骨架**：独立工程 + 依赖复用 + `/plan` 单轮纯文本跑通
- [x] **M1 结构化输出**：`.entity(Itinerary.class)` record 树 + `/plan/stream` 流式 ✅ 2026-08-27
  - `/plan` / `/plan/struct` / `/plan/stream` 三形态共用一个 buildPrompt
  - 前端双模式：流式文档（打字机 + 首字延迟显示）/ 行程卡片（Day 卡片 + 点位时间线）
  - 实测：杭州 2 日 struct 8.2s 全字段正确；北京 1 日 stream 16.7s / 1661 个 SSE 事件
- [x] **M2 工具层**：5 个 `@Tool`，模型自主决策调用 ✅ 2026-08-27
  - 否判断兜底：工具返回「查不到+支持列表+建议」，模型降级常识推荐并标注"出发前核实"
  - 实测：杭州 → 模型自主编排 5 连调；伊斯坦布尔（库外）全走否判断 + 模型自主换算 CNY→USD
- [x] **M3 知识层**：Agentic RAG ✅ 2026-08-27
  - 知识库 6 城市 Markdown → 切块 → bge-m3 → SimpleVectorStore（city 进 metadata）
  - **filterExpression 按 city 过滤**：实测修复跨城市污染（搜"成都美食"混进北京块）
  - 实测：成都攻略引用熊猫基地 7:30 开园/鹤鸣茶社等知识库独有细节
- [x] **M4 多轮调整**：记忆 + 行程状态分离 ✅ 2026-08-27
  - /plan/adjust：当前行程 JSON 注入 prompt 增量修改，未提及的天不动
  - 实测：两轮调整各只动目标 Day；cid 隔离；行程状态正确迭代（第 2 轮基于第 1 轮结果）
  - 踩坑×2：①DeepSeek 工具调用+entity 组合偶尔先输出思考再给 JSON → BeanOutputConverter+extractJson 防御性解析；②prompt 里行程快照漏字段 → 模型全填 0 → Jackson 全量序列化 + backfillMissing 回填
- [x] **M5 体验升级** ✅ 2026-08-28
  - 真实天气 wttr.in（24 城中英映射；失败直接否判断——宁可没有不用假数据，Mock 已删）
  - 逐日预报 → DayPlan.weather → 前端天气条 + 导出行；实测 Day2 降雨 77% → 模型主动排室内线，数值与源逐项对齐
  - 导出双入口 + 流式模式每日天气 + 骨架屏
- [x] **M6 收尾** ✅ 2026-08-28
  - README 架构图（四层 + 请求全链路）/ 面试素材映射表回填 / docs/interview-qa.md 深挖问答

## 快速开始

前置：JDK 17+ / Maven / 环境变量 `DEEPSEEK_API_KEY`（M3 起还需本地 Ollama + bge-m3 模型）

```bash
mvn spring-boot:run
# 打开 http://localhost:8081/
# 或裸接口：
# http://localhost:8081/plan?destination=东京&days=3&budget=8000&preferences=美食,博物馆
```

> 端口 8081（8080 留给 spring-ai-hello）。Ollama 未启动时应用照常起（知识库为空，纯工具模式）。

## 目录结构

```
src/main/java/com/travel/agent/
├── TravelAgentApplication.java        # 启动类
├── config/
│   ├── TravelChatConfig.java          # ChatClient 集中装配（系统提示词 + defaultTools + 天气写进行程的指令）
│   └── TravelRagConfig.java           # RAG 建库：knowledge/*.md → 切块 → bge-m3 → SimpleVectorStore
├── controller/
│   ├── TravelController.java          # /plan、/plan/struct、/plan/stream、/plan/adjust
│   └── ExportController.java          # /plan/export、/plan/export/md（导出双入口）
├── domain/Itinerary.java              # 攻略 record 树（结构化输出目标格式，含 DayPlan.weather）
├── service/ItinerarySessionService.java  # 双状态：ChatMemory（对话）+ Map<cid,Itinerary>（行程）
└── tools/TravelTools.java             # 5 个 @Tool（天气/景点检索/贴士/汇率/日期）

src/main/resources/
├── application.yml                    # DeepSeek + Ollama 配置
├── knowledge/*.md                     # 6 城市知识库（东京/北京/杭州/成都/巴黎/伦敦）
└── static/index.html                  # 前端双模式页面
```

## 面试素材映射（全部实测，详见 docs/interview-qa.md；E1 存储对比详见 docs/memory-redis-vs-heap.md）

| # | 素材 | 一句话 | 来源 |
|---|---|---|---|
| 1 | 模型输出不可信的三道防御 | 格式防御(extractJson)·快照防御(全量序列化)·字段防御(backfill) | M4 双坑 |
| 2 | RAG 跨城市污染修复 | metadata 建库时打标，检索时 filterExpression 过滤——同机制可做权限隔离 | M3 |
| 3 | 对话记忆 ≠ 业务状态 | 记忆管过程（可滑窗），状态管结果（结构化持有），增量调整省 token | M4 |
| 4 | 降级策略分数据类型 | 实时观测（天气）宁可缺失不造假；静态参考才适合缓存兜底 | M5 |
| 5 | Agentic RAG vs 固定管道 | 检索变工具，模型自主决定查不查/查什么/查几轮 | M3 |
| 6 | embedding 中文选型 | bge-m3 中文召回精准（nomic 实测翻车已弃用） | hello 工程 Day4 |
| 7 | 工具粒度与否判断 | 4~5 个中粒度工具；查不到返回"支持列表+建议"让模型有路可走 | M2 |
| 8 | 放大清单（谈演进必背） | Redis 记忆/Milvus+增量索引/token 打点/限流脱敏/分级模型/容器化 | 全项目 |

## 企业级演进路线（下一阶段）

| 现在 | 目标 | 面试考点 | 状态 |
|---|---|---|---|
| ~~内存 ChatMemory + Map~~ | Redis 集中存储 | 多实例部署记忆串话 | ✅ E1 完成（2026-09-10） |
| SimpleVectorStore | Milvus/PGVector + 增量索引 | 向量库选型、索引更新 | ⬜ |
| ~~System.out 日志~~ | Micrometer token/延迟/成本打点 | 可观测性 | ✅ E3 完成（2026-09-17） |
| 裸接口 | 限流 + PII 脱敏 | 安全层 | ⬜ |
| 全 deepseek-chat | 分级模型 + FAQ 缓存 | 成本优化 | ⬜ |
| 手动起服务 | Docker Compose 全栈 | 部署 | ⬜ |

### E1 Redis 集中存储（2026-09-10）✅

- **自研 RedisChatMemory**（Spring AI 1.0.0 只有内存/JDBC 实现）：List 结构 + RPUSH 追加 + LTRIM 滑窗 20 条 + 滑动 TTL 24h；消息 role+text 精简序列化（tool metadata 不落库的取舍）
- **RedisItineraryStore**：行程状态 String 结构 JSON 整存整取，TTL 与记忆对齐——数据结构跟着访问模式走（List=有序可滑窗，String=整树读写）
- **降级设计（影子副本）**：写 Redis+内存双写，读 Redis 失败读实例内存影子——降级代价明确：单实例可用但跨实例共享/重启存活能力丢失
- ItinerarySessionService 重构为构造器注入（依赖倒置：存储策略可替换）
- **实测**：①跨重启状态连续性——重启后同 cid 直接调整成功，Day2 保持重启前的购物版、记忆 4→6 条累加；②redis-cli 可见真实 key（travel:chat:{cid} 4 条、travel:itinerary:{cid} TTL 86337s）；③Redis 故障（错端口 8082 实例）struct/adjust 全 200 + 降级日志
- **踩坑**：/plan/struct 写初始记忆漏传 CONVERSATION_ID → 落到 Advisor 默认"default"桶——内存版时代不可见，外置到 Redis 才现形（外置存储的可观测性红利）

### E3 可观测性：token/延迟/成本打点（2026-09-17）✅

- **CostTrackingAdvisor**（CallAdvisor，order -100 最外层）：一次请求一份账单——墙钟延迟 / token 输入输出 / 工具轮数 / 按单价估算成本（¥）。三条出口：控制台日志一行账单、Micrometer 指标（/actuator/metrics + /actuator/prometheus，Grafana 即插即用）、ThreadLocal 快照随响应透出（struct→X-Travel-Cost 响应头，adjust→响应体"成本"字段，前端状态栏展示）
- **ToolCallTracker**：每个 @Tool 方法报数 → travel.tool.calls{tool=xx} 按工具分 tag；请求级 ThreadLocal 计数得出"本次几轮工具调用"
- 单价配置化：travel.cost.input/output-per-million（官方调价只改 yml）
- **实测数字**（deepseek-chat，杭州 2 日）：主生成 10740ms / in 7148 + out 1768 tok / 6 工具轮 / ¥0.0284；adjust 6576ms / ¥0.0195
- **打点立刻暴露的浪费**：/plan/struct 生成后"补写一句初始上下文"走了带全套工具的 memoryChatClient——模型为这句废话又调了 4 轮工具、烧 4161 输入 token（¥0.0184，占主生成成本 65%）。E5 优化方向：补写用无工具轻量 client
- 踩坑①：Spring AI 1.0.0 GA 的 Advisor 签名是 `adviseCall(ChatClientRequest, CallAdvisorChain) → ChatClientResponse`（不是旧文档的 AdvisedRequest/ChatResponse），编译器当老师
- 踩坑②：**Tomcat 对非 ASCII 的 header 值静默丢弃**——X-Travel-Cost 值含中文时整个 header 凭空消失、零报错；同位置纯 ASCII 值正常。header 值必须 ASCII（或 RFC 5987 编码）
- 诚实边界：ChatResponse 的 Usage 只是最后一轮模型调用的用量，工具中间轮次拿不到（全量靠 Spring AI 原生 per-call observation）；流式接口未接（StreamAdvisor + usage 聚合 TODO）
