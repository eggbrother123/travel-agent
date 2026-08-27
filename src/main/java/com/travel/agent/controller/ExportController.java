package com.travel.agent.controller;

import com.travel.agent.domain.Itinerary;
import com.travel.agent.service.ItinerarySessionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M5 导出：把攻略落成 Markdown 文件下载——「AI 产物落地」的最后一公里。
 * 两个入口，对应两种产物形态：
 *  GET  /plan/export?cid=      会话里的 Itinerary 对象 → md（行程卡片模式）
 *  POST /plan/export/md        流式模式攒下来的 Markdown 原文 → 直接成文件（省一次转换）
 */
@RestController
public class ExportController {

    private final ItinerarySessionService sessionService;

    public ExportController(ItinerarySessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 导出指定会话的当前行程。
     * 试：http://localhost:8081/plan/export?cid=web-abc123（浏览器直接打开即下载）
     */
    @GetMapping("/plan/export")
    public ResponseEntity<String> export(@RequestParam String cid) {
        Itinerary it = sessionService.getItinerary(cid);
        if (it == null) {
            return ResponseEntity.badRequest().body("会话 " + cid + " 没有可导出的行程");
        }

        String md = toMarkdown(it);
        String filename = it.destination() + it.days() + "日攻略.md";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8))
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(md);
    }

    /**
     * 流式文档模式的导出：前端把 SSE 攒下来的 Markdown 原文 POST 过来，原样成文件。
     * 为什么走后端而不前端 Blob 下载：统一导出入口/以后加格式转换（PDF/图片）只改一处。
     */
    @PostMapping("/plan/export/md")
    public ResponseEntity<String> exportRawMd(@RequestBody ExportMdRequest req) {
        if (req.content() == null || req.content().isBlank()) {
            return ResponseEntity.badRequest().body("导出内容为空");
        }
        String filename = (req.filename() == null || req.filename().isBlank())
                ? "旅行攻略.md" : req.filename();
        // 文件名做一次清洗：去掉路径分隔符等危险字符，防目录穿越
        String safe = filename.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!safe.endsWith(".md")) safe += ".md";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(safe, java.nio.charset.StandardCharsets.UTF_8))
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(req.content());
    }

    /** 流式导出请求体：content = 攒下的 Markdown 原文，filename 可选 */
    public record ExportMdRequest(String content, String filename) {}

    /** Itinerary record 树 → 结构清晰的 Markdown（和页面行程卡片同构） */
    private String toMarkdown(Itinerary it) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(it.destination()).append(" ").append(it.days()).append(" 日旅行攻略\n\n");
        sb.append("> 预算：约 ").append(it.totalBudget()).append(" 元 · 由 travel-agent 生成\n\n");

        int dayNo = 1;
        for (Itinerary.DayPlan day : it.dayPlans()) {
            sb.append("## Day ").append(dayNo++).append(" · ").append(day.theme() == null ? "" : day.theme())
              .append("（预计 ¥").append(day.estimatedCost()).append("）\n\n");
            if (day.weather() != null && !day.weather().isBlank()) {
                sb.append("🌤️ ").append(day.weather()).append("\n\n");
            }
            int spotNo = 1;
            for (Itinerary.Spot s : day.spots()) {
                sb.append(spotNo++).append(". **").append(s.name()).append("**");
                if (s.type() != null && !s.type().isBlank()) sb.append("（").append(s.type()).append("）");
                sb.append("\n   - ").append(s.reason() == null ? "" : s.reason()).append("\n");
                if (s.transportToNext() != null && !s.transportToNext().isBlank()) {
                    sb.append("   - → 下一站：").append(s.transportToNext()).append("\n");
                }
            }
            if (day.mealSuggestion() != null && !day.mealSuggestion().isBlank()) {
                sb.append("\n🍽️ **用餐建议**：").append(day.mealSuggestion()).append("\n");
            }
            sb.append("\n");
        }

        if (it.tips() != null && !it.tips().isEmpty()) {
            sb.append("## 💡 实用贴士\n\n");
            for (String t : it.tips()) sb.append("- ").append(t).append("\n");
        }
        return sb.toString();
    }
}
