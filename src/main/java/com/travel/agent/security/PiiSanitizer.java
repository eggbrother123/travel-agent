package com.travel.agent.security;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * E4 PII 脱敏：用户输入进模型【之前】清洗——PII 不进 prompt 就不会出域发给模型厂商。
 *
 * 设计原则：
 *  1. 入口层做（Controller 显式调用），不做 Advisor——脱敏要在数据离开信任边界前发生，
 *     越早越好；放管道深处容易出现旁路（比如某个新接口忘了挂）
 *  2. 保留首尾片段而不是全打码：模型仍能理解语义（"138****5678"模型知道是手机号），
 *     演示/调试时人也能核对
 *  3. 审计只记【类型】不记原文——否则脱敏日志自己就成了 PII 泄漏点
 *
 * 匹配顺序有讲究：长而特异的先匹配（身份证→银行卡→手机→邮箱），
 * 否则 18 位身份证会被 16~19 位银行卡规则先吃掉。
 *
 * 诚实边界（面试主动讲）：正则脱敏是【保守防御】不是完备方案——
 * 格式变体（加空格、中文数字）会漏；零宽字符/Unicode 混淆绕不过 NER 模型。
 * 企业级做法：正则做第一层（便宜、快），敏感场景叠 NER 模型做第二层。
 */
@Component
public class PiiSanitizer {

    /** 身份证：18 位（末位可 X），前后不能是数字（防长数字串的子串误伤） */
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");

    /** 银行卡：16~19 位连续数字 */
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");

    /** 手机号：1 开头 11 位（第二位 3-9） */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    /** 邮箱：保留首字符 + 域名 */
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");

    private final MeterRegistry registry;

    public PiiSanitizer(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 脱敏入口：命中什么脱什么，审计只记类型与次数 */
    public String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String out = input;
        out = mask(out, ID_CARD,   m -> m.group().substring(0, 3) + "***********" + m.group().substring(14), "idcard");
        out = mask(out, BANK_CARD, m -> m.group().substring(0, 4) + "************" + m.group().substring(m.group().length() - 4), "bankcard");
        out = mask(out, PHONE,     m -> m.group().substring(0, 3) + "****" + m.group().substring(7), "phone");
        out = mask(out, EMAIL,     m -> {
            String v = m.group();
            int at = v.indexOf('@');
            return v.charAt(0) + "***" + v.substring(at);
        }, "email");
        return out;
    }

    /** 单个规则执行 + 审计（类型计数；绝不记原文） */
    private String mask(String input, Pattern pattern, java.util.function.Function<Matcher, String> replacer, String type) {
        Matcher m = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        int hits = 0;
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(m)));
            hits++;
        }
        m.appendTail(sb);
        if (hits > 0) {
            System.out.println(">>> [PII] 脱敏 " + type + " x" + hits + "（已阻止进入模型 prompt）");
            registry.counter("travel.pii.masked", "type", type).increment(hits);
        }
        return sb.toString();
    }
}
